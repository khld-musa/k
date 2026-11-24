package com.example.ussdGateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Maintains {@code ussd_stats_hourly}: per-hour aggregates of {@code ussd_transactions} grouped by
 * node, status, short code, product and error code. Report summaries read this small table instead
 * of scanning millions of raw rows. Hours are recomputed from the raw table (never incrementally),
 * so the process is idempotent and self-healing.
 */
@Service
@Slf4j
public class ReportRollupService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int backfillDays;
    private final ReentrantLock lock = new ReentrantLock();
    private final com.example.ussdGateway.util.ReportResultCache resultCache;
    /** Hours whose raw data changed (ingestion) and must be recomputed. */
    private final Set<LocalDateTime> dirtyHours = ConcurrentHashMap.newKeySet();

    public ReportRollupService(DataSource dataSource, PlatformTransactionManager txManager,
                               com.example.ussdGateway.util.ReportResultCache resultCache,
                               @Value("${app.reports.rollup.enabled:true}") boolean enabled,
                               @Value("${app.reports.rollup.backfill-days:${ussd.retention.days:30}}") int backfillDays) {
        this.resultCache = resultCache;
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.backfillDays = Math.max(1, backfillDays);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Called by ingestion: the given moments received new/changed rows, so their hours are stale. */
    public void markDirty(Collection<LocalDateTime> moments) {
        if (!enabled) return;
        boolean added = false;
        for (LocalDateTime m : moments) {
            if (m != null) {
                dirtyHours.add(m.truncatedTo(ChronoUnit.HOURS));
                added = true;
            }
        }
        if (added) {
            resultCache.clear();
        }
    }

    /** True when every hour of [rangeStart, rangeEnd] is present in the rollup (only for past completed days). */
    public boolean covers(LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        if (!enabled) return false;
        // Never answer today from rollup alone, because today's transactions are actively arriving
        if (rangeEnd.toLocalDate().equals(LocalDate.now())) {
            return false;
        }
        try {
            LocalDateTime from = rangeStart.truncatedTo(ChronoUnit.HOURS);
            LocalDateTime lastCompletedHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(1);
            LocalDateTime to = rangeEnd.truncatedTo(ChronoUnit.HOURS);
            if (to.isAfter(lastCompletedHour)) to = lastCompletedHour;
            if (to.isBefore(from)) return false;
            long expected = ChronoUnit.HOURS.between(from, to) + 1;
            Long have = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ussd_stats_hourly_coverage WHERE hour_start BETWEEN ? AND ?",
                    Long.class, Timestamp.valueOf(from), Timestamp.valueOf(to));
            return have != null && have == expected;
        } catch (RuntimeException e) {
            log.warn("Rollup coverage check failed, falling back to raw data: {}", e.getMessage());
            return false;
        }
    }

    /** Recomputes the hours in [from, to) (both truncated to the hour). */
    public void recompute(LocalDateTime from, LocalDateTime to) {
        LocalDateTime f = from.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime t = to.truncatedTo(ChronoUnit.HOURS);
        if (!to.equals(t)) t = t.plusHours(1);
        if (!t.isAfter(f)) return;

        // Prevent wiping historical rollup stats if raw transactions for that period have already been purged
        LocalDateTime oldestRawData = LocalDateTime.now().minusDays(backfillDays + 1).truncatedTo(ChronoUnit.DAYS);
        if (t.isBefore(oldestRawData)) {
            log.warn("Skipping rollup recompute for [{}, {}): raw transactions for this period were already purged by retention policy", f, t);
            return;
        }

        LocalDateTime lastCompletedHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(1);
        LocalDateTime coverageEnd = t.minusHours(1).isAfter(lastCompletedHour) ? lastCompletedHour : t.minusHours(1);
        Timestamp tf = Timestamp.valueOf(f);
        Timestamp tt = Timestamp.valueOf(t);
        Timestamp tc = Timestamp.valueOf(coverageEnd);

        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ussd_stats_hourly WHERE hour_start >= ? AND hour_start < ?", tf, tt);
            jdbc.update("INSERT INTO ussd_stats_hourly (hour_start, node_ip, status, short_code, product_name,"
                    + " error_code, total, success, latency_sum, latency_count) "
                    + "SELECT date_trunc('hour', triggered_at), COALESCE(node_ip, ''), COALESCE(status, ''),"
                    + " COALESCE(short_code, ''), COALESCE(product_name, ''), COALESCE(error_code, ''),"
                    + " COUNT(*), COUNT(*) FILTER (WHERE status = 'SUCCESS'),"
                    + " COALESCE(SUM(latency_ms), 0), COUNT(latency_ms) "
                    + "FROM ussd_transactions WHERE triggered_at >= ? AND triggered_at < ? "
                    + "GROUP BY 1, 2, 3, 4, 5, 6", tf, tt);
            jdbc.update("INSERT INTO ussd_stats_hourly_coverage (hour_start, computed_at) "
                    + "SELECT g, CURRENT_TIMESTAMP FROM generate_series(CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP),"
                    + " INTERVAL '1 hour') g ON CONFLICT (hour_start) DO UPDATE SET computed_at = EXCLUDED.computed_at",
                    tf, tc);
        });
    }

    /** Rebuilds the given days, one day per transaction. Used by the admin endpoint. */
    public void rebuildDays(LocalDate from, LocalDate to) {
        lock.lock();
        try {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                recompute(d.atStartOfDay(), d.plusDays(1).atStartOfDay());
            }
            resultCache.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Keeps the current and previous hours fresh (about one raw-range scan of ~2 hours per minute). */
    @Scheduled(fixedDelayString = "${app.reports.rollup.refresh-ms:60000}", initialDelayString = "${app.reports.rollup.refresh-ms:60000}")
    public void refreshRecent() {
        if (!enabled || !lock.tryLock()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            Set<LocalDateTime> batch = new TreeSet<>(dirtyHours);
            dirtyHours.removeAll(batch);
            try {
                for (LocalDateTime h : batch) {
                    recompute(h, h.plusHours(1));
                }
            } catch (RuntimeException e) {
                dirtyHours.addAll(batch);
                throw e;
            }
            recompute(now.minusHours(2), now.plusHours(1));
            if (!batch.isEmpty()) {
                resultCache.clear();
            }
        } catch (Exception e) {
            log.error("Rollup refresh failed: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Late-arriving log files can add rows to recent days, so re-verify the last two days nightly. */
    @Scheduled(cron = "${app.reports.rollup.nightly-cron:0 30 3 * * *}")
    public void nightlyReconcile() {
        if (!enabled) return;
        lock.lock();
        try {
            LocalDate today = LocalDate.now();
            for (LocalDate d = today.minusDays(2); !d.isAfter(today); d = d.plusDays(1)) {
                recompute(d.atStartOfDay(), d.plusDays(1).atStartOfDay());
            }
            jdbc.update("DELETE FROM ussd_stats_hourly WHERE hour_start < ?",
                    Timestamp.valueOf(today.minusDays(400).atStartOfDay()));
            jdbc.update("DELETE FROM ussd_stats_hourly_coverage WHERE hour_start < ?",
                    Timestamp.valueOf(today.minusDays(400).atStartOfDay()));
            resultCache.clear();
        } catch (Exception e) {
            log.error("Nightly rollup reconcile failed: {}", e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Recomputes every day in the retention window on startup, newest first (self-heals stale days). */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!enabled) return;
        Thread t = new Thread(this::backfill, "rollup-backfill");
        t.setDaemon(true);
        t.start();
    }

    void backfill() {
        try {
            LocalDate today = LocalDate.now();
            for (int i = 0; i <= backfillDays; i++) {
                LocalDate day = today.minusDays(i);
                LocalDateTime from = day.atStartOfDay();
                LocalDateTime to = day.plusDays(1).atStartOfDay();

                // Skip days older than 2 days that are already fully covered
                if (i > 2 && covers(from, to)) {
                    continue;
                }

                long begin = System.currentTimeMillis();
                // Lock per day so the 60 s refresh of recent hours is never blocked for the whole backfill.
                lock.lock();
                try {
                    recompute(from, to);
                    resultCache.clear();
                } finally {
                    lock.unlock();
                }
                log.info("Rollup backfill for {} done in {} ms", day, System.currentTimeMillis() - begin);
            }
        } catch (Exception e) {
            log.error("Rollup backfill failed: {}", e.getMessage(), e);
        }
    }
}
