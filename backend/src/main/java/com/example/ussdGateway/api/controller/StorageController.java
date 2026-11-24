package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.service.DataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only endpoints for database storage management: view storage stats,
 * manually purge old data, run VACUUM, and REINDEX.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/storage")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StorageController {

    private final DataCleanupService dataCleanupService;

    /**
     * Returns detailed storage statistics: DB size, table sizes, index sizes,
     * dead tuples, row counts, last vacuum times, etc.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStorageStats() {
        return ResponseEntity.ok(dataCleanupService.getStorageStats());
    }

    /**
     * Manually purge ussd_transactions older than the given number of days.
     * Use retentionDays=30 to enforce 30-day retention, or retentionDays=7 for aggressive cleanup.
     */
    @PostMapping("/purge")
    public ResponseEntity<Map<String, Object>> purgeData(
            @RequestParam(defaultValue = "30") int retentionDays) {
        log.info("Admin triggered manual purge: retentionDays={}", retentionDays);
        long deleted = dataCleanupService.purgeOlderThan(retentionDays);
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "rowsDeleted", deleted,
                "retentionDays", retentionDays
        ));
    }

    /**
     * Run VACUUM ANALYZE to reclaim dead tuple space and update planner statistics.
     * This is safe to run during normal operations — it does NOT lock the table exclusively.
     */
    @PostMapping("/vacuum")
    public ResponseEntity<Map<String, String>> vacuum() {
        log.info("Admin triggered VACUUM ANALYZE");
        dataCleanupService.vacuumAnalyze();
        return ResponseEntity.ok(Map.of("status", "completed", "type", "VACUUM ANALYZE"));
    }

    /**
     * Run VACUUM FULL ANALYZE to completely rewrite the table file to minimum size.
     * WARNING: This acquires an exclusive table lock and blocks all reads/writes until done.
     * Only use during maintenance windows.
     */
    @PostMapping("/vacuum-full")
    public ResponseEntity<Map<String, String>> vacuumFull() {
        log.info("Admin triggered VACUUM FULL ANALYZE");
        dataCleanupService.vacuumFull();
        return ResponseEntity.ok(Map.of("status", "completed", "type", "VACUUM FULL ANALYZE"));
    }

    /**
     * Run REINDEX to rebuild all indexes on ussd_transactions, eliminating index bloat.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, String>> reindex() {
        log.info("Admin triggered REINDEX");
        dataCleanupService.reindexTable();
        return ResponseEntity.ok(Map.of("status", "completed"));
    }

    /**
     * Full maintenance: purge + vacuum + reindex in one call.
     */
    @PostMapping("/full-maintenance")
    public ResponseEntity<Map<String, Object>> fullMaintenance(
            @RequestParam(defaultValue = "30") int retentionDays) {
        log.info("Admin triggered full storage maintenance: retentionDays={}", retentionDays);

        long deleted = dataCleanupService.purgeOlderThan(retentionDays);
        dataCleanupService.vacuumFull();
        dataCleanupService.reindexTable();

        Map<String, Object> stats = dataCleanupService.getStorageStats();
        stats.put("rowsDeleted", deleted);
        stats.put("maintenanceType", "PURGE + VACUUM FULL + REINDEX");
        return ResponseEntity.ok(stats);
    }
}
