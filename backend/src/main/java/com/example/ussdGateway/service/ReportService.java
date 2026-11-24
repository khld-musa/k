package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.UssdTransactionDto;
import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.example.ussdGateway.entity.UssdTransaction;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import com.example.ussdGateway.util.InputSanitizer;
import com.example.ussdGateway.util.ReportResultCache;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Builds USSD transaction reports (summary stats + filterable transaction lists) from the
 * {@code ussd_transactions} table populated by {@link LogIngestionService}.
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class ReportService {

    private final UssdTransactionRepository ussdTransactionRepository;
    private final EntityManager entityManager;
    private final ReportRollupService rollupService;
    private final ReportResultCache resultCache;

    public ReportService(UssdTransactionRepository ussdTransactionRepository,
                         EntityManager entityManager,
                         ReportRollupService rollupService,
                         ReportResultCache resultCache) {
        this.ussdTransactionRepository = ussdTransactionRepository;
        this.entityManager = entityManager;
        this.rollupService = rollupService;
        this.resultCache = resultCache;
    }

    /**
     * Aggregated stats for a date range with optional dynamic filters.
     * Uses consolidated single-pass aggregation query.
     */
    public long[] getScalarTotals(String startDate, String endDate, String shortCode, String errorCode, String message) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end   = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd   = end.atTime(LocalTime.MAX);

        UssdTransactionRepository.SummaryStats stats = ussdTransactionRepository.consolidatedSummaryStats(
                rangeStart, rangeEnd, null, null, formatSearchPattern(shortCode),
                null, formatSearchPattern(errorCode), null, formatSearchPattern(message));

        long total = (stats != null && stats.getTotal() != null) ? stats.getTotal() : 0L;
        long success = (stats != null && stats.getSuccessCount() != null) ? stats.getSuccessCount() : 0L;
        long failed = Math.max(0L, total - success);
        return new long[]{total, success, failed};
    }

    private static final long TTL_TODAY_MS = 45_000L;
    private static final long TTL_PAST_MS = 5 * 60_000L;

    /** Minimal SQL fragment holder: WHERE clause with positional parameters. */
    private static final class Filter {
        final StringBuilder where = new StringBuilder();
        final List<Object> args = new java.util.ArrayList<>();

        void add(String clause, Object... values) {
            where.append(" AND ").append(clause);
            for (Object v : values) {
                args.add(v);
            }
        }
    }

    /**
     * Builds a WHERE clause containing only the active filters, so PostgreSQL plans for the real
     * query instead of a generic {@code (:x IS NULL OR ...)} plan.
     */
    private Filter buildFilter(LocalDateTime rangeStart, LocalDateTime rangeEnd, String nodeIp, String status,
                               String shortCode, String msisdn, String errorCode, String productName,
                               String message) {
        return buildFilter("triggered_at", rangeStart, rangeEnd, nodeIp, status, shortCode, msisdn, errorCode,
                productName, message);
    }

    private Filter buildFilter(String timeColumn, LocalDateTime rangeStart, LocalDateTime rangeEnd, String nodeIp,
                               String status, String shortCode, String msisdn, String errorCode,
                               String productName, String message) {
        Filter f = new Filter();
        f.where.append(" WHERE ").append(timeColumn).append(" BETWEEN ? AND ?");
        f.args.add(rangeStart);
        f.args.add(rangeEnd);
        if (nodeIp != null) f.add("node_ip = ?", nodeIp);
        if (status != null) f.add("status = ?", status);
        if (shortCode != null) f.add("LOWER(short_code) LIKE ?", shortCode);
        if (msisdn != null) f.add("msisdn LIKE ?", msisdn);
        if (errorCode != null) f.add("LOWER(error_code) LIKE ?", errorCode);
        if (productName != null) f.add("LOWER(product_name) LIKE ?", productName);
        if (message != null) {
            f.add("(LOWER(error_message) LIKE ? OR LOWER(error_code) LIKE ? OR LOWER(tibco_response) LIKE ?"
                    + " OR LOWER(error_reason) LIKE ? OR LOWER(subscriber_reply) LIKE ?)",
                    message, message, message, message, message);
        }
        return f;
    }

    /** Numbers the {@code ?} placeholders as {@code ?1, ?2, ...} and binds the arguments. */
    private jakarta.persistence.Query nativeQuery(String sql, List<Object> args, Class<?> entity) {
        StringBuilder numbered = new StringBuilder(sql.length() + 16);
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                numbered.append('?').append(++n);
            } else {
                numbered.append(c);
            }
        }
        jakarta.persistence.Query q = entity == null
                ? entityManager.createNativeQuery(numbered.toString())
                : entityManager.createNativeQuery(numbered.toString(), entity);
        for (int i = 0; i < args.size(); i++) {
            q.setParameter(i + 1, args.get(i));
        }
        return q;
    }

    private static long ttlFor(LocalDate end) {
        return end.isBefore(LocalDate.now()) ? TTL_PAST_MS : TTL_TODAY_MS;
    }

    private static String filterKey(String kind, LocalDate start, LocalDate end, Object... parts) {
        StringBuilder sb = new StringBuilder(kind).append('|').append(start).append('|').append(end);
        for (Object p : parts) {
            sb.append('|').append(p);
        }
        return sb.toString();
    }

    public ReportSummaryResponse getSummary(String startDate, String endDate, String nodeIp,
                                            String status, String shortCode, String productName,
                                            String msisdn, String errorCode, String message) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end   = InputSanitizer.validateDate(endDate);

        String nNodeIp      = blankToNull(nodeIp);
        String nStatus      = blankToNull(status);
        String nShortCode   = formatSearchPattern(shortCode);
        String nProductName = formatSearchPattern(productName);
        String nMsisdn      = formatSearchPattern(msisdn);
        String nErrorCode   = formatSearchPattern(errorCode);
        String nMessage     = formatSearchPattern(message);

        String key = filterKey("summary", start, end, nNodeIp, nStatus, nShortCode, nProductName,
                nMsisdn, nErrorCode, nMessage);
        return resultCache.get(key, ttlFor(end), () -> computeSummary(startDate, endDate, start, end, nNodeIp,
                nStatus, nShortCode, nProductName, nMsisdn, nErrorCode, nMessage));
    }

    /**
     * One table scan produces totals, per-node, per-day, error-code, short-code and package
     * aggregates through GROUPING SETS (previously ~8-17 sequential scans).
     */
    private ReportSummaryResponse computeSummary(String startDate, String endDate, LocalDate start, LocalDate end,
                                                 String nNodeIp, String nStatus, String nShortCode,
                                                 String nProductName, String nMsisdn, String nErrorCode,
                                                 String nMessage) {
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd   = end.atTime(LocalTime.MAX);
        boolean multiDay = !start.equals(end);
        boolean needNode = nNodeIp == null;
        boolean needErrors = !"SUCCESS".equalsIgnoreCase(nStatus);

        Filter f = buildFilter(rangeStart, rangeEnd, nNodeIp, nStatus, nShortCode, nMsisdn, nErrorCode,
                nProductName, nMessage);

        // Whole-day ranges without row-level filters are answered from the hourly rollup table
        // (thousands of rows) instead of scanning the raw transactions.
        boolean fromRollup = nMsisdn == null && nMessage == null
                && rollupService.covers(rangeStart, rangeEnd);
        if (fromRollup) {
            f = buildFilter("hour_start", rangeStart, rangeEnd, nNodeIp, nStatus, nShortCode, null, nErrorCode,
                    nProductName, null);
        }

        // The CTE is referenced several times, so PostgreSQL materialises it: a single pass feeds
        // every aggregate below.
        String agg = fromRollup
                ? "SUM(total), SUM(success)"
                : "COUNT(*), SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END)";
        String avg = fromRollup
                ? "CAST(SUM(lsum) AS DOUBLE PRECISION) / NULLIF(SUM(lcount), 0)"
                : "AVG(latency_ms)";
        String nullStr = "CAST(NULL AS VARCHAR(255))";
        String nullDay = "CAST(NULL AS DATE)";
        String nullNum = "CAST(NULL AS DOUBLE PRECISION)";
        StringBuilder sql = new StringBuilder("WITH b AS (SELECT ");
        if (fromRollup) {
            sql.append("NULLIF(node_ip, '') AS node_ip, CAST(hour_start AS DATE) AS tx_day,"
                    + " NULLIF(error_code, '') AS error_code, NULLIF(short_code, '') AS short_code,"
                    + " NULLIF(product_name, '') AS product_name, status, total, success,"
                    + " latency_sum AS lsum, latency_count AS lcount FROM ussd_stats_hourly");
        } else {
            sql.append("node_ip, CAST(triggered_at AS DATE) AS tx_day, error_code, short_code, product_name,"
                    + " status, latency_ms FROM ussd_transactions");
        }
        sql.append(f.where).append(") SELECT 0 AS k, ").append(nullStr).append(" AS s1, ").append(nullDay)
                .append(" AS d1, ").append(agg).append(", ").append(avg).append(", ").append(nullStr)
                .append(" AS s2 FROM b");
        if (needNode) {
            sql.append(" UNION ALL SELECT 1, node_ip, ").append(nullDay).append(", ").append(agg).append(", ")
                    .append(nullNum).append(", ").append(nullStr).append(" FROM b GROUP BY node_ip");
        }
        if (multiDay) {
            sql.append(" UNION ALL SELECT 2, ").append(nullStr).append(", tx_day, ").append(agg).append(", ")
                    .append(nullNum).append(", ").append(nullStr).append(" FROM b GROUP BY tx_day");
        }
        if (needErrors) {
            sql.append(" UNION ALL SELECT 3, error_code, ").append(nullDay).append(", ").append(agg).append(", ")
                    .append(nullNum).append(", ").append(nullStr)
                    .append(" FROM b WHERE error_code IS NOT NULL GROUP BY error_code");
        }
        sql.append(" UNION ALL SELECT 4, short_code, ").append(nullDay).append(", ").append(agg).append(", ")
                .append(nullNum).append(", MAX(product_name) FROM b WHERE short_code IS NOT NULL GROUP BY short_code");
        sql.append(" UNION ALL SELECT 5, product_name, ").append(nullDay).append(", ").append(agg).append(", ")
                .append(nullNum).append(", MAX(short_code) FROM b WHERE product_name IS NOT NULL GROUP BY product_name");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery(sql.toString(), f.args, null).getResultList();

        long total = 0L;
        long successRaw = 0L;
        Double avgLatency = null;
        List<ReportSummaryResponse.NodeCount> nodes = new java.util.ArrayList<>();
        List<ReportSummaryResponse.DailyCount> days = new java.util.ArrayList<>();
        List<Object[]> errors = new java.util.ArrayList<>();
        List<ReportSummaryResponse.ShortCodeCount> shortCodes = new java.util.ArrayList<>();
        List<ReportSummaryResponse.PackageCount> packages = new java.util.ArrayList<>();

        for (Object[] r : rows) {
            int k = (int) num(r[0]);
            long count = num(r[3]);
            long success = num(r[4]);
            switch (k) {
                case 0 -> {
                    total = count;
                    successRaw = success;
                    avgLatency = r[5] == null ? null : ((Number) r[5]).doubleValue();
                }
                case 1 -> nodes.add(new ReportSummaryResponse.NodeCount((String) r[1], count));
                case 2 -> days.add(new ReportSummaryResponse.DailyCount(parseLocalDate(r[2]), count, success,
                        count - success));
                case 3 -> errors.add(new Object[]{r[1], count});
                case 4 -> shortCodes.add(new ReportSummaryResponse.ShortCodeCount((String) r[1], (String) r[6],
                        count, success, count - success));
                default -> packages.add(new ReportSummaryResponse.PackageCount((String) r[1], (String) r[6],
                        count, success, count - success));
            }
        }

        long successCount = "FAILED".equalsIgnoreCase(nStatus)
                ? 0L
                : ("SUCCESS".equalsIgnoreCase(nStatus) ? total : successRaw);
        long failedCount = Math.max(0L, total - successCount);
        double successRate = total == 0 ? 0.0 : (successCount * 100.0) / total;

        if (total == 0L) {
            return ReportSummaryResponse.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .nodeIp(nNodeIp)
                    .totalTransactions(0L)
                    .successCount(0L)
                    .failedCount(0L)
                    .successRatePercent(0.0)
                    .averageLatencyMs(null)
                    .dailyBreakdown(Collections.emptyList())
                    .nodeBreakdown(Collections.emptyList())
                    .topErrorCodes(Collections.emptyList())
                    .topShortCodes(Collections.emptyList())
                    .topPackages(Collections.emptyList())
                    .build();
        }

        List<ReportSummaryResponse.DailyCount> dailyBreakdown;
        if (multiDay) {
            days.sort(java.util.Comparator.comparing(ReportSummaryResponse.DailyCount::getDay));
            dailyBreakdown = days;
        } else {
            dailyBreakdown = List.of(new ReportSummaryResponse.DailyCount(start, total, successCount, failedCount));
        }

        List<ReportSummaryResponse.NodeCount> nodeBreakdown = needNode
                ? nodes.stream()
                        .sorted(java.util.Comparator.comparingLong(ReportSummaryResponse.NodeCount::getTotal).reversed())
                        .collect(Collectors.toList())
                : List.of(new ReportSummaryResponse.NodeCount(nNodeIp, total));

        List<ReportSummaryResponse.ErrorCodeCount> topErrorCodes = Collections.emptyList();
        if (failedCount > 0L && needErrors && !errors.isEmpty()) {
            errors.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
            List<Object[]> top = errors.subList(0, Math.min(10, errors.size()));
            Map<String, String> samples = findSampleMessages(rangeStart, rangeEnd,
                    top.stream().map(o -> (String) o[0]).collect(Collectors.toList()));
            topErrorCodes = top.stream()
                    .map(o -> new ReportSummaryResponse.ErrorCodeCount((String) o[0], (Long) o[1],
                            samples.getOrDefault((String) o[0], (String) o[0])))
                    .collect(Collectors.toList());
        }

        List<ReportSummaryResponse.ShortCodeCount> topShortCodes = shortCodes.stream()
                .sorted(java.util.Comparator.comparingLong(ReportSummaryResponse.ShortCodeCount::getTotal).reversed())
                .limit(15).collect(Collectors.toList());
        List<ReportSummaryResponse.PackageCount> topPackages = packages.stream()
                .sorted(java.util.Comparator.comparingLong(ReportSummaryResponse.PackageCount::getTotal).reversed())
                .limit(15).collect(Collectors.toList());

        return ReportSummaryResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .nodeIp(nNodeIp)
                .totalTransactions(total)
                .successCount(successCount)
                .failedCount(failedCount)
                .successRatePercent(Math.round(successRate * 100.0) / 100.0)
                .averageLatencyMs(avgLatency)
                .dailyBreakdown(dailyBreakdown)
                .nodeBreakdown(nodeBreakdown)
                .topErrorCodes(topErrorCodes)
                .topShortCodes(topShortCodes)
                .topPackages(topPackages)
                .build();
    }

    /** Latest descriptive message for each error code, fetched in a single round trip. */
    private Map<String, String> findSampleMessages(LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                                   List<String> codes) {
        StringBuilder sql = new StringBuilder("SELECT c.code, (SELECT COALESCE(NULLIF(error_message, ''),"
                + " NULLIF(tibco_response, ''), NULLIF(error_reason, ''), error_code) FROM ussd_transactions"
                + " WHERE triggered_at BETWEEN ? AND ? AND error_code = c.code"
                + " AND ((error_message IS NOT NULL AND error_message <> '')"
                + " OR (tibco_response IS NOT NULL AND tibco_response <> ''))"
                + " ORDER BY triggered_at DESC, id DESC LIMIT 1) FROM (");
        List<Object> args = new java.util.ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            sql.append(i == 0 ? "" : " UNION ALL ").append("SELECT CAST(? AS VARCHAR(255)) AS code");
            args.add(codes.get(i));
        }
        sql.append(") c");
        // range parameters come first in the SQL text, so they must lead the argument list
        args.add(0, rangeEnd);
        args.add(0, rangeStart);

        Map<String, String> result = new java.util.HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = nativeQuery(sql.toString(), args, null).getResultList();
            for (Object[] r : rows) {
                if (r[1] != null) result.put((String) r[0], (String) r[1]);
            }
        } catch (RuntimeException e) {
            log.warn("Sample error message lookup failed: {}", e.getMessage());
        }
        return result;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    public Page<UssdTransactionDto> getTransactions(String startDate, String endDate, String nodeIp,
                                                      String status, String shortCode, String msisdn,
                                                      String errorCode, String productName, String message,
                                                      int page, int size) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end   = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd   = end.atTime(LocalTime.MAX);

        String nNodeIp      = blankToNull(nodeIp);
        String nStatus      = blankToNull(status);
        String nShortCode   = formatSearchPattern(shortCode);
        String nMsisdn      = formatSearchPattern(msisdn);
        String nErrorCode   = formatSearchPattern(errorCode);
        String nProductName = formatSearchPattern(productName);
        String nMessage     = formatSearchPattern(message);

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        Filter f = buildFilter(rangeStart, rangeEnd, nNodeIp, nStatus, nShortCode, nMsisdn, nErrorCode,
                nProductName, nMessage);

        // Fetch one extra row so a short last page never needs a COUNT query.
        List<Object> listArgs = new java.util.ArrayList<>(f.args);
        String listSql = "SELECT * FROM ussd_transactions" + f.where
                + " ORDER BY triggered_at DESC, id DESC LIMIT " + (pageable.getPageSize() + 1)
                + " OFFSET " + pageable.getOffset();
        @SuppressWarnings("unchecked")
        List<UssdTransaction> rows = nativeQuery(listSql, listArgs, UssdTransaction.class).getResultList();

        boolean hasMore = rows.size() > pageable.getPageSize();
        List<UssdTransactionDto> content = rows.stream()
                .limit(pageable.getPageSize()).map(this::toDto).collect(Collectors.toList());

        long total;
        if (!hasMore) {
            total = pageable.getOffset() + content.size();
        } else {
            String filters = filterKey("n", start, end, nNodeIp, nStatus, nShortCode, nProductName,
                    nMsisdn, nErrorCode, nMessage);
            ReportSummaryResponse cached = resultCache.peek(filterKey("summary", start, end, nNodeIp, nStatus,
                    nShortCode, nProductName, nMsisdn, nErrorCode, nMessage));
            if (cached != null) {
                total = cached.getTotalTransactions();
            } else {
                boolean useRollup = nMsisdn == null && nMessage == null && rollupService.covers(rangeStart, rangeEnd);
                Filter cf = useRollup
                        ? buildFilter("hour_start", rangeStart, rangeEnd, nNodeIp, nStatus, nShortCode, null,
                                nErrorCode, nProductName, null)
                        : f;
                String countSql = useRollup
                        ? "SELECT COALESCE(SUM(total), 0) FROM ussd_stats_hourly" + cf.where
                        : "SELECT COUNT(*) FROM ussd_transactions" + cf.where;
                total = resultCache.get(filters, ttlFor(end),
                        () -> num(nativeQuery(countSql, cf.args, null).getSingleResult()));
            }
        }
        return new org.springframework.data.domain.PageImpl<>(content, pageable, total);
    }

    /**
     * Streams transactions for export using keyset (cursor) pagination to avoid the O(n²)
     * OFFSET degradation of offset-based paging.
     *
     * <p>Instead of {@code OFFSET pageNum * 5000} (which forces PostgreSQL to
     * skip progressively more rows on every page), each subsequent page uses the
     * {@code (triggered_at, id)} cursor from the last row of the previous page.
     * This keeps every page fetch at direct O(log n) index access regardless of how deep into the
     * result set you are.
     *
     * <p>Rows are fed one at a time to the {@code rowConsumer} (the Excel writer) so only
     * one page (5,000 rows) is held in heap at once.
     *
     * @return total number of rows streamed
     */
    private static final int EXPORT_PAGE_SIZE = 5_000;
    private static final int EXPORT_MAX_ROWS  = 20_000_000;

    public int streamTransactionsForExport(String startDate, String endDate, String nodeIp,
                                            String status, String shortCode, String msisdn,
                                            String errorCode, String productName, String message,
                                            Consumer<UssdTransactionDto> rowConsumer) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end   = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd   = end.atTime(LocalTime.MAX);

        String nNodeIp      = blankToNull(nodeIp);
        String nStatus      = blankToNull(status);
        String nShortCode   = formatSearchPattern(shortCode);
        String nMsisdn      = formatSearchPattern(msisdn);
        String nErrorCode   = formatSearchPattern(errorCode);
        String nProductName = formatSearchPattern(productName);
        String nMessage     = formatSearchPattern(message);

        int totalStreamed = 0;
        // Keyset cursor: start at the logical "top" — the rangeEnd timestamp with a
        // sentinel id that ensures the first real row is always included.
        LocalDateTime cursorTime = rangeEnd;
        long cursorId = Long.MAX_VALUE;

        while (totalStreamed < EXPORT_MAX_ROWS) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Export interrupted by cancellation or timeout; halting stream at {} rows", totalStreamed);
                break;
            }

            List<UssdTransaction> page = ussdTransactionRepository.searchKeyset(
                    rangeStart, rangeEnd, nNodeIp, nStatus, nShortCode,
                    nMsisdn, nErrorCode, nProductName, nMessage,
                    cursorTime, cursorId, PageRequest.of(0, EXPORT_PAGE_SIZE));

            if (page.isEmpty()) {
                break;
            }

            for (UssdTransaction tx : page) {
                rowConsumer.accept(toDto(tx));
                totalStreamed++;
            }

            // Advance cursor to the last row of this page
            UssdTransaction last = page.get(page.size() - 1);
            cursorTime = last.getTriggeredAt();
            cursorId   = last.getId();

            // Clear persistence context to release loaded entity graph and prevent memory leak & GC freezes
            entityManager.clear();

            if (page.size() < EXPORT_PAGE_SIZE) {
                break; // last page — no more rows
            }
        }

        if (totalStreamed >= EXPORT_MAX_ROWS) {
            log.warn("Export capped at {} rows (max {})", totalStreamed, EXPORT_MAX_ROWS);
        }
        log.info("Export streamed {} rows via keyset pagination", totalStreamed);
        return totalStreamed;
    }

    /** @deprecated Use {@link #streamTransactionsForExport} for large exports. Kept for test compatibility. */
    @Deprecated
    public List<UssdTransactionDto> getTransactionsForExport(String startDate, String endDate, String nodeIp,
                                                               String status, String shortCode, String msisdn,
                                                               String errorCode, String productName, String message) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end   = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd   = end.atTime(LocalTime.MAX);

        return ussdTransactionRepository.findAllInRange(rangeStart, rangeEnd, blankToNull(nodeIp),
                blankToNull(status), formatSearchPattern(shortCode), formatSearchPattern(msisdn),
                formatSearchPattern(errorCode), formatSearchPattern(productName), formatSearchPattern(message)).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = com.example.ussdGateway.config.CacheConfig.REPORTS_CACHE, key = "'distinct-shortcodes'")
    public List<String> getDistinctShortCodes() {
        return ussdTransactionRepository.findDistinctShortCodes();
    }

    @Cacheable(value = com.example.ussdGateway.config.CacheConfig.REPORTS_CACHE, key = "'distinct-productnames'")
    public List<String> getDistinctProductNames() {
        return ussdTransactionRepository.findDistinctProductNames();
    }

    @Cacheable(value = com.example.ussdGateway.config.CacheConfig.REPORTS_CACHE, key = "'distinct-errorcodes'")
    public List<String> getDistinctErrorCodes() {
        return ussdTransactionRepository.findDistinctErrorCodes();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) ? null : value.trim();
    }

    private String formatSearchPattern(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return "%" + value.trim().toLowerCase() + "%";
    }

    private UssdTransactionDto toDto(UssdTransaction tx) {
        UssdTransactionDto dto = new UssdTransactionDto();
        dto.setId(tx.getId());
        dto.setCallId(tx.getCallId());
        dto.setMsisdn(tx.getMsisdn());
        dto.setTransactionId(tx.getTransactionId());
        dto.setStatus(tx.getStatus());
        dto.setLatencyMs(tx.getLatencyMs());
        dto.setShortCode(tx.getShortCode());
        dto.setProductName(tx.getProductName());
        dto.setErrorCode(tx.getErrorCode());
        dto.setErrorMessage(tx.getErrorMessage());
        dto.setServiceType(tx.getServiceType());
        dto.setLang(tx.getLang());
        dto.setNodeIp(tx.getNodeIp());
        dto.setCellId(tx.getCellId());
        dto.setVlr(tx.getVlr());
        dto.setChannel(tx.getChannel());
        dto.setRemoteUrl(tx.getRemoteUrl());
        dto.setSubscriberReply(tx.getSubscriberReply());
        dto.setTibcoResponse(tx.getTibcoResponse());
        dto.setErrorReason(tx.getErrorReason());
        dto.setResponseTime(tx.getResponseTime());
        dto.setTriggeredAt(tx.getTriggeredAt());
        return dto;
    }

    private LocalDate parseLocalDate(Object dayObj) {
        if (dayObj == null) return LocalDate.now();
        if (dayObj instanceof LocalDate ld) return ld;
        if (dayObj instanceof java.sql.Date sd) return sd.toLocalDate();
        if (dayObj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (dayObj instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        try {
            return LocalDate.parse(dayObj.toString().substring(0, 10));
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
