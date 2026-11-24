package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.request.ReportJobRequestDto;
import com.example.ussdGateway.api.dto.response.ApnReportSummaryDto;
import com.example.ussdGateway.api.dto.response.ReportJobDto;
import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.example.ussdGateway.entity.ReportJob;
import com.example.ussdGateway.repository.ReportJobRepository;
import com.example.ussdGateway.util.InputSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class ReportJobService {

    private final ReportJobRepository reportJobRepository;
    private final ReportService reportService;
    private final CsvExportService csvExportService;
    private final ApnService apnService;
    private final ObjectMapper objectMapper;

    @Value("${app.export.timeout-minutes:180}")
    private long exportTimeoutMinutes;

    @Value("${app.export.stale-minutes:15}")
    private long staleMinutes;

    @Value("${app.export.min-free-mb:2048}")
    private long minFreeMb;

    @Value("${app.export.dir:data/exports}")
    private String exportDirConfig;

    @Value("${app.export.ttl-hours:24}")
    private int ttlHours;

    @Lazy
    @Autowired
    private ReportJobService self;

    public ReportJobService(
            ReportJobRepository reportJobRepository,
            ReportService reportService,
            CsvExportService csvExportService,
            ApnService apnService,
            ObjectMapper objectMapper) {
        this.reportJobRepository = reportJobRepository;
        this.reportService = reportService;
        this.csvExportService = csvExportService;
        this.apnService = apnService;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue a new report export job. Validates limits, persists QUEUED job, and fires async generation.
     */
    public ReportJobDto createJob(ReportJobRequestDto request, String username) {
        long activeCount = reportJobRepository.countByRequestedByAndStatusIn(
                username, List.of("QUEUED", "PROCESSING"));
        if (activeCount >= 5) {
            throw new IllegalStateException("You already have 5 export jobs currently queued or processing. Please wait for them to finish.");
        }

        Map<String, String> filters = request.getFilters() != null
                ? new TreeMap<>(request.getFilters()) : Collections.emptyMap();
        String filtersJson;
        try {
            filtersJson = objectMapper.writeValueAsString(filters);
        } catch (Exception e) {
            filtersJson = "{}";
        }
        String reportType = request.getReportType().toUpperCase().trim();

        // Identical request already running or waiting: reuse it instead of doing the work twice
        Optional<ReportJob> duplicate = reportJobRepository
                .findFirstByRequestedByAndReportTypeAndFiltersJsonAndStatusInOrderByRequestedAtDesc(
                        username, reportType, filtersJson, List.of("QUEUED", "PROCESSING"));
        if (duplicate.isPresent()) {
            return toDto(duplicate.get());
        }

        String fileName = buildFileName(request.getReportType(), filters);

        ReportJob job = ReportJob.builder()
                .reportType(reportType)
                .status("QUEUED")
                .filtersJson(filtersJson)
                .fileName(fileName)
                .requestedBy(username)
                .requestedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(Math.max(1, ttlHours)))
                .build();

        job = reportJobRepository.save(job);
        log.info("Created background export job id={} type={} for user={}", job.getId(), job.getReportType(), username);

        try {
            self.processJobAsync(job.getId());
        } catch (RuntimeException e) {
            reportJobRepository.markFailed(job.getId(), "Export queue is full, please try again shortly", LocalDateTime.now());
            log.error("Could not dispatch export job id={}", job.getId(), e);
            throw new IllegalStateException("The export queue is full. Please try again in a few minutes.");
        }

        return toDto(job);
    }

    private final Map<UUID, Thread> activeJobThreads = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Background asynchronous worker executing the report generation.
     */
    @Async("reportExportExecutor")
    public void processJobAsync(UUID jobId) {
        activeJobThreads.put(jobId, Thread.currentThread());
        Path partFile = null;
        Path targetFile = null;
        try {
            // Atomic QUEUED -> PROCESSING; skips jobs that were cancelled while queued
            if (reportJobRepository.markProcessing(jobId, LocalDateTime.now()) == 0) {
                log.info("Job {} is no longer QUEUED; skipping execution", jobId);
                return;
            }
            ReportJob job = reportJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            log.info("Started generating export report job id={} type={}", jobId, job.getReportType());

            Path exportDir = resolveExportDirectory();
            long freeMb = Files.getFileStore(exportDir).getUsableSpace() / (1024 * 1024);
            if (freeMb < minFreeMb) {
                throw new IllegalStateException("Not enough free disk space on the server for the export ("
                        + freeMb + " MB free, " + minFreeMb + " MB required)");
            }

            targetFile = exportDir.resolve(jobId + "_" + sanitizeFileName(job.getFileName()));
            partFile = exportDir.resolve(targetFile.getFileName() + ".part");
            Map<String, String> filters = parseFilters(job.getFiltersJson());
            String baseName = baseEntryName(job.getFileName());

            long rows;
            try (FileOutputStream fos = new FileOutputStream(partFile.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 256 * 1024)) {
                rows = switch (job.getReportType()) {
                    case "TIBCO_ERRORS" -> generateTibcoErrorReport(jobId, filters, bos);
                    case "APN" -> generateApnReport(jobId, filters, baseName, bos);
                    default -> generateGeneralReport(jobId, filters, baseName, bos);
                };
                bos.flush();
            }

            checkActive();
            Files.move(partFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long fileSize = Files.size(targetFile);
            if (reportJobRepository.markCompleted(jobId, targetFile.toString(), fileSize, rows, LocalDateTime.now()) == 0) {
                Files.deleteIfExists(targetFile);
                log.info("Export job id={} finished but was cancelled or timed out meanwhile; file discarded", jobId);
            } else {
                log.info("Completed export report job id={} rows={} size={} bytes file={}", jobId, rows, fileSize, targetFile);
            }
            targetFile = null;
        } catch (Exception e) {
            log.error("Failed to generate export report for job id=" + jobId, e);
            String err = (e instanceof InterruptedException || Thread.currentThread().isInterrupted())
                    ? "Export was cancelled or timed out"
                    : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            reportJobRepository.markFailed(jobId, truncate(err, 1000), LocalDateTime.now());
        } finally {
            activeJobThreads.remove(jobId);
            deleteQuietly(partFile);
            if (targetFile != null) {
                deleteQuietly(targetFile);
            }
        }
    }

    private void checkActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Export was cancelled or timed out");
        }
    }

    private CsvExportService.Progress progressFor(UUID jobId, long total) {
        return rows -> {
            checkActive();
            reportJobRepository.updateProgress(jobId, rows, total, LocalDateTime.now());
        };
    }

    private void ensureWithinLimit(long total) {
        if (total > csvExportService.getMaxRows()) {
            throw new IllegalStateException("The report contains " + total + " rows, above the maximum of "
                    + csvExportService.getMaxRows() + ". Please narrow the filters.");
        }
    }

    private long generateTibcoErrorReport(UUID jobId, Map<String, String> filters, BufferedOutputStream bos) throws Exception {
        String effectiveStart = getFilter(filters, "date", getFilter(filters, "startDate", ""));
        String effectiveEnd = getFilter(filters, "date", getFilter(filters, "endDate", effectiveStart));
        String shortCode = getFilter(filters, "shortCode", null);
        String errorCode = getFilter(filters, "errorCode", null);
        String message = getFilter(filters, "message", null);

        long[] totals = reportService.getScalarTotals(effectiveStart, effectiveEnd, shortCode, errorCode, message);
        ensureWithinLimit(totals[2]);
        checkActive();

        String dateLabel = effectiveStart.equals(effectiveEnd) ? effectiveStart : effectiveStart + " to " + effectiveEnd;
        CsvExportService.UssdFilter filter = CsvExportService.UssdFilter.of(
                effectiveStart, effectiveEnd, null, "FAILED", shortCode, null, errorCode, null, message);

        return csvExportService.exportTibcoErrors(bos, filter, dateLabel,
                totals[0], totals[1], totals[2], progressFor(jobId, totals[2]));
    }

    private long generateGeneralReport(UUID jobId, Map<String, String> filters, String baseName,
                                       BufferedOutputStream bos) throws Exception {
        String startDate = getFilter(filters, "startDate", getFilter(filters, "date", ""));
        String endDate = getFilter(filters, "endDate", getFilter(filters, "date", startDate));
        String nodeIp = getFilter(filters, "nodeIp", null);
        String status = getFilter(filters, "status", null);
        String shortCode = getFilter(filters, "shortCode", null);
        String msisdn = getFilter(filters, "msisdn", null);
        String errorCode = getFilter(filters, "errorCode", null);
        String productName = getFilter(filters, "productName", null);
        String message = getFilter(filters, "message", null);

        ReportSummaryResponse summary = reportService.getSummary(
                startDate, endDate, nodeIp, status, shortCode, productName, msisdn, errorCode, message);
        ensureWithinLimit(summary.getTotalTransactions());
        checkActive();

        CsvExportService.UssdFilter filter = CsvExportService.UssdFilter.of(
                startDate, endDate, nodeIp, status, shortCode, msisdn, errorCode, productName, message);
        return csvExportService.exportGeneral(bos, baseName, summary, filter,
                progressFor(jobId, summary.getTotalTransactions()));
    }

    private long generateApnReport(UUID jobId, Map<String, String> filters, String baseName,
                                   BufferedOutputStream bos) throws Exception {
        String startDate = getFilter(filters, "startDate", getFilter(filters, "date", ""));
        String endDate = getFilter(filters, "endDate", getFilter(filters, "date", startDate));
        String status = getFilter(filters, "status", null);
        String msisdn = getFilter(filters, "msisdn", null);
        String imsi = getFilter(filters, "imsi", null);

        ApnReportSummaryDto summary = apnService.getSummary(startDate, endDate);
        ensureWithinLimit(summary.getTotalRequests());
        checkActive();

        CsvExportService.ApnFilter filter = CsvExportService.ApnFilter.of(startDate, endDate, status, msisdn, imsi);
        return csvExportService.exportApn(bos, baseName, summary, filter,
                progressFor(jobId, summary.getTotalRequests()));
    }

    private static String baseEntryName(String fileName) {
        String n = fileName == null ? "report" : fileName;
        return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    private static String truncate(String v, int max) {
        return v != null && v.length() > max ? v.substring(0, max) : v;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    /**
     * Poll status of an existing job.
     */
    @Transactional(readOnly = true)
    public ReportJobDto getJob(UUID jobId, String username) {
        ReportJob job = reportJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Report export job not found: " + jobId));
        validateOwnership(job, username);
        return toDto(job);
    }

    /**
     * Find the most recent active or completed job for this user and report type.
     */
    @Transactional(readOnly = true)
    public Optional<ReportJobDto> getLatestJob(String reportType, String username) {
        return reportJobRepository.findFirstByRequestedByAndReportTypeOrderByRequestedAtDesc(
                username, reportType.toUpperCase().trim())
                .filter(j -> j.getExpiresAt() == null || j.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(this::toDto);
    }

    /**
     * List recent jobs for user.
     */
    @Transactional(readOnly = true)
    public List<ReportJobDto> getUserRecentJobs(String username) {
        return reportJobRepository.findTop10ByRequestedByOrderByRequestedAtDesc(username).stream()
                .map(this::toDto)
                .toList();
    }

    private void validateOwnership(ReportJob job, String username) {
        if (username != null && job.getRequestedBy() != null && !job.getRequestedBy().equalsIgnoreCase(username)) {
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean isAdmin = auth != null && auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
            if (!isAdmin) {
                throw new org.springframework.security.access.AccessDeniedException("You are not authorized to access report job " + job.getId());
            }
        }
    }

    /**
     * Load the completed export file for download. The job stays available until its TTL so a failed transfer can be retried.
     */
    public DownloadableFile getDownloadFile(UUID jobId, String username) {
        ReportJob job = reportJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Report job not found: " + jobId));
        validateOwnership(job, username);

        if (!"COMPLETED".equalsIgnoreCase(job.getStatus())) {
            throw new IllegalStateException("Report is not ready for download. Current status: " + job.getStatus());
        }

        if (job.getFilePath() == null) {
            throw new IllegalStateException("Export file path is missing");
        }

        File file = new File(job.getFilePath());
        if (!file.exists() || !file.canRead()) {
            throw new IllegalStateException("Export file is no longer available on server");
        }

        return new DownloadableFile(job.getFileName(), file.length(), new FileSystemResource(file));
    }

    public record DownloadableFile(String fileName, long fileSize, Resource resource) {}

    /**
     * User-initiated cancellation of a pending or in-progress export job.
     */
    public void cancelJob(UUID jobId, String username) {
        reportJobRepository.findById(jobId).ifPresent(job -> {
            validateOwnership(job, username);
            if (reportJobRepository.markFailed(jobId, "Cancelled by user", LocalDateTime.now()) > 0) {
                log.info("Report job id={} cancelled by user {}", jobId, username);
            }
            Thread worker = activeJobThreads.remove(jobId);
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
        });
    }

    /**
     * Watchdog: fails PROCESSING jobs that stopped sending heartbeats or exceeded the hard timeout.
     */
    @Scheduled(fixedDelay = 60000)
    public void timeoutStuckJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<ReportJob> stuck = reportJobRepository.findStuckProcessing(
                now.minusMinutes(staleMinutes), now.minusMinutes(exportTimeoutMinutes));
        for (ReportJob j : stuck) {
            log.warn("Auto-failing stuck report job id={} type={} startedAt={} heartbeat={}",
                    j.getId(), j.getReportType(), j.getStartedAt(), j.getHeartbeatAt());
            reportJobRepository.markFailed(j.getId(),
                    "Export stalled or exceeded " + exportTimeoutMinutes + " minutes", now);
            Thread worker = activeJobThreads.remove(j.getId());
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
        }
    }

    /**
     * After a restart no worker is running: fail interrupted jobs and re-dispatch the ones still queued.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverJobsAfterStartup() {
        try {
            for (ReportJob j : reportJobRepository.findAllByStatusIn(List.of("PROCESSING", "QUEUED"))) {
                if ("PROCESSING".equals(j.getStatus())) {
                    reportJobRepository.markFailed(j.getId(),
                            "Interrupted by a server restart. Please request the export again.", LocalDateTime.now());
                } else {
                    try {
                        self.processJobAsync(j.getId());
                    } catch (RuntimeException e) {
                        reportJobRepository.markFailed(j.getId(), "Could not resume after restart", LocalDateTime.now());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Export job recovery after startup failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<ReportJob> expired = reportJobRepository.findAllByExpiresAtBefore(now);
        for (ReportJob j : expired) {
            if (j.getFilePath() != null) {
                deleteQuietly(Paths.get(j.getFilePath()));
            }
        }
        if (!expired.isEmpty()) {
            log.info("Cleaned up {} expired report export jobs", expired.size());
            reportJobRepository.deleteAll(expired);
        }
        sweepOrphanFiles();
    }

    /** Removes export files that no job references (crashes, deleted rows) and stale .part files. */
    private void sweepOrphanFiles() {
        try {
            Path dir = resolveExportDirectory();
            Set<String> known = new HashSet<>();
            for (ReportJob j : reportJobRepository.findAll()) {
                if (j.getFilePath() != null) known.add(Paths.get(j.getFilePath()).getFileName().toString());
            }
            long cutoff = System.currentTimeMillis() - 2L * 60 * 60 * 1000;
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}_.*"))
                        .filter(p -> !known.contains(p.getFileName().toString()))
                        .filter(p -> p.toFile().lastModified() < cutoff)
                        .forEach(p -> {
                            log.info("Removing orphan export file {}", p);
                            deleteQuietly(p);
                        });
            }
        } catch (Exception e) {
            log.warn("Orphan export file sweep failed: {}", e.getMessage());
        }
    }

    private Path resolveExportDirectory() {
        List<Path> candidates = new ArrayList<>();
        if (exportDirConfig != null && !exportDirConfig.isBlank()) {
            candidates.add(Paths.get(exportDirConfig).toAbsolutePath().normalize());
        }
        candidates.add(Paths.get("/var/log/ussd/exports"));
        candidates.add(Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).resolve("kraken-exports"));
        candidates.add(Paths.get("/tmp/kraken-exports"));

        for (Path dir : candidates) {
            try {
                Files.createDirectories(dir);
                if (Files.isWritable(dir)) {
                    return dir;
                }
            } catch (Exception e) {
                log.warn("Cannot create or write to export directory candidate {}: {}", dir, e.getMessage());
            }
        }

        // Ultimate fallback: current user's temp
        Path fallback = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"));
        log.warn("All export directory candidates failed; falling back to {}", fallback);
        return fallback;
    }

    private String buildFileName(String reportType, Map<String, String> filters) {
        String date = getFilter(filters, "date", getFilter(filters, "startDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))));
        date = date.replace("-", "");
        switch (reportType.toUpperCase().trim()) {
            case "TIBCO_ERRORS":
                return String.format("TIBCO_ERRORS_EXACT_%s.zip", date);
            case "PACKAGES":
                return String.format("package-report-%s.zip", date);
            case "SUBSCRIBERS":
                return String.format("subscriber-report-%s.zip", date);
            case "APN":
                return String.format("apn-report-%s.zip", date);
            case "GENERAL":
            default:
                return String.format("ussd-report-%s.zip", date);
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "report.zip";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getFilter(Map<String, String> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        String val = map.get(key);
        if (val == null || val.isBlank() || "all".equalsIgnoreCase(val.trim())) {
            return defaultVal;
        }
        return val.trim();
    }

    private Map<String, String> parseFilters(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private ReportJobDto toDto(ReportJob job) {
        return ReportJobDto.builder()
                .id(job.getId())
                .reportType(job.getReportType())
                .status(job.getStatus())
                .fileName(job.getFileName())
                .fileSize(job.getFileSize())
                .requestedBy(job.getRequestedBy())
                .requestedAt(job.getRequestedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .downloadedAt(job.getDownloadedAt())
                .errorMessage(job.getErrorMessage())
                .rowsWritten(job.getRowsWritten())
                .totalRows(job.getTotalRows())
                .downloadUrl("/api/v1/reports/jobs/" + job.getId() + "/download")
                .build();
    }
}
