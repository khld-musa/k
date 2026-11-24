package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.UssdTransactionDto;
import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.example.ussdGateway.service.FetchResult;
import com.example.ussdGateway.service.LogFetchService;
import com.example.ussdGateway.service.LogIngestionService;
import com.example.ussdGateway.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST controller for USSD transaction reports built from the {@code ussd_transactions} table.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
public class ReportController {

    private final ReportService reportService;
    private final LogIngestionService logIngestionService;
    private final LogFetchService logFetchService;
    private final com.example.ussdGateway.service.ReportRollupService rollupService;

    /** Recomputes the pre-aggregated hourly statistics for a date range (e.g. after a manual re-ingestion). */
    @PostMapping("/rollup/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> rebuildRollup(@RequestParam String startDate,
                                                             @RequestParam String endDate) {
        java.time.LocalDate from = com.example.ussdGateway.util.InputSanitizer.validateDate(startDate);
        java.time.LocalDate to = com.example.ussdGateway.util.InputSanitizer.validateDate(endDate);
        rollupService.rebuildDays(from, to);
        return ResponseEntity.ok(Map.of("status", "ok", "startDate", startDate, "endDate", endDate));
    }

    /** Aggregated stats (totals, success rate, avg latency, breakdowns) for a date range with optional dynamic filters. */
    @GetMapping("/summary")
    public ResponseEntity<ReportSummaryResponse> getSummary(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String nodeIp,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String shortCode,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String msisdn,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String message) {
        return ResponseEntity.ok(reportService.getSummary(startDate, endDate, nodeIp, status, shortCode, productName, msisdn, errorCode, message));
    }

    /** Paginated, filterable list of raw USSD transactions for a date range. */
    @GetMapping("/transactions")
    public ResponseEntity<Page<UssdTransactionDto>> getTransactions(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String nodeIp,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String shortCode,
            @RequestParam(required = false) String msisdn,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String message,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(reportService.getTransactions(startDate, endDate, nodeIp, status, shortCode, msisdn, errorCode, productName, message, page, size));
    }

    // Exports are produced by the background job pipeline: see ReportJobController (/api/v1/reports/jobs).

    /** Distinct short codes for the filter dropdown. */
    @GetMapping("/filters/short-codes")
    public ResponseEntity<List<String>> getShortCodes() {
        return ResponseEntity.ok(reportService.getDistinctShortCodes());
    }

    /** Distinct product names for the filter dropdown. */
    @GetMapping("/filters/products")
    public ResponseEntity<List<String>> getProductNames() {
        return ResponseEntity.ok(reportService.getDistinctProductNames());
    }

    /** Distinct error codes for the filter dropdown. */
    @GetMapping("/filters/error-codes")
    public ResponseEntity<List<String>> getErrorCodes() {
        return ResponseEntity.ok(reportService.getDistinctErrorCodes());
    }

    /** Manually triggers USSD log ingestion (admin only) - backfills missing data across all logs by default. */
    @PostMapping("/ingest/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> runIngestionNow(@RequestParam(defaultValue = "true") boolean reingestAll) {
        if (reingestAll) {
            logIngestionService.reingestAll();
            return ResponseEntity.ok(Map.of("message", "USSD log full ingestion and missing data backfill completed"));
        } else {
            logIngestionService.ingestAll();
            return ResponseEntity.ok(Map.of("message", "USSD log ingestion completed"));
        }
    }

    /**
     * Manually triggers the daily USSD log fetch job (admin only) - pulls each server's new log
     * files in from its remote FTP node, deletes them remotely, then ingests them. Useful for
     * QA/backfill or kicking off an out-of-cycle pull. Returns a per-server result (fetched,
     * no new files, skipped - with the reason, or error) rather than a generic success message,
     * since a misconfigured server is a silent no-op otherwise.
     */
    @PostMapping("/fetch/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FetchResult>> runFetchNow() {
        List<FetchResult> results = logFetchService.fetchAll();
        logIngestionService.ingestAll(true);
        return ResponseEntity.ok(results);
    }
}
