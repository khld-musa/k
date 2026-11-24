package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.entity.UssdTransaction;
import com.example.ussdGateway.repository.UssdServerRepository;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import com.example.ussdGateway.util.ServerLogPaths;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ingests each active {@link UssdServer}'s USSD log file, parses it via
 * {@link IngestionChunkProcessor}, and upserts the resulting call records into {@code ussd_transactions}.
 * <p>
 * <b>Performance design for large files (100 MB+, millions of lines):</b>
 * <ul>
 *   <li>Lines are streamed from disk — the file is <b>never</b> fully loaded into memory.</li>
 *   <li>Lines are processed in configurable chunks (default 5 000). Each chunk is parsed,
 *       deduplicated against the DB, and committed in its own transaction via {@link IngestionChunkProcessor}.
 *       This makes records immediately visible in report queries while ingestion continues.</li>
 *   <li>Hibernate JDBC batching ({@code hibernate.jdbc.batch_size}) groups INSERTs/UPDATEs into
 *       a single JDBC round-trip per batch — dramatically reducing network + PostgreSQL overhead.</li>
 *   <li>Progress is logged every chunk so operators can track throughput on long ingestion runs.</li>
 * </ul>
 * <p>
 * Log files are pulled in from the remote FTP nodes once a day (around 02:00) by
 * {@link LogFetchService}, which calls {@link #ingestAll()} immediately afterwards - there is no
 * separate schedule here. Each server's files live locally under
 * {@code {server.getLocalLogPath()}/{server.getName()}/} and only ever contain the <em>previous</em>
 * calendar day's traffic in full - there is no partially-written "today" file to tail on this
 * server. Each per-server file is therefore read exactly once, in full, when it first appears;
 * {@link UssdServer#getLastSyncDate()} tracks the last calendar day that was fully ingested so
 * re-runs (chained or manual) resume from the day after it and automatically backfill any gap
 * (e.g. if this job was down for a few days).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogIngestionService {

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** How far back the first run for a server looks for existing log files. */
    private static final int BACKFILL_DAYS = 90;

    private final UssdServerRepository ussdServerRepository;
    private final IngestionChunkProcessor ingestionChunkProcessor;
    private final com.example.ussdGateway.util.ReportResultCache reportResultCache;
    private final org.springframework.cache.CacheManager cacheManager;

    @Value("${ussd.log.file-pattern:TIBCO-%s.log}")
    private String filePattern;

    /** How many raw log lines to accumulate before parsing + flushing a chunk. */
    @Value("${ussd.ingestion.chunk-size:5000}")
    private int chunkSize;

    @Value("${ussd.ingestion.enabled:true}")
    private boolean ingestionEnabled;

    @Value("${ussd.ingestion.delete-after-ingest:true}")
    private boolean deleteAfterIngest;

    @Value("${ussd.log.base-path:${USSD_LOG_BASE_PATH:/mediation}}")
    private String configuredBasePath;

    private static final Pattern FILE_DATE_PATTERN = Pattern.compile("(\\d{4})[-_]?(\\d{2})[-_]?(\\d{2})");

    /**
     * Periodic USSD log ingestion run - defaults to every 15 minutes.
     * Picks up newly arrived log files in /mediation/{server_name}, parses them,
     * updates the database with missing transactions and reasons, and deletes the log file.
     */
    @Scheduled(cron = "${ussd.ingestion.cron:0 */15 * * * *}")
    public void scheduledIngest() {
        if (!ingestionEnabled) {
            log.debug("Scheduled USSD ingestion is disabled (ussd.ingestion.enabled=false)");
            return;
        }
        log.info("Starting scheduled 15-minute USSD log ingestion run from directories...");
        ingestAll(false);
    }

    /**
     * Standard ingestion: reads and upserts new/today's USSD transactions for every active server.
     */
    public void ingestAll() {
        ingestAll(false);
    }

    /**
     * Full re-ingestion: reads ALL available log files on disk (both today's and all previous days),
     * upserting missing calls and populating missing fields (reason, tibcoResponse, etc.) across the DB.
     */
    public void reingestAll() {
        ingestAll(true);
    }

    private final java.util.concurrent.atomic.AtomicBoolean isIngesting = new java.util.concurrent.atomic.AtomicBoolean(false);

    @CacheEvict(value = com.example.ussdGateway.config.CacheConfig.REPORTS_CACHE, allEntries = true)
    public void ingestAll(boolean reingestAll) {
        if (!isIngesting.compareAndSet(false, true)) {
            log.warn("USSD log ingestion is already in progress; skipping concurrent run.");
            return;
        }
        try {
            List<UssdServer> servers = ussdServerRepository.findByActiveTrue();
            for (UssdServer server : servers) {
                try {
                    ingestServer(server, reingestAll);
                } catch (Exception e) {
                    log.error("Failed to ingest USSD logs for server {} ({}): {}",
                            server.getName(), server.getIpAddress(), e.getMessage(), e);
                }
            }
        } finally {
            try {
                // Explicitly clear both in-memory and Redis caches
                reportResultCache.clear();
                org.springframework.cache.Cache cache = cacheManager.getCache(com.example.ussdGateway.config.CacheConfig.REPORTS_CACHE);
                if (cache != null) {
                    cache.clear();
                }
            } catch (Exception ce) {
                log.warn("Error clearing reports cache after ingestion: {}", ce.getMessage());
            }
            isIngesting.set(false);
        }
    }

    private void ingestServer(UssdServer server, boolean reingestAll) throws Exception {
        Path localDir = resolveExistingServerDir(server);
        if (localDir == null) {
            log.warn("Server {} has no log path configured or accessible; skipping USSD log ingestion.", server.getName());
            return;
        }

        List<Path> logFiles = findAllLogFiles(localDir);
        if (logFiles.isEmpty()) {
            log.debug("No USSD log files found for server {} in {}", server.getName(), localDir);
            return;
        }

        log.info("Processing {} log file(s) for server {} in {} (reingestAll={})",
                logFiles.size(), server.getName(), localDir, reingestAll);

        LocalDate today = LocalDate.now();
        LocalDate lastSyncDate = server.getLastSyncDate();
        LocalDate maxIngestedDate = lastSyncDate;

        for (Path file : logFiles) {
            if (!isFileReadyForIngestion(file)) {
                log.info("File {} for server {} is not ready (empty or actively transferring); skipping this cycle.",
                        file.getFileName(), server.getName());
                continue;
            }

            LocalDate fileDate = extractDateFromFileName(file.getFileName().toString());
            // When deleteAfterIngest is enabled, any file on disk is an un-ingested file newly pushed by the remote server.
            boolean shouldIngest = reingestAll
                    || deleteAfterIngest
                    || lastSyncDate == null
                    || fileDate == null
                    || fileDate.isAfter(lastSyncDate)
                    || fileDate.equals(today);

            if (shouldIngest) {
                boolean success = false;
                try {
                    ingestFileStreaming(server, file);
                    success = true;
                    if (fileDate != null && (maxIngestedDate == null || fileDate.isAfter(maxIngestedDate))) {
                        maxIngestedDate = fileDate;
                    }
                } catch (Exception e) {
                    log.error("Failed to ingest USSD log file {} for server {}: {}. Continuing to next file.",
                            file, server.getName(), e.getMessage(), e);
                }

                if (success && deleteAfterIngest) {
                    // Always delete successfully ingested file so the remote push script
                    // can push the next hour's log cleanly without conflict or blockage.
                    deleteProcessedFile(file, server.getName());
                }
            }
        }

        if (maxIngestedDate != null && (lastSyncDate == null || maxIngestedDate.isAfter(lastSyncDate))) {
            server.setLastSyncDate(maxIngestedDate);
            ussdServerRepository.save(server);
        }
    }

    /**
     * Streams a log file line-by-line, accumulates lines into chunks, and processes each chunk
     * independently. This keeps memory usage bounded: at most {@code chunkSize} lines + their
     * parsed transactions are in memory at any time, regardless of total file size.
     */
    private void ingestFileStreaming(UssdServer server, Path file) throws IOException {
        long fileSize = Files.size(file);
        log.info("Starting streamed ingestion of {} ({} bytes / {} MB) for server {}",
                file.getFileName(), fileSize, fileSize / (1024 * 1024), server.getName());

        long startTime = System.currentTimeMillis();
        long totalLinesRead = 0;
        long totalRecordsSaved = 0;
        int chunkNumber = 0;

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        File f = file.toFile();
        f.setReadable(true, false);
        f.setWritable(true, false);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), decoder), 256 * 1024)) {

            List<String> chunkLines = new ArrayList<>(chunkSize);
            String line;
            while ((line = reader.readLine()) != null) {
                chunkLines.add(line);
                totalLinesRead++;

                if (chunkLines.size() >= chunkSize) {
                    chunkNumber++;
                    try {
                        int saved = ingestionChunkProcessor.processChunk(server.getIpAddress(), chunkLines, chunkNumber, totalLinesRead);
                        totalRecordsSaved += saved;
                    } catch (Exception chunkEx) {
                        log.error("Error processing chunk #{} (around line {}) for server {}: {}. Continuing with remaining chunks.",
                                chunkNumber, totalLinesRead, server.getName(), chunkEx.getMessage());
                    }
                    chunkLines = new ArrayList<>(chunkSize);
                }
            }

            // Process any remaining lines in the last partial chunk
            if (!chunkLines.isEmpty()) {
                chunkNumber++;
                try {
                    int saved = ingestionChunkProcessor.processChunk(server.getIpAddress(), chunkLines, chunkNumber, totalLinesRead);
                    totalRecordsSaved += saved;
                } catch (Exception chunkEx) {
                    log.error("Error processing final chunk #{} for server {}: {}.",
                            chunkNumber, server.getName(), chunkEx.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double seconds = elapsed / 1000.0;
        long linesPerSec = seconds > 0 ? (long) (totalLinesRead / seconds) : totalLinesRead;
        log.info("Finished ingestion of {} for server {}: {} lines read, {} records saved, "
                        + "{} chunks, {}s elapsed ({} lines/sec)",
                file.getFileName(), server.getName(), totalLinesRead, totalRecordsSaved,
                chunkNumber, String.format("%.1f", seconds), linesPerSec);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private boolean isFileReadyForIngestion(Path file) {
        try {
            long size1 = Files.size(file);
            if (size1 == 0) {
                log.info("File {} is currently 0 bytes; waiting for remote transfer to populate data.", file.getFileName());
                return false;
            }
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            if (System.currentTimeMillis() - lastModified < 3000) {
                // File was modified within the last 3 seconds — check if actively growing
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long size2 = Files.size(file);
                if (size2 > size1) {
                    log.info("File {} is actively being written/transferred ({} -> {} bytes). Deferring to next run.",
                            file.getFileName(), size1, size2);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Cannot verify readiness for file {}: {}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    private Path resolveExistingServerDir(UssdServer server) {
        return ServerLogPaths.resolveServerDir(server, configuredBasePath);
    }

    private List<Path> findAllLogFiles(Path dir) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> rawFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            rawFiles = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list log files in directory {}: {}", dir, e.getMessage());
            return Collections.emptyList();
        }

        return rawFiles.stream()
                .map(this::ensureLogExtension)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".tmp") || lower.endsWith(".part") || lower.endsWith(".temp")
                            || lower.endsWith(".writing") || lower.endsWith(".swp") || lower.endsWith(".gz")
                            || lower.endsWith(".zip") || lower.endsWith(".bak")) {
                        return false;
                    }
                    return name.startsWith("TIBCO-") || name.endsWith(".log") || name.endsWith(".cdr") || name.endsWith(".txt");
                })
                .sorted(Comparator.comparing(Path::getFileName))
                .collect(Collectors.toList());
    }

    private Path ensureLogExtension(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".cdr")) {
            String logFileName = fileName.substring(0, fileName.length() - 4) + ".log";
            Path logFile = file.resolveSibling(logFileName);
            try {
                Files.move(file, logFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("Renamed local CDR file {} to {}", file, logFile);
                return logFile;
            } catch (IOException e) {
                log.warn("Failed to rename {} to {}: {}", file, logFile, e.getMessage());
                return file;
            }
        }
        return file;
    }

    private LocalDate extractDateFromFileName(String filename) {
        Matcher m = FILE_DATE_PATTERN.matcher(filename);
        if (m.find()) {
            try {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void deleteProcessedFile(Path file, String serverName) {
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Deleted processed USSD log file {} for server {}", file.getFileName(), serverName);
                return;
            }
        } catch (Exception e) {
            log.warn("Files.deleteIfExists failed for {} ({}). Trying File.delete() fallback...", file.getFileName(), e.getMessage());
        }

        try {
            java.io.File f = file.toFile();
            if (f.exists()) {
                f.setWritable(true);
                if (f.delete()) {
                    log.info("Deleted processed USSD log file {} via fallback for server {}", file.getFileName(), serverName);
                } else {
                    log.error("Unable to delete USSD log file {} for server {} (file may be locked or permissions restricted). canRead={}, canWrite={}",
                            file.getFileName(), serverName, f.canRead(), f.canWrite());
                }
            }
        } catch (Exception e) {
            log.error("Failed to delete processed USSD log file {} for server {}: {}", file.getFileName(), serverName, e.getMessage());
        }
    }
}
