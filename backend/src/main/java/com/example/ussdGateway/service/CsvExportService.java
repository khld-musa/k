package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.ApnRequestDto;
import com.example.ussdGateway.api.dto.UssdTransactionDto;
import com.example.ussdGateway.api.dto.response.ApnReportSummaryDto;
import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import com.example.ussdGateway.util.CsvZipWriter;
import com.example.ussdGateway.util.InputSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams report exports as CSV files packed into a ZIP archive.
 *
 * <p>Rows are read with keyset pagination through plain JDBC (only the needed columns, no entity
 * hydration, no long-lived transaction: every page is its own short statement) and written
 * straight to the output, so memory and disk usage stay flat regardless of row count.
 */
@Slf4j
@Service
public class CsvExportService {

    private static final int PAGE_SIZE = 10_000;
    private static final int MAX_DESCRIPTIONS = 20_000;
    private static final int MAX_DESCRIPTION_KEY_LENGTH = 1_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SQL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Pattern SHORTCODE_REGEX = Pattern.compile("(\\*\\d+[0-9*#]*#)");

    private static final String[] GENERAL_HEADERS = {
            "Time", "MSISDN", "Short Code", "Product", "Status", "Error Code", "Error Message", "Reason",
            "TIBCO Response", "Latency (ms)", "Node IP", "Channel", "Service Type", "Lang", "VLR", "Cell ID",
            "Transaction ID", "Call ID"};
    private static final String[] APN_HEADERS = {
            "Timestamp", "MSISDN", "IMSI", "Status", "Response Message", "Latency (ms)", "HTTP Status", "Call ID"};
    private static final String[] TIBCO_HEADERS = {
            "#", "Error time", "MSISDN", "Shortcode", "TIBCO URL", "Request time", "Duration (s)",
            "Error (TIBCO response)", "Reason", "Reply to subscriber"};

    private final JdbcTemplate jdbc;
    private final UssdTransactionRepository ussdRepository;

    @Value("${app.export.max-rows:20000000}")
    private long maxRows;

    public CsvExportService(DataSource dataSource, UssdTransactionRepository ussdRepository) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(900);
        this.ussdRepository = ussdRepository;
    }

    public long getMaxRows() {
        return maxRows;
    }

    /** Called after every page; may throw to abort (cancellation). */
    @FunctionalInterface
    public interface Progress {
        void update(long rowsWritten) throws InterruptedException;
    }

    @FunctionalInterface
    private interface RowSink<T> {
        void accept(T row) throws IOException;
    }

    public record UssdFilter(LocalDateTime start, LocalDateTime end, String nodeIp, String status,
                             String shortCodeLike, String msisdnLike, String errorCodeLike,
                             String productLike, String messageLike) {

        public static UssdFilter of(String startDate, String endDate, String nodeIp, String status,
                                    String shortCode, String msisdn, String errorCode,
                                    String productName, String message) {
            LocalDate s = InputSanitizer.validateDate(startDate);
            LocalDate e = InputSanitizer.validateDate(endDate);
            return new UssdFilter(s.atStartOfDay(), e.atTime(LocalTime.MAX),
                    plain(nodeIp), plain(status), like(shortCode), like(msisdn), like(errorCode),
                    like(productName), like(message));
        }
    }

    public record ApnFilter(LocalDateTime start, LocalDateTime end, String status, String msisdnLike, String imsiLike) {
        public static ApnFilter of(String startDate, String endDate, String status, String msisdn, String imsi) {
            LocalDate s = InputSanitizer.validateDate(startDate);
            LocalDate e = InputSanitizer.validateDate(endDate);
            return new ApnFilter(s.atStartOfDay(), e.atTime(LocalTime.MAX),
                    plain(status), likeKeepCase(msisdn), likeKeepCase(imsi));
        }
    }

    private static String plain(String v) {
        return (v == null || v.isBlank() || "all".equalsIgnoreCase(v.trim())) ? null : v.trim();
    }

    private static String like(String v) {
        String p = plain(v);
        return p == null ? null : "%" + p.toLowerCase() + "%";
    }

    private static String likeKeepCase(String v) {
        String p = plain(v);
        return p == null ? null : "%" + p + "%";
    }

    // ───────────────────────────── General / subscribers / packages ─────────────────────────────

    public long exportGeneral(OutputStream out, String baseName, ReportSummaryResponse summary,
                              UssdFilter filter, Progress progress) throws Exception {
        try (CsvZipWriter csv = new CsvZipWriter(out)) {
            csv.startEntry("summary.csv");
            writeGeneralSummary(csv, summary);

            csv.startEntry(baseName + ".csv");
            csv.writeRow(GENERAL_HEADERS);
            long rows = streamUssd(filter, false, progress, tx -> csv.writeRow(
                    fmt(tx.getTriggeredAt()), s(tx.getMsisdn()), s(tx.getShortCode()), s(tx.getProductName()),
                    statusLabel(tx.getStatus()), s(tx.getErrorCode()), s(tx.getErrorMessage()),
                    s(tx.getErrorReason()), s(tx.getTibcoResponse()), num(tx.getLatencyMs()),
                    s(tx.getNodeIp()), s(tx.getChannel()), s(tx.getServiceType()), s(tx.getLang()),
                    s(tx.getVlr()), s(tx.getCellId()), s(tx.getTransactionId()), s(tx.getCallId())));
            csv.finish();
            return rows;
        }
    }

    private void writeGeneralSummary(CsvZipWriter csv, ReportSummaryResponse sm) throws IOException {
        csv.writeRow("USSD Transaction Report");
        csv.writeRow("Period", sm.getStartDate() + " to " + sm.getEndDate());
        csv.writeRow("Node", sm.getNodeIp() != null ? sm.getNodeIp() : "All nodes");
        csv.writeRow("Generated", LocalDateTime.now().format(TS));
        csv.blankRow();
        csv.writeRow("Totals");
        csv.writeRow("Total transactions", String.valueOf(sm.getTotalTransactions()));
        csv.writeRow("Successful", String.valueOf(sm.getSuccessCount()));
        csv.writeRow("Failed", String.valueOf(sm.getFailedCount()));
        csv.writeRow("Success rate (%)", String.format("%.2f", sm.getSuccessRatePercent()));
        if (sm.getAverageLatencyMs() != null) {
            csv.writeRow("Average latency (ms)", String.format("%.0f", sm.getAverageLatencyMs()));
        }
        if (sm.getDailyBreakdown() != null && !sm.getDailyBreakdown().isEmpty()) {
            csv.blankRow();
            csv.writeRow("By day");
            csv.writeRow("Date", "Total", "Successful", "Failed", "Success rate (%)");
            for (ReportSummaryResponse.DailyCount d : sm.getDailyBreakdown()) {
                csv.writeRow(String.valueOf(d.getDay()), String.valueOf(d.getTotal()), String.valueOf(d.getSuccess()),
                        String.valueOf(d.getFailed()), pct(d.getSuccess(), d.getTotal()));
            }
        }
        if (sm.getNodeBreakdown() != null && !sm.getNodeBreakdown().isEmpty()) {
            csv.blankRow();
            csv.writeRow("By node");
            csv.writeRow("Node IP", "Total");
            for (ReportSummaryResponse.NodeCount n : sm.getNodeBreakdown()) {
                csv.writeRow(s(n.getNodeIp()), String.valueOf(n.getTotal()));
            }
        }
        if (sm.getTopShortCodes() != null && !sm.getTopShortCodes().isEmpty()) {
            csv.blankRow();
            csv.writeRow("Top short codes");
            csv.writeRow("Short code", "Product", "Total", "Failed", "Success rate (%)");
            for (ReportSummaryResponse.ShortCodeCount c : sm.getTopShortCodes()) {
                csv.writeRow(s(c.getShortCode()), s(c.getProductName()), String.valueOf(c.getTotal()),
                        String.valueOf(c.getFailed()), pct(c.getSuccess(), c.getTotal()));
            }
        }
        if (sm.getTopPackages() != null && !sm.getTopPackages().isEmpty()) {
            csv.blankRow();
            csv.writeRow("Top packages");
            csv.writeRow("Product", "Short code", "Total", "Failed", "Success rate (%)");
            for (ReportSummaryResponse.PackageCount c : sm.getTopPackages()) {
                csv.writeRow(s(c.getProductName()), s(c.getShortCode()), String.valueOf(c.getTotal()),
                        String.valueOf(c.getFailed()), pct(c.getSuccess(), c.getTotal()));
            }
        }
        if (sm.getTopErrorCodes() != null && !sm.getTopErrorCodes().isEmpty()) {
            csv.blankRow();
            csv.writeRow("Top error codes");
            csv.writeRow("Error code", "Occurrences", "Example message");
            for (ReportSummaryResponse.ErrorCodeCount e : sm.getTopErrorCodes()) {
                csv.writeRow(s(e.getErrorCode()), String.valueOf(e.getTotal()), s(e.getSampleMessage()));
            }
        }
    }

    // ───────────────────────────── APN ─────────────────────────────

    public long exportApn(OutputStream out, String baseName, ApnReportSummaryDto sm,
                          ApnFilter filter, Progress progress) throws Exception {
        try (CsvZipWriter csv = new CsvZipWriter(out)) {
            csv.startEntry("summary.csv");
            csv.writeRow("APN Traffic Report");
            csv.writeRow("Date Range", sm.getStartDate() + " to " + sm.getEndDate());
            csv.writeRow("Generated", LocalDateTime.now().format(TS));
            csv.writeRow("Total Requests", String.valueOf(sm.getTotalRequests()));
            csv.writeRow("Successful", String.valueOf(sm.getSuccessCount()));
            csv.writeRow("Throttled (Rate Limited)", String.valueOf(sm.getThrottledCount()));
            csv.writeRow("Failed / Timeout", String.valueOf(sm.getFailedCount()));
            csv.writeRow("Success Rate (%)", String.format("%.2f", sm.getSuccessRatePercent()));
            csv.writeRow("Avg Latency (ms)", sm.getAverageLatencyMs() != null
                    ? String.valueOf(Math.round(sm.getAverageLatencyMs())) : "");
            csv.writeRow("Circuit Breaker State", s(sm.getCircuitBreakerState()));

            csv.startEntry(baseName + ".csv");
            csv.writeRow(APN_HEADERS);
            long rows = streamApn(filter, progress, r -> csv.writeRow(
                    fmt(r.getCreatedAt()), s(r.getMsisdn()), s(r.getImsi()), s(r.getStatus()),
                    s(r.getResponseMessage()), num(r.getLatencyMs()),
                    r.getHttpStatus() != null ? String.valueOf(r.getHttpStatus()) : "", s(r.getCallId())));
            csv.finish();
            return rows;
        }
    }

    // ───────────────────────────── TIBCO errors ─────────────────────────────

    private static final class DescStat {
        long total;
        LocalDateTime firstSeen;
        LocalDateTime lastSeen;
    }

    public long exportTibcoErrors(OutputStream out, UssdFilter filter, String dateLabel,
                                  long totalAll, long totalSuccess, long totalFailed,
                                  Progress progress) throws Exception {
        List<UssdTransactionRepository.HourlyStatsProjection> hourly =
                ussdRepository.countHourlyStats(filter.start(), filter.end());

        Map<String, DescStat> descriptions = new HashMap<>();
        Map<String, Long> shortCodes = new HashMap<>();
        long[] counters = {0L, 0L}; // read timeouts, 504s

        try (CsvZipWriter csv = new CsvZipWriter(out)) {
            csv.startEntry("error_transactions.csv");
            csv.writeRow(TIBCO_HEADERS);

            long[] seq = {0L};
            long rows = streamUssd(filter, true, progress, tx -> {
                if (!"FAILED".equalsIgnoreCase(tx.getStatus())) return;
                seq[0]++;

                String err = tx.getErrorMessage();
                String tibco = tx.getTibcoResponse();
                String code = tx.getErrorCode();
                if (isReadTimeout(err, tibco, code)) {
                    counters[0]++;
                } else if (is504(err, tibco, code)) {
                    counters[1]++;
                }

                trackDescription(descriptions, extractDescription(tx), tx.getTriggeredAt());

                String sc = (tx.getShortCode() != null && !tx.getShortCode().isBlank()) ? tx.getShortCode() : tx.getProductName();
                if (isBlankCode(sc) && tx.getErrorMessage() != null) sc = extractShortCode(tx.getErrorMessage());
                if (isBlankCode(sc) && tx.getTibcoResponse() != null) sc = extractShortCode(tx.getTibcoResponse());
                if (sc == null || sc.isBlank()) sc = "Unknown";
                shortCodes.merge(sc, 1L, Long::sum);

                LocalDateTime errTime = tx.getResponseTime() != null ? tx.getResponseTime()
                        : (tx.getTriggeredAt() != null && tx.getLatencyMs() != null
                        ? tx.getTriggeredAt().plusNanos(tx.getLatencyMs() * 1_000_000L) : tx.getTriggeredAt());
                String url = tx.getRemoteUrl();
                if (url != null && (url.contains("127.0.0.1") || url.contains("localhost"))) url = null;
                long sec = tx.getLatencyMs() != null ? tx.getLatencyMs() / 1000 : 0;
                String errStr = nonBlank(tibco) ? tibco : (err != null ? err : (code != null ? code : "—"));
                String reason = nonBlank(tx.getErrorReason()) ? tx.getErrorReason()
                        : (err != null ? err : (code != null ? code : "—"));
                String reply = nonBlank(tx.getSubscriberReply()) ? tx.getSubscriberReply()
                        : (err != null ? err : (tx.getErrorReason() != null ? tx.getErrorReason() : "—"));

                csv.writeRow(String.valueOf(seq[0]), errTime != null ? errTime.format(TS) : "—",
                        s(tx.getMsisdn()), sc, nonBlank(url) ? url : "—",
                        tx.getTriggeredAt() != null ? tx.getTriggeredAt().format(TS) : "—",
                        String.valueOf(sec), errStr, reason, reply);
            });

            long actualFailed = seq[0] > 0 ? seq[0] : totalFailed;
            writeTibcoSummaries(csv, dateLabel, totalAll, totalSuccess, actualFailed, hourly,
                    counters[0], counters[1], descriptions, shortCodes);
            csv.finish();
            return rows;
        }
    }

    private void trackDescription(Map<String, DescStat> map, String desc, LocalDateTime t) {
        String key = desc.length() > MAX_DESCRIPTION_KEY_LENGTH ? desc.substring(0, MAX_DESCRIPTION_KEY_LENGTH) : desc;
        if (!map.containsKey(key) && map.size() >= MAX_DESCRIPTIONS) {
            key = "(other - more than " + MAX_DESCRIPTIONS + " distinct descriptions)";
        }
        DescStat st = map.computeIfAbsent(key, k -> new DescStat());
        st.total++;
        if (t != null) {
            if (st.firstSeen == null || t.isBefore(st.firstSeen)) st.firstSeen = t;
            if (st.lastSeen == null || t.isAfter(st.lastSeen)) st.lastSeen = t;
        }
    }

    private void writeTibcoSummaries(CsvZipWriter csv, String dateLabel, long totalAll, long totalSuccess,
                                     long totalFailed,
                                     List<UssdTransactionRepository.HourlyStatsProjection> hourly,
                                     long readTimedOut, long error504,
                                     Map<String, DescStat> descriptions, Map<String, Long> shortCodes) throws IOException {
        long other = Math.max(0, totalFailed - readTimedOut - error504);
        int peakHour = 0;
        long peakCount = 0;
        Map<Integer, long[]> byHour = new HashMap<>();
        if (hourly != null) {
            for (var h : hourly) {
                int hour = h.getHour() != null ? h.getHour() : 0;
                long failed = h.getFailed() != null ? h.getFailed() : 0L;
                long total = h.getTotal() != null ? h.getTotal() : 0L;
                byHour.put(hour, new long[]{failed, total});
                if (failed > peakCount) {
                    peakCount = failed;
                    peakHour = hour;
                }
            }
        }

        csv.startEntry("daily_summary.csv");
        csv.writeRow("Date", "Normal responses", "Errors", "Error %", "Read timed out", "504 Gateway Time-out",
                "Other errors", "Peak hour (errors)");
        csv.writeRow(dateLabel, String.valueOf(totalSuccess), String.valueOf(totalFailed), pct(totalFailed, totalAll),
                String.valueOf(readTimedOut), String.valueOf(error504), String.valueOf(other),
                peakCount > 0 ? String.format("%02d:00 (%d)", peakHour, peakCount) : "");

        csv.startEntry("hourly_errors.csv");
        csv.writeRow("Hour", "Errors", "Total responses", "Error %");
        for (int h = 0; h < 24; h++) {
            long[] v = byHour.getOrDefault(h, new long[]{0, 0});
            csv.writeRow(String.format("%02d:00", h), String.valueOf(v[0]), String.valueOf(v[1]), pct(v[0], v[1]));
        }

        csv.startEntry("errors_by_description.csv");
        csv.writeRow("Exact TIBCO response", "Total", "First seen", "Last seen");
        List<Map.Entry<String, DescStat>> sortedDesc = new ArrayList<>(descriptions.entrySet());
        sortedDesc.sort((a, b) -> Long.compare(b.getValue().total, a.getValue().total));
        for (var e : sortedDesc) {
            csv.writeRow(e.getKey(), String.valueOf(e.getValue().total), fmt(e.getValue().firstSeen),
                    fmt(e.getValue().lastSeen));
        }

        csv.startEntry("top_shortcodes.csv");
        csv.writeRow("Shortcode", "Total", "% of errors");
        List<Map.Entry<String, Long>> sortedSc = new ArrayList<>(shortCodes.entrySet());
        sortedSc.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (var e : sortedSc) {
            csv.writeRow(e.getKey(), String.valueOf(e.getValue()), pct(e.getValue(), totalFailed));
        }
    }

    // ───────────────────────────── Keyset streaming ─────────────────────────────

    private long streamUssd(UssdFilter f, boolean tibcoExtras, Progress progress,
                            RowSink<UssdTransactionDto> sink) throws Exception {
        RowMapper<UssdTransactionDto> mapper = ussdMapper(tibcoExtras);
        Object[] args = ussdArgs(f);
        return streamPaged(last -> jdbc.query(
                buildUssdSql(f, tibcoExtras, last == null ? null : last.getTriggeredAt(),
                        last == null ? null : last.getId()), mapper, args), sink, progress);
    }

    @FunctionalInterface
    private interface PageFetcher<T> {
        /** Fetches the page that follows {@code last} (null for the first page). */
        List<T> fetch(T last) throws Exception;
    }

    private static final Object END = new Object();

    /**
     * Keyset-paginates with read-ahead: a producer thread fetches the next pages from the database
     * while the caller compresses and writes the current one, so database and CPU work overlap.
     */
    private <T> long streamPaged(PageFetcher<T> fetcher, RowSink<T> sink, Progress progress) throws Exception {
        java.util.concurrent.BlockingQueue<Object> queue = new java.util.concurrent.ArrayBlockingQueue<>(3);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                T last = null;
                while (true) {
                    List<T> page = fetcher.fetch(last);
                    queue.put(page);
                    if (page.size() < PAGE_SIZE) break;
                    last = page.get(page.size() - 1);
                }
                queue.put(END);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure.set(t);
                try {
                    queue.put(END);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "export-prefetch");
        producer.setDaemon(true);
        producer.start();

        long streamed = 0;
        try {
            while (true) {
                Object item = queue.take();
                if (item == END) {
                    Throwable t = failure.get();
                    if (t != null) {
                        if (t instanceof Exception ex) throw ex;
                        throw new IllegalStateException(t);
                    }
                    break;
                }
                @SuppressWarnings("unchecked")
                List<T> page = (List<T>) item;
                for (T row : page) {
                    sink.accept(row);
                }
                streamed += page.size();
                if (streamed > maxRows) {
                    throw new IllegalStateException("Export exceeds the maximum of " + maxRows
                            + " rows. Please narrow the filters.");
                }
                progress.update(streamed);
            }
        } finally {
            producer.interrupt();
        }
        return streamed;
    }

    private String buildUssdSql(UssdFilter f, boolean extras, LocalDateTime curTs, Long curId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, triggered_at, msisdn, short_code, product_name, status, error_code, error_message, "
                        + "error_reason, tibco_response, latency_ms, node_ip, channel, service_type, lang, vlr, "
                        + "cell_id, transaction_id, call_id");
        if (extras) sql.append(", response_time, remote_url, subscriber_reply");
        sql.append(" FROM ussd_transactions WHERE triggered_at BETWEEN ")
                .append(tsLiteral(f.start())).append(" AND ").append(tsLiteral(f.end()));
        if (curTs != null) {
            sql.append(" AND (triggered_at, id) < (").append(tsLiteral(curTs)).append(", ").append(curId.longValue()).append(")");
        }
        if (f.nodeIp() != null) sql.append(" AND node_ip = ?");
        if (f.status() != null) sql.append(" AND status = ?");
        if (f.shortCodeLike() != null) sql.append(" AND LOWER(short_code) LIKE ?");
        if (f.msisdnLike() != null) sql.append(" AND msisdn LIKE ?");
        if (f.errorCodeLike() != null) sql.append(" AND LOWER(error_code) LIKE ?");
        if (f.productLike() != null) sql.append(" AND LOWER(product_name) LIKE ?");
        if (f.messageLike() != null) {
            sql.append(" AND (LOWER(error_message) LIKE ? OR LOWER(error_code) LIKE ? OR LOWER(tibco_response) LIKE ?"
                    + " OR LOWER(error_reason) LIKE ? OR LOWER(subscriber_reply) LIKE ?)");
        }
        sql.append(" ORDER BY triggered_at DESC, id DESC LIMIT ").append(PAGE_SIZE);
        return sql.toString();
    }

    private Object[] ussdArgs(UssdFilter f) {
        List<Object> a = new ArrayList<>();
        if (f.nodeIp() != null) a.add(f.nodeIp());
        if (f.status() != null) a.add(f.status());
        if (f.shortCodeLike() != null) a.add(f.shortCodeLike());
        if (f.msisdnLike() != null) a.add(f.msisdnLike());
        if (f.errorCodeLike() != null) a.add(f.errorCodeLike());
        if (f.productLike() != null) a.add(f.productLike());
        if (f.messageLike() != null) {
            for (int i = 0; i < 5; i++) a.add(f.messageLike());
        }
        return a.toArray();
    }

    private RowMapper<UssdTransactionDto> ussdMapper(boolean extras) {
        return (rs, i) -> {
            UssdTransactionDto d = new UssdTransactionDto();
            d.setId(rs.getLong("id"));
            Timestamp ts = rs.getTimestamp("triggered_at");
            d.setTriggeredAt(ts != null ? ts.toLocalDateTime() : null);
            d.setMsisdn(rs.getString("msisdn"));
            d.setShortCode(rs.getString("short_code"));
            d.setProductName(rs.getString("product_name"));
            d.setStatus(rs.getString("status"));
            d.setErrorCode(rs.getString("error_code"));
            d.setErrorMessage(rs.getString("error_message"));
            d.setErrorReason(rs.getString("error_reason"));
            d.setTibcoResponse(rs.getString("tibco_response"));
            long latency = rs.getLong("latency_ms");
            d.setLatencyMs(rs.wasNull() ? null : latency);
            d.setNodeIp(rs.getString("node_ip"));
            d.setChannel(rs.getString("channel"));
            d.setServiceType(rs.getString("service_type"));
            d.setLang(rs.getString("lang"));
            d.setVlr(rs.getString("vlr"));
            d.setCellId(rs.getString("cell_id"));
            d.setTransactionId(rs.getString("transaction_id"));
            d.setCallId(rs.getString("call_id"));
            if (extras) {
                Timestamp rt = rs.getTimestamp("response_time");
                d.setResponseTime(rt != null ? rt.toLocalDateTime() : null);
                d.setRemoteUrl(rs.getString("remote_url"));
                d.setSubscriberReply(rs.getString("subscriber_reply"));
            }
            return d;
        };
    }

    private long streamApn(ApnFilter f, Progress progress, RowSink<ApnRequestDto> sink) throws Exception {
        return streamPaged(last -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, created_at, msisdn, imsi, status, response_message, http_status, latency_ms, call_id"
                            + " FROM apn_requests WHERE created_at BETWEEN ")
                    .append(tsLiteral(f.start())).append(" AND ").append(tsLiteral(f.end()));
            List<Object> args = new ArrayList<>();
            if (last != null) {
                sql.append(" AND (created_at, id) < (").append(tsLiteral(last.getCreatedAt())).append(", ")
                        .append(last.getId().longValue()).append(")");
            }
            if (f.status() != null) { sql.append(" AND status = ?"); args.add(f.status()); }
            if (f.msisdnLike() != null) { sql.append(" AND msisdn LIKE ?"); args.add(f.msisdnLike()); }
            if (f.imsiLike() != null) { sql.append(" AND imsi LIKE ?"); args.add(f.imsiLike()); }
            sql.append(" ORDER BY created_at DESC, id DESC LIMIT ").append(PAGE_SIZE);

            return jdbc.query(sql.toString(), (rs, i) -> {
                Timestamp ts = rs.getTimestamp("created_at");
                int http = rs.getInt("http_status");
                boolean httpNull = rs.wasNull();
                long latency = rs.getLong("latency_ms");
                boolean latencyNull = rs.wasNull();
                return ApnRequestDto.builder()
                        .id(rs.getLong("id"))
                        .createdAt(ts != null ? ts.toLocalDateTime() : null)
                        .msisdn(rs.getString("msisdn"))
                        .imsi(rs.getString("imsi"))
                        .status(rs.getString("status"))
                        .responseMessage(rs.getString("response_message"))
                        .httpStatus(httpNull ? null : http)
                        .latencyMs(latencyNull ? null : latency)
                        .callId(rs.getString("call_id"))
                        .build();
            }, args.toArray());
        }, sink, progress);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String tsLiteral(LocalDateTime t) {
        if (t == null) return "CURRENT_TIMESTAMP";
        return "TIMESTAMP '" + t.format(SQL_TS) + "'";
    }

    private static String fmt(LocalDateTime t) {
        return t != null ? t.format(TS) : "";
    }

    private static String s(String v) {
        return v != null ? v : "";
    }

    private static String num(Long v) {
        return v != null ? v.toString() : "";
    }

    private static String pct(long part, long total) {
        return total == 0 ? "0.00" : String.format("%.2f", part * 100.0 / total);
    }

    private static String statusLabel(String status) {
        if ("SUCCESS".equalsIgnoreCase(status)) return "Success";
        if ("FAILED".equalsIgnoreCase(status)) return "Failed";
        return "Unknown";
    }

    private static boolean nonBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static boolean isBlankCode(String v) {
        return v == null || v.isBlank() || "—".equals(v);
    }

    private static String extractDescription(UssdTransactionDto tx) {
        if (nonBlank(tx.getTibcoResponse())) return tx.getTibcoResponse().trim();
        if (nonBlank(tx.getErrorMessage())) return tx.getErrorMessage().trim();
        if (nonBlank(tx.getErrorCode())) return tx.getErrorCode().trim();
        return "Unknown Error";
    }

    private static String extractShortCode(String text) {
        Matcher m = SHORTCODE_REGEX.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static boolean containsIgnoreCase(String str, String search) {
        return str != null && str.toLowerCase().contains(search.toLowerCase());
    }

    private static boolean isReadTimeout(String err, String tibco, String code) {
        return "READ_TIMEOUT".equalsIgnoreCase(code)
                || containsIgnoreCase(tibco, "Read timed out") || containsIgnoreCase(err, "Read timed out");
    }

    private static boolean is504(String err, String tibco, String code) {
        return "HTTP_504".equalsIgnoreCase(code)
                || containsIgnoreCase(tibco, "504") || containsIgnoreCase(tibco, "Time-out")
                || containsIgnoreCase(err, "504") || containsIgnoreCase(err, "Time-out");
    }
}
