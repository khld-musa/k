package com.example.ussdGateway.service;

import com.example.ussdGateway.repository.AlarmHistoryRepository;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataCleanupService {

    private final AlarmHistoryRepository alarmHistoryRepository;
    private final UssdTransactionRepository ussdTransactionRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final int DELETE_BATCH_SIZE = 10_000;

    @Value("${ussd.retention.days:30}")
    private int ussdRetentionDays;

    @Value("${ussd.retention.batch-size:10000}")
    private int deleteBatchSize;

    /**
     * Data Retention: Runs at 02:00 AM daily.
     * Deletes alarm history records older than 90 days and USSD transactions older than configured retention days.
     * Then runs VACUUM ANALYZE to reclaim disk space from deleted rows.
     */
    @Scheduled(cron = "${ussd.retention.cron:0 0 2 * * *}")
    public void purgeOldData() {
        purgeOldAlarmHistory();
        long txDeleted = purgeOldUssdTransactions();
        purgeOldRollupData();
        purgeOldReportJobs();

        // VACUUM ANALYZE to reclaim disk space after bulk deletes.
        // Without this, PostgreSQL keeps dead tuples on disk (MVCC), and the table file never shrinks.
        if (txDeleted > 0) {
            vacuumAnalyze();
        }
    }

    /**
     * Deletes ussd_transactions older than configured retention period in controlled batches to avoid table locking.
     * @return total number of rows deleted
     */
    public long purgeOldUssdTransactions() {
        if (ussdRetentionDays <= 0) {
            log.info("USSD transaction retention purge is disabled (retention days <= 0)");
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(ussdRetentionDays);
        log.info("Starting retention purge for ussd_transactions older than {} days (cutoff: {})...",
                ussdRetentionDays, cutoff);
        long totalDeleted = 0;
        int batch;
        int limit = deleteBatchSize > 0 ? deleteBatchSize : DELETE_BATCH_SIZE;
        try {
            do {
                batch = deleteUssdTransactionBatch(cutoff, limit);
                totalDeleted += batch;
                if (batch > 0) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } while (batch >= limit);
            log.info("Finished ussd_transactions retention purge: {} rows deleted.", totalDeleted);
        } catch (Exception e) {
            log.error("Failed to purge old ussd_transactions: {}", e.getMessage(), e);
        }
        return totalDeleted;
    }

    /**
     * Manual purge: deletes data older than the specified number of days.
     * @return total rows deleted
     */
    public long purgeOlderThan(int days) {
        if (days <= 0) {
            log.warn("Cannot purge with days <= 0");
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        log.info("Manual purge: deleting ussd_transactions older than {} days (cutoff: {})...", days, cutoff);
        long totalDeleted = 0;
        int batch;
        int limit = deleteBatchSize > 0 ? deleteBatchSize : DELETE_BATCH_SIZE;
        do {
            batch = deleteUssdTransactionBatch(cutoff, limit);
            totalDeleted += batch;
            if (batch > 0) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } while (batch >= limit);
        log.info("Manual purge complete: {} ussd_transactions deleted (cutoff: {}).", totalDeleted, cutoff);
        return totalDeleted;
    }

    @Transactional
    public int deleteUssdTransactionBatch(LocalDateTime cutoff, int batchSize) {
        return ussdTransactionRepository.deleteBatchByTriggeredAtBefore(cutoff, batchSize);
    }

    /**
     * Purge stale rollup data that is beyond the retention window.
     */
    private void purgeOldRollupData() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(ussdRetentionDays + 1);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM ussd_stats_hourly WHERE hour_start < ?", cutoff);
            int coverageDeleted = jdbcTemplate.update(
                    "DELETE FROM ussd_stats_hourly_coverage WHERE hour_start < ?", cutoff);
            if (deleted > 0 || coverageDeleted > 0) {
                log.info("Purged {} rollup rows and {} coverage rows older than {} days.",
                        deleted, coverageDeleted, ussdRetentionDays);
            }
        } catch (Exception e) {
            log.warn("Failed to purge old rollup data: {}", e.getMessage());
        }
    }

    /**
     * Purge completed/expired report export jobs older than 7 days to free export file storage.
     */
    private void purgeOldReportJobs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM report_jobs WHERE created_at < ? AND status IN ('COMPLETED', 'FAILED', 'EXPIRED')", cutoff);
            if (deleted > 0) {
                log.info("Purged {} old report jobs.", deleted);
            }
        } catch (Exception e) {
            log.warn("Failed to purge old report jobs: {}", e.getMessage());
        }
    }

    /**
     * Runs VACUUM ANALYZE on ussd_transactions.
     * This reclaims disk space from deleted rows (dead tuples) and updates query planner statistics.
     * Must run outside of a transaction (VACUUM cannot run inside a transaction block).
     */
    public void vacuumAnalyze() {
        try {
            log.info("Running VACUUM ANALYZE on ussd_transactions to reclaim disk space...");
            long start = System.currentTimeMillis();
            jdbcTemplate.execute("VACUUM ANALYZE ussd_transactions");
            long elapsed = System.currentTimeMillis() - start;
            log.info("VACUUM ANALYZE completed in {}s.", elapsed / 1000.0);
        } catch (Exception e) {
            log.error("VACUUM ANALYZE failed: {}", e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute("VACUUM ANALYZE ussd_stats_hourly");
            log.info("VACUUM ANALYZE ussd_stats_hourly completed.");
        } catch (Exception e) {
            log.warn("VACUUM ANALYZE ussd_stats_hourly failed: {}", e.getMessage());
        }
    }

    /**
     * Runs VACUUM FULL on ussd_transactions — rewrites the entire table file to minimum size.
     * This is a HEAVY operation that acquires an exclusive lock. Only use during maintenance windows.
     * Unlike regular VACUUM, VACUUM FULL actually returns disk space to the OS.
     */
    public void vacuumFull() {
        try {
            log.info("Running VACUUM FULL ANALYZE on ussd_transactions (this will lock the table)...");
            long start = System.currentTimeMillis();
            jdbcTemplate.execute("VACUUM FULL ANALYZE ussd_transactions");
            long elapsed = System.currentTimeMillis() - start;
            log.info("VACUUM FULL ANALYZE completed in {}s.", elapsed / 1000.0);
        } catch (Exception e) {
            log.error("VACUUM FULL ANALYZE failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Rebuilds all indexes on ussd_transactions to eliminate index bloat.
     */
    public void reindexTable() {
        try {
            log.info("Running REINDEX on ussd_transactions...");
            long start = System.currentTimeMillis();
            jdbcTemplate.execute("REINDEX TABLE ussd_transactions");
            long elapsed = System.currentTimeMillis() - start;
            log.info("REINDEX completed in {}s.", elapsed / 1000.0);
        } catch (Exception e) {
            log.error("REINDEX failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns detailed storage statistics for the database.
     */
    public Map<String, Object> getStorageStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        try {
            // Database total size
            String dbSize = jdbcTemplate.queryForObject(
                    "SELECT pg_size_pretty(pg_database_size(current_database()))", String.class);
            stats.put("databaseSize", dbSize);
            stats.put("databaseSizeBytes", jdbcTemplate.queryForObject(
                    "SELECT pg_database_size(current_database())", Long.class));

            // Table sizes
            String tableSize = jdbcTemplate.queryForObject(
                    "SELECT pg_size_pretty(pg_total_relation_size('ussd_transactions'))", String.class);
            stats.put("ussdTransactionsTotal", tableSize);
            stats.put("ussdTransactionsTotalBytes", jdbcTemplate.queryForObject(
                    "SELECT pg_total_relation_size('ussd_transactions')", Long.class));

            String tableOnly = jdbcTemplate.queryForObject(
                    "SELECT pg_size_pretty(pg_relation_size('ussd_transactions'))", String.class);
            stats.put("ussdTransactionsTableOnly", tableOnly);

            String indexSize = jdbcTemplate.queryForObject(
                    "SELECT pg_size_pretty(pg_indexes_size('ussd_transactions'))", String.class);
            stats.put("ussdTransactionsIndexes", indexSize);
            stats.put("ussdTransactionsIndexesBytes", jdbcTemplate.queryForObject(
                    "SELECT pg_indexes_size('ussd_transactions')", Long.class));

            // Row count estimate (fast, from pg_stat)
            Long estimatedRows = jdbcTemplate.queryForObject(
                    "SELECT reltuples::bigint FROM pg_class WHERE relname = 'ussd_transactions'", Long.class);
            stats.put("estimatedRowCount", estimatedRows);

            // Dead tuples (space that VACUUM can reclaim)
            Long deadTuples = jdbcTemplate.queryForObject(
                    "SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname = 'ussd_transactions'", Long.class);
            stats.put("deadTuples", deadTuples);

            Long liveTuples = jdbcTemplate.queryForObject(
                    "SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'ussd_transactions'", Long.class);
            stats.put("liveTuples", liveTuples);

            // Last vacuum times
            Map<String, Object> vacuumInfo = jdbcTemplate.queryForMap(
                    "SELECT last_vacuum, last_autovacuum, last_analyze, last_autoanalyze " +
                    "FROM pg_stat_user_tables WHERE relname = 'ussd_transactions'");
            stats.put("lastVacuum", vacuumInfo.get("last_vacuum"));
            stats.put("lastAutovacuum", vacuumInfo.get("last_autovacuum"));
            stats.put("lastAnalyze", vacuumInfo.get("last_analyze"));

            // Index details
            List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                    "SELECT indexname, pg_size_pretty(pg_relation_size(quote_ident(indexname)::text)) as size, " +
                    "pg_relation_size(quote_ident(indexname)::text) as size_bytes " +
                    "FROM pg_indexes WHERE tablename = 'ussd_transactions' ORDER BY pg_relation_size(quote_ident(indexname)::text) DESC");
            stats.put("indexes", indexes);

            // Other big tables
            List<Map<String, Object>> bigTables = jdbcTemplate.queryForList(
                    "SELECT relname as table_name, " +
                    "pg_size_pretty(pg_total_relation_size(quote_ident(relname)::regclass)) as total_size, " +
                    "pg_total_relation_size(quote_ident(relname)::regclass) as total_bytes " +
                    "FROM pg_stat_user_tables ORDER BY pg_total_relation_size(quote_ident(relname)::regclass) DESC LIMIT 10");
            stats.put("topTables", bigTables);

            // Retention config
            stats.put("retentionDays", ussdRetentionDays);

            // Date range of data
            Map<String, Object> dateRange = jdbcTemplate.queryForMap(
                    "SELECT MIN(triggered_at) as earliest, MAX(triggered_at) as latest FROM ussd_transactions");
            stats.put("dataEarliest", dateRange.get("earliest"));
            stats.put("dataLatest", dateRange.get("latest"));

        } catch (Exception e) {
            log.error("Failed to collect storage stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * Deletes alarm_history records older than 90 days in batches to avoid table locks.
     */
    private void purgeOldAlarmHistory() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        try {
            long count = alarmHistoryRepository.countByTriggeredAtBefore(cutoff);
            if (count == 0) {
                log.info("No old alarm history records to purge.");
                return;
            }
            log.info("Purging {} alarm history records older than {} days...", count, 90);
            long totalDeleted = 0;
            int batch;
            do {
                batch = deleteAlarmHistoryBatch(cutoff);
                totalDeleted += batch;
                if (batch == DELETE_BATCH_SIZE) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } while (batch == DELETE_BATCH_SIZE);
            log.info("Purged {} old alarm history records.", totalDeleted);
        } catch (Exception e) {
            log.error("Failed to cleanup old alarm history: {}", e.getMessage());
        }
    }

    @Transactional
    protected int deleteAlarmHistoryBatch(LocalDateTime cutoff) {
        return alarmHistoryRepository.deleteBatchByTriggeredAtBefore(cutoff, DELETE_BATCH_SIZE);
    }
}

