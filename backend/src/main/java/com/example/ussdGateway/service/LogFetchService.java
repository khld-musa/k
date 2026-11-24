package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.repository.UssdServerRepository;
import com.example.ussdGateway.util.ServerLogPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Once-daily scheduled job that pulls each active {@link UssdServer}'s USSD log files from its
 * remote FTP node into local disk, then deletes them remotely to free up space there, and finally
 * triggers {@link LogIngestionService#ingestAll()} so the newly-pulled files are parsed right away.
 * <p>
 * Only one calendar day's file is ever downloaded once: each file is written to a {@code .part}
 * temp name first, verified against the remote file size, and only then atomically renamed into
 * place - so {@link LogIngestionService} never sees a partially-downloaded file, and the remote
 * copy is only deleted once the local copy is confirmed good. {@link UssdServer#getLastFetchDate()}
 * tracks the last calendar day pulled so re-runs resume from the day after it and backfill gaps
 * (e.g. if this job was down for a few days).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogFetchService {

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** How far back the first run for a server looks for existing files on the remote FTP server. */
    private static final int BACKFILL_DAYS = 90;
    private static final int FTP_PORT = 21;
    private static final String PART_SUFFIX = ".part";

    private final UssdServerRepository ussdServerRepository;
    private final LogIngestionService logIngestionService;

    @Value("${ussd.log.file-pattern:TIBCO-%s.log}")
    private String filePattern;

    @Value("${ussd.ftp.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${ussd.ftp.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${ussd.ftp.delete-after-pull:true}")
    private boolean deleteAfterPull;

    @Value("${ussd.ftp.enabled:false}")
    private boolean ftpEnabled;

    @Value("${ussd.log.base-path:${USSD_LOG_BASE_PATH:/mediation}}")
    private String configuredBasePath;

    /**
     * Runs once daily. Default: disabled via "-". If enabled, pulls every server's new log files in,
     * then ingests whatever was pulled.
     */
    @Scheduled(cron = "${ussd.log.fetch-cron:-}")
    public void fetchAndIngestAll() {
        if (!ftpEnabled) {
            log.info("FTP fetch worker is temporarily disabled (ussd.ftp.enabled=false). Skipping.");
            return;
        }
        fetchAll();
        logIngestionService.ingestAll();
    }

    /**
     * Pulls new log files for every active USSD server that has FTP credentials configured. Safe
     * to call repeatedly/manually (e.g. via the admin "run now" endpoint) - a run with nothing new
     * on the remote server is a no-op.
     */
    public List<FetchResult> fetchAll() {
        List<UssdServer> servers = ussdServerRepository.findByActiveTrue();
        List<FetchResult> results = new ArrayList<>();
        for (UssdServer server : servers) {
            FetchResult result;
            try {
                result = fetchServer(server);
            } catch (Exception e) {
                log.error("FTP fetch failed for server {} ({}): {}",
                        server.getName(), server.getIpAddress(), e.getMessage(), e);
                result = new FetchResult(server.getName(), FetchResult.Status.ERROR, 0, e.getMessage());
            }
            log.info("FTP fetch for server {}: {} - {}", server.getName(), result.status(), result.message());
            results.add(result);
        }
        if (results.isEmpty()) {
            log.info("FTP fetch run: no active USSD servers found.");
        }
        return results;
    }

    private FetchResult fetchServer(UssdServer server) throws IOException {
        if (server.getFtpUsername() == null || server.getFtpUsername().isBlank()
                || server.getFtpPassword() == null || server.getFtpPassword().isBlank()) {
            return new FetchResult(server.getName(), FetchResult.Status.SKIPPED, 0,
                    "No FTP username/password configured for this server.");
        }
        String remotePath = server.getFtpRemotePath();
        if (remotePath == null || remotePath.isBlank()) {
            return new FetchResult(server.getName(), FetchResult.Status.SKIPPED, 0,
                    "No FTP remote path configured for this server.");
        }
        Path localDir = ServerLogPaths.resolveServerDir(server, configuredBasePath);
        if (localDir == null) {
            return new FetchResult(server.getName(), FetchResult.Status.SKIPPED, 0,
                    "No local log path configured for this server.");
        }
        Files.createDirectories(localDir);

        FTPClient client = connect(server);
        try {
            Map<String, FTPFile> remoteFiles = listRemoteFiles(client, remotePath);
            int filesFetched = pullMissingDays(server, client, remotePath, localDir, remoteFiles);
            return filesFetched > 0
                    ? new FetchResult(server.getName(), FetchResult.Status.FETCHED, filesFetched,
                            "Pulled " + filesFetched + " file(s).")
                    : new FetchResult(server.getName(), FetchResult.Status.NO_NEW_FILES, 0,
                            "No new files to pull.");
        } finally {
            disconnect(client, server);
        }
    }

    private FTPClient connect(UssdServer server) throws IOException {
        FTPClient client = new FTPClient();
        client.setConnectTimeout(connectTimeoutMs);
        client.setDefaultTimeout(readTimeoutMs);
        client.setDataTimeout(java.time.Duration.ofMillis(readTimeoutMs));
        client.setBufferSize(256 * 1024);

        client.connect(server.getIpAddress(), FTP_PORT);
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            client.disconnect();
            throw new IOException("FTP server refused connection for " + server.getName() + ": reply " + reply);
        }

        if (!client.login(server.getFtpUsername(), server.getFtpPassword())) {
            client.disconnect();
            throw new IOException("FTP login failed for server " + server.getName());
        }
        client.enterLocalPassiveMode();
        client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
        client.setSoTimeout(readTimeoutMs);
        return client;
    }

    private void disconnect(FTPClient client, UssdServer server) {
        try {
            if (client.isConnected()) {
                try {
                    client.logout();
                } catch (Exception ignored) {}
                client.disconnect();
            }
        } catch (IOException e) {
            log.warn("Error closing FTP connection to server {} ({}): {}",
                    server.getName(), server.getIpAddress(), e.getMessage());
        }
    }

    /** Lists the remote log directory once per server run, keyed by file name, to avoid a LIST round-trip per day. */
    private Map<String, FTPFile> listRemoteFiles(FTPClient client, String remotePath) throws IOException {
        FTPFile[] files = client.listFiles(remotePath);
        Map<String, FTPFile> byName = new HashMap<>();
        List<String> rawEntries = new ArrayList<>();
        if (files != null) {
            for (FTPFile f : files) {
                if (f == null) {
                    continue;
                }
                rawEntries.add(f.getName() + " (isFile=" + f.isFile() + ", size=" + f.getSize() + ")");
                if (f.isFile()) {
                    // Some FTP servers return the full remote path rather than just the file's
                    // basename in LIST output - normalize so lookups by plain file name still match.
                    String name = f.getName();
                    String baseName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                    byName.put(baseName, f);
                }
            }
        }
        log.info("FTP directory listing for {}: {} entr{} seen - {}",
                remotePath, rawEntries.size(), rawEntries.size() == 1 ? "y" : "ies", rawEntries);
        return byName;
    }

    /** @return how many days' files were actually pulled in this run. */
    private int pullMissingDays(UssdServer server, FTPClient client, String remotePath, Path localDir,
                                 Map<String, FTPFile> remoteFiles) {
        LocalDate today = LocalDate.now();
        LocalDate lastFetchDate = server.getLastFetchDate();
        LocalDate startLimit = today.minusDays(BACKFILL_DAYS);

        LocalDate cursor;
        if (lastFetchDate == null) {
            cursor = findOldestRemoteDate(remoteFiles, startLimit, today.minusDays(1)).orElse(today.minusDays(1));
        } else if (lastFetchDate.isBefore(today.minusDays(1))) {
            cursor = lastFetchDate.plusDays(1);
        } else {
            cursor = today; // Already up to date with completed days
        }
        int filesFetched = 0;

        // Only pull completed past calendar days (cursor.isBefore(today)); today's file is actively being written to
        while (cursor.isBefore(today)) {
            Optional<String> remoteNameOpt = findRemoteFileName(remoteFiles, cursor);

            if (remoteNameOpt.isEmpty()) {
                String expectedName = String.format("TIBCO-%s.cdr", cursor.format(FILE_DATE_FORMAT));
                // Only consider past completed days when checking if a later file exists
                if (laterRemoteFileExists(remoteFiles, cursor, today.minusDays(1))) {
                    // A newer past file is already there, so this day's file will never come.
                    log.warn("No USSD CDR/log file for server {} on {} ({}) but later files exist remotely; skipping this day.",
                            server.getName(), cursor, expectedName);
                    server.setLastFetchDate(cursor);
                    ussdServerRepository.save(server);
                    cursor = cursor.plusDays(1);
                    continue;
                }
                // Hasn't appeared on the remote node yet - retry this date on the next run.
                log.info("No USSD CDR/log file found remotely for server {} on {} yet ({}); will retry next run.",
                        server.getName(), cursor, expectedName);
                break;
            }

            String remoteFileName = remoteNameOpt.get();
            FTPFile remoteFile = remoteFiles.get(remoteFileName);
            String localLogFileName = toLocalLogFileName(remoteFileName, cursor);
            String remoteFullPath = joinRemotePath(remotePath, remoteFileName);

            boolean downloaded = downloadAndVerify(client, remoteFullPath, localDir, localLogFileName, remoteFile.getSize());
            if (!downloaded) {
                // Stop here and retry this same day next run rather than risking skipping ahead over a bad pull.
                break;
            }
            filesFetched++;

            // Only delete completed past days from remote FTP; keep today's active file intact!
            if (deleteAfterPull && cursor.isBefore(today)) {
                try {
                    if (!client.deleteFile(remoteFullPath)) {
                        log.warn("Could not delete remote file {} on server {} after pulling it (reply: {})",
                                remoteFullPath, server.getName(), client.getReplyString());
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete remote file {} on server {}: {}",
                            remoteFullPath, server.getName(), e.getMessage());
                }
            }

            server.setLastFetchDate(cursor);
            ussdServerRepository.save(server);
            cursor = cursor.plusDays(1);
        }
        return filesFetched;
    }

    /**
     * Resolves the remote filename for the given date.
     * Looks for .cdr file (as on remote FTP), then .log, or any file ending in .cdr with the date.
     */
    private Optional<String> findRemoteFileName(Map<String, FTPFile> remoteFiles, LocalDate date) {
        String dateStr = date.format(FILE_DATE_FORMAT);
        String expectedCdr = String.format("TIBCO-%s.cdr", dateStr);
        if (remoteFiles.containsKey(expectedCdr)) {
            return Optional.of(expectedCdr);
        }
        String patternCdr = filePattern.replace(".log", ".cdr");
        String formattedCdr = String.format(patternCdr, dateStr);
        if (remoteFiles.containsKey(formattedCdr)) {
            return Optional.of(formattedCdr);
        }
        String formattedLog = String.format(filePattern, dateStr);
        if (remoteFiles.containsKey(formattedLog)) {
            return Optional.of(formattedLog);
        }
        // Fallback: search for any remote file ending in .cdr containing the date string
        for (String name : remoteFiles.keySet()) {
            if (name.toLowerCase().endsWith(".cdr") && name.contains(dateStr)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /**
     * Converts a remote filename (e.g. TIBCO-2026-10-14.cdr) to a local .log filename (TIBCO-2026-10-14.log)
     * so it matches the system's log ingestion logic.
     */
    private String toLocalLogFileName(String remoteFileName, LocalDate date) {
        if (remoteFileName.toLowerCase().endsWith(".cdr")) {
            return remoteFileName.substring(0, remoteFileName.length() - 4) + ".log";
        }
        if (!remoteFileName.toLowerCase().endsWith(".log")) {
            return remoteFileName + ".log";
        }
        return remoteFileName;
    }

    /**
     * Downloads a remote file to {@code localDir/fileName}, via a {@code .part} temp file that's
     * only renamed into place once its size matches what the remote server reports - so a failed
     * or partial transfer never leaves a file at the final name for ingestion to pick up.
     */
    private boolean downloadAndVerify(FTPClient client, String remoteFullPath, Path localDir, String fileName,
                                       long remoteSize) {
        Path partFile = localDir.resolve(fileName + PART_SUFFIX);
        Path finalFile = localDir.resolve(fileName);
        try {
            boolean retrieved;
            try (OutputStream out = Files.newOutputStream(partFile,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                retrieved = client.retrieveFile(remoteFullPath, out);
            }
            if (!retrieved) {
                log.error("FTP download failed for {} (reply: {})", remoteFullPath, client.getReplyString());
                Files.deleteIfExists(partFile);
                return false;
            }

            long localSize = Files.size(partFile);
            if (remoteSize >= 0 && localSize != remoteSize) {
                log.error("Downloaded file {} size mismatch (local {} vs remote {}); discarding and will retry next run.",
                        remoteFullPath, localSize, remoteSize);
                Files.deleteIfExists(partFile);
                return false;
            }

            Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                File f = finalFile.toFile();
                f.setReadable(true, false);
                f.setWritable(true, false);
            } catch (Exception ignored) {}
            log.info("Pulled {} ({} bytes) to {}", remoteFullPath, localSize, finalFile);
            return true;
        } catch (IOException e) {
            log.error("Error downloading {}: {}", remoteFullPath, e.getMessage(), e);
            try {
                Files.deleteIfExists(partFile);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            return false;
        }
    }

    private boolean laterRemoteFileExists(Map<String, FTPFile> remoteFiles, LocalDate after, LocalDate until) {
        for (LocalDate d = after.plusDays(1); !d.isAfter(until); d = d.plusDays(1)) {
            if (findRemoteFileName(remoteFiles, d).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private Optional<LocalDate> findOldestRemoteDate(Map<String, FTPFile> remoteFiles, LocalDate notBefore, LocalDate notAfter) {
        for (LocalDate d = notBefore; !d.isAfter(notAfter); d = d.plusDays(1)) {
            if (findRemoteFileName(remoteFiles, d).isPresent()) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    private String joinRemotePath(String remotePath, String fileName) {
        return remotePath.endsWith("/") ? remotePath + fileName : remotePath + "/" + fileName;
    }
}
