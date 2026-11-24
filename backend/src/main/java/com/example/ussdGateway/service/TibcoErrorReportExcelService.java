package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.UssdTransactionDto;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Generates an Excel workbook with exact structure and sheets matching TIBCO_ERRORS_EXACT:
 *   1. Daily summary
 *   2. Hourly errors
 *   3. Hourly error %
 *   4. Errors by description
 *   5. Top shortcodes
 *   6. Error transactions (streamed)
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class TibcoErrorReportExcelService {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_ONLY_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern INVALID_XML_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private final UssdTransactionRepository ussdTransactionRepository;

    public StreamingTibcoErrorWriter createStreamingWriter(OutputStream outputStream,
                                                            LocalDateTime rangeStart,
                                                            LocalDateTime rangeEnd,
                                                            String dateLabel,
                                                            long totalAll,
                                                            long totalSuccess,
                                                            long totalFailed) {
        return new StreamingTibcoErrorWriter(outputStream, rangeStart, rangeEnd, dateLabel, totalAll, totalSuccess, totalFailed);
    }

    private static final int MAX_ROWS_PER_SHEET = 1_000_000;

    public class StreamingTibcoErrorWriter implements Consumer<UssdTransactionDto>, AutoCloseable {
        private final SXSSFWorkbook workbook;
        private final OutputStream outputStream;
        private final Sheet sheet1;
        private final Sheet sheet2;
        private final Sheet sheet3;
        private final Sheet sheet4;
        private final Sheet sheet5;
        private Sheet sheet6;
        private int sheet6Count = 1;
        private final CellStyle headerStyle;
        private final CellStyle textStyle;
        private final CellStyle numStyle;
        private final CellStyle percentStyle;
        private final CellStyle titleStyle;
        private int rowNumSheet6 = 0;
        private int seqNumber = 1;

        private final LocalDateTime rangeStart;
        private final LocalDateTime rangeEnd;
        private final String dateLabel;
        private final long totalAll;
        private final long totalSuccess;
        private final long totalFailed;
        private final List<UssdTransactionRepository.HourlyStatsProjection> hourly;

        private long readTimedOutCount = 0L;
        private long error504Count = 0L;
        private final Map<String, ErrorDescStat> descriptionsMap = new HashMap<>();
        private final Map<String, Long> shortCodeMap = new HashMap<>();

        private static class ErrorDescStat {
            long total = 0;
            LocalDateTime firstSeen;
            LocalDateTime lastSeen;
        }

        public StreamingTibcoErrorWriter(OutputStream outputStream,
                                         LocalDateTime rangeStart,
                                         LocalDateTime rangeEnd,
                                         String dateLabel,
                                         long totalAll,
                                         long totalSuccess,
                                         long totalFailed) {
            this.workbook = new SXSSFWorkbook(200);
            this.outputStream = outputStream;
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
            this.dateLabel = dateLabel;
            this.totalAll = totalAll;
            this.totalSuccess = totalSuccess;
            this.totalFailed = totalFailed;

            // Styles
            DataFormat df = workbook.createDataFormat();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            boldFont.setFontHeightInPoints((short) 10);

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);

            this.titleStyle = workbook.createCellStyle();
            this.titleStyle.setFont(titleFont);

            this.headerStyle = workbook.createCellStyle();
            this.headerStyle.setFont(boldFont);
            this.headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            this.headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorders(this.headerStyle);

            this.textStyle = workbook.createCellStyle();
            setBorders(this.textStyle);

            this.numStyle = workbook.createCellStyle();
            this.numStyle.setDataFormat(df.getFormat("#,##0"));
            setBorders(this.numStyle);

            this.percentStyle = workbook.createCellStyle();
            this.percentStyle.setDataFormat(df.getFormat("0.00%"));
            setBorders(this.percentStyle);

            // Pre-fetch hourly stats once for both Daily Summary and Hourly sheets (fast index query)
            this.hourly = ussdTransactionRepository.countHourlyStats(rangeStart, rangeEnd);

            // Create all 6 sheets upfront in the exact required tab order
            this.sheet1 = workbook.createSheet("Daily summary");
            this.sheet2 = workbook.createSheet("Hourly errors");
            this.sheet3 = workbook.createSheet("Hourly error %");
            this.sheet4 = workbook.createSheet("Errors by description");
            this.sheet5 = workbook.createSheet("Top shortcodes");
            this.sheet6 = workbook.createSheet("Error transactions");
            setupSheet6Headers();
        }

        private void setBorders(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setBorderTop(BorderStyle.THIN);
            style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setBorderLeft(BorderStyle.THIN);
            style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setBorderRight(BorderStyle.THIN);
            style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        }

        private void buildDailySummarySheet(Sheet sheet, LocalDateTime start, LocalDateTime end, String dateLabel,
                                             long totalAll, long totalSuccess, long totalFailed,
                                             List<UssdTransactionRepository.HourlyStatsProjection> hourly,
                                             long readTimedOut, long error504) {
            sheet.setColumnWidth(0, 15 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 14 * 256);
            sheet.setColumnWidth(4, 18 * 256);
            sheet.setColumnWidth(5, 24 * 256);
            sheet.setColumnWidth(6, 16 * 256);
            sheet.setColumnWidth(7, 22 * 256);

            // Title
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("TIBCO responses per day – " + dateLabel + " (USSD subscription)");
            titleCell.setCellStyle(titleStyle);

            // Headers row 2
            Row hRow = sheet.createRow(2);
            String[] headers = {"Date", "Normal responses", "Errors", "Error %", "Read timed out", "504 Gateway Time-out", "Other errors", "Peak hour (errors)"};
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            long otherErrors = Math.max(0, totalFailed - readTimedOut - error504);
            double errorRate = totalAll > 0 ? (double) totalFailed / totalAll : 0.0;

            // Find peak hour using pre-fetched hourly stats
            int peakHour = 0;
            long peakCount = 0;
            if (hourly != null) {
                for (var h : hourly) {
                    long f = h.getFailed() != null ? h.getFailed() : 0L;
                    if (f > peakCount) {
                        peakCount = f;
                        peakHour = h.getHour() != null ? h.getHour() : 0;
                    }
                }
            }
            String peakHourStr = peakCount > 0 ? String.format("%02d:00 (%,d)", peakHour, peakCount) : "—";

            // Data row
            Row r = sheet.createRow(3);
            Cell c0 = r.createCell(0); c0.setCellValue(dateLabel); c0.setCellStyle(textStyle);
            Cell c1 = r.createCell(1); c1.setCellValue(totalSuccess); c1.setCellStyle(numStyle);
            Cell c2 = r.createCell(2); c2.setCellValue(totalFailed); c2.setCellStyle(numStyle);
            Cell c3 = r.createCell(3); c3.setCellValue(errorRate); c3.setCellStyle(percentStyle);
            Cell c4 = r.createCell(4); c4.setCellValue(readTimedOut); c4.setCellStyle(numStyle);
            Cell c5 = r.createCell(5); c5.setCellValue(error504); c5.setCellStyle(numStyle);
            Cell c6 = r.createCell(6); c6.setCellValue(otherErrors); c6.setCellStyle(numStyle);
            Cell c7 = r.createCell(7); c7.setCellValue(peakHourStr); c7.setCellStyle(textStyle);

            // Total row
            Row totRow = sheet.createRow(4);
            Cell t0 = totRow.createCell(0); t0.setCellValue("Total"); t0.setCellStyle(headerStyle);
            Cell t1 = totRow.createCell(1); t1.setCellValue(totalSuccess); t1.setCellStyle(headerStyle);
            Cell t2 = totRow.createCell(2); t2.setCellValue(totalFailed); t2.setCellStyle(headerStyle);
            Cell t3 = totRow.createCell(3); t3.setCellValue(errorRate); t3.setCellStyle(headerStyle);
            Cell t4 = totRow.createCell(4); t4.setCellValue(readTimedOut); t4.setCellStyle(headerStyle);
            Cell t5 = totRow.createCell(5); t5.setCellValue(error504); t5.setCellStyle(headerStyle);
            Cell t6 = totRow.createCell(6); t6.setCellValue(otherErrors); t6.setCellStyle(headerStyle);
            Cell t7 = totRow.createCell(7); t7.setCellValue(""); t7.setCellStyle(headerStyle);
        }

        private void buildHourlySheets(Sheet sheetErr, Sheet sheetPct, LocalDateTime start, LocalDateTime end, String dateLabel,
                                       List<UssdTransactionRepository.HourlyStatsProjection> hourly) {
            sheetErr.setColumnWidth(0, 15 * 256);
            sheetPct.setColumnWidth(0, 15 * 256);
            for (int i = 1; i <= 24; i++) {
                sheetErr.setColumnWidth(i, 9 * 256);
                sheetPct.setColumnWidth(i, 11 * 256);
            }

            // Titles
            Row tErr = sheetErr.createRow(0);
            Cell cTErr = tErr.createCell(0);
            cTErr.setCellValue("TIBCO errors per hour – USSD subscription");
            cTErr.setCellStyle(titleStyle);

            Row tPct = sheetPct.createRow(0);
            Cell cTPct = tPct.createCell(0);
            cTPct.setCellValue("TIBCO error % per hour – USSD subscription");
            cTPct.setCellStyle(titleStyle);

            // Headers row 2
            Row hErr = sheetErr.createRow(2);
            Row hPct = sheetPct.createRow(2);
            Cell h0e = hErr.createCell(0); h0e.setCellValue("Date"); h0e.setCellStyle(headerStyle);
            Cell h0p = hPct.createCell(0); h0p.setCellValue("Date"); h0p.setCellStyle(headerStyle);

            for (int h = 0; h < 24; h++) {
                String label = String.format("%02d:00", h);
                Cell ce = hErr.createCell(h + 1); ce.setCellValue(label); ce.setCellStyle(headerStyle);
                Cell cp = hPct.createCell(h + 1); cp.setCellValue(label); cp.setCellStyle(headerStyle);
            }

            Map<Integer, Long> failedByHour = new HashMap<>();
            Map<Integer, Long> totalByHour = new HashMap<>();
            if (hourly != null) {
                for (var stat : hourly) {
                    int h = stat.getHour() != null ? stat.getHour() : 0;
                    failedByHour.put(h, stat.getFailed() != null ? stat.getFailed() : 0L);
                    totalByHour.put(h, stat.getTotal() != null ? stat.getTotal() : 0L);
                }
            }

            Row rErr = sheetErr.createRow(3);
            Row rPct = sheetPct.createRow(3);
            Cell dCellE = rErr.createCell(0); dCellE.setCellValue(dateLabel); dCellE.setCellStyle(textStyle);
            Cell dCellP = rPct.createCell(0); dCellP.setCellValue(dateLabel); dCellP.setCellStyle(textStyle);

            for (int h = 0; h < 24; h++) {
                long failed = failedByHour.getOrDefault(h, 0L);
                long total = totalByHour.getOrDefault(h, 0L);
                double pct = total > 0 ? (double) failed / total : 0.0;

                Cell ce = rErr.createCell(h + 1);
                ce.setCellValue(failed);
                ce.setCellStyle(numStyle);

                Cell cp = rPct.createCell(h + 1);
                cp.setCellValue(pct);
                cp.setCellStyle(percentStyle);
            }
        }

        private void buildErrorsByDescriptionSheet(Sheet sheet, LocalDateTime start, LocalDateTime end,
                                                   Map<String, ErrorDescStat> descriptions) {
            sheet.setColumnWidth(0, 65 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 20 * 256);
            sheet.setColumnWidth(3, 20 * 256);

            Row tRow = sheet.createRow(0);
            Cell cT = tRow.createCell(0);
            cT.setCellValue("TIBCO error responses by description and day – USSD subscription");
            cT.setCellStyle(titleStyle);

            Row hRow = sheet.createRow(2);
            String[] headers = {"Exact TIBCO response (as received; multi-line bodies joined on one line)", "Total", "First seen", "Last seen"};
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            // Sort descriptions by total descending
            List<Map.Entry<String, ErrorDescStat>> sorted = descriptions.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue().total, e1.getValue().total))
                    .toList();

            int rIdx = 3;
            for (var entry : sorted) {
                Row r = sheet.createRow(rIdx++);
                Cell c0 = r.createCell(0); c0.setCellValue(sanitizeForExcel(entry.getKey())); c0.setCellStyle(textStyle);
                Cell c1 = r.createCell(1); c1.setCellValue(entry.getValue().total); c1.setCellStyle(numStyle);
                Cell c2 = r.createCell(2);
                LocalDateTime fSeen = entry.getValue().firstSeen;
                c2.setCellValue(fSeen != null ? fSeen.format(TS_FORMAT) : (start != null ? start.format(TS_FORMAT) : "—"));
                c2.setCellStyle(textStyle);
                Cell c3 = r.createCell(3);
                LocalDateTime lSeen = entry.getValue().lastSeen;
                c3.setCellValue(lSeen != null ? lSeen.format(TS_FORMAT) : (end != null ? end.format(TS_FORMAT) : "—"));
                c3.setCellStyle(textStyle);
            }
        }

        private void buildTopShortcodesSheet(Sheet sheet, Map<String, Long> shortCodes, long totalFailed) {
            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 16 * 256);

            Row tRow = sheet.createRow(0);
            Cell cT = tRow.createCell(0);
            cT.setCellValue("Error transactions by shortcode and day – USSD subscription");
            cT.setCellStyle(titleStyle);

            Row hRow = sheet.createRow(2);
            String[] headers = {"Shortcode", "Total", "% of total"};
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            // Sort shortcodes by total descending
            List<Map.Entry<String, Long>> sorted = shortCodes.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                    .toList();

            int rIdx = 3;
            for (var entry : sorted) {
                Row r = sheet.createRow(rIdx++);
                String code = entry.getKey();
                if (code == null || code.isBlank() || "—".equals(code) || "null".equalsIgnoreCase(code)) {
                    code = "Unknown";
                }
                Cell c0 = r.createCell(0); c0.setCellValue(sanitizeForExcel(code)); c0.setCellStyle(textStyle);
                long tot = entry.getValue() != null ? entry.getValue() : 0L;
                Cell c1 = r.createCell(1); c1.setCellValue(tot); c1.setCellStyle(numStyle);
                double pct = totalFailed > 0 ? (double) tot / totalFailed : 0.0;
                Cell c2 = r.createCell(2); c2.setCellValue(pct); c2.setCellStyle(percentStyle);
            }
        }

        private void setupSheet6Headers() {
            String[] headers = {"#", "Error time", "MSISDN", "Shortcode", "TIBCO URL", "Request time", "Duration (s)", "Error (TIBCO response)", "Reason", "Reply to subscriber"};
            int[] widths = {8, 20, 16, 14, 38, 20, 14, 55, 40, 35};
            for (int i = 0; i < widths.length; i++) {
                sheet6.setColumnWidth(i, widths[i] * 256);
            }

            Row hRow = sheet6.createRow(rowNumSheet6++);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            sheet6.createFreezePane(0, 1);
        }

        @Override
        public void accept(UssdTransactionDto tx) {
            // Write only failed transactions to Sheet 6
            if (!"FAILED".equalsIgnoreCase(tx.getStatus())) {
                return;
            }

            // 1. Specific errors tracking
            String err = tx.getErrorMessage();
            String tibco = tx.getTibcoResponse();
            String code = tx.getErrorCode();
            if (isReadTimeout(err, tibco, code)) {
                readTimedOutCount++;
            } else if (is504(err, tibco, code)) {
                error504Count++;
            }

            // 2. Error description tracking
            String desc = extractDescription(tx);
            LocalDateTime txTime = tx.getTriggeredAt();
            descriptionsMap.compute(desc, (k, v) -> {
                if (v == null) {
                    ErrorDescStat s = new ErrorDescStat();
                    s.total = 1;
                    s.firstSeen = txTime;
                    s.lastSeen = txTime;
                    return s;
                }
                v.total++;
                if (txTime != null) {
                    if (v.firstSeen == null || txTime.isBefore(v.firstSeen)) v.firstSeen = txTime;
                    if (v.lastSeen == null || txTime.isAfter(v.lastSeen)) v.lastSeen = txTime;
                }
                return v;
            });

            // 3. Shortcode tracking
            String sc = (tx.getShortCode() != null && !tx.getShortCode().isBlank()) ? tx.getShortCode() : tx.getProductName();
            if ((sc == null || sc.isBlank() || "—".equals(sc)) && tx.getErrorMessage() != null) {
                sc = extractShortCodeFromText(tx.getErrorMessage());
            }
            if ((sc == null || sc.isBlank() || "—".equals(sc)) && tx.getTibcoResponse() != null) {
                sc = extractShortCodeFromText(tx.getTibcoResponse());
            }
            if (sc == null || sc.isBlank()) {
                sc = "Unknown";
            }
            shortCodeMap.merge(sc, 1L, Long::sum);

            if (rowNumSheet6 >= MAX_ROWS_PER_SHEET) {
                sheet6.setAutoFilter(new CellRangeAddress(0, rowNumSheet6 - 1, 0, 9));
                sheet6 = workbook.createSheet("Error transactions (" + (++sheet6Count) + ")");
                rowNumSheet6 = 0;
                setupSheet6Headers();
            }
            Row r = sheet6.createRow(rowNumSheet6++);

            Cell c0 = r.createCell(0);
            c0.setCellValue(seqNumber++);
            c0.setCellStyle(numStyle);

            Cell c1 = r.createCell(1);
            LocalDateTime errTime = tx.getResponseTime() != null ? tx.getResponseTime() :
                    (tx.getTriggeredAt() != null && tx.getLatencyMs() != null ? tx.getTriggeredAt().plusNanos(tx.getLatencyMs() * 1_000_000L) : tx.getTriggeredAt());
            c1.setCellValue(errTime != null ? errTime.format(TS_FORMAT) : "—");
            c1.setCellStyle(textStyle);

            Cell c2 = r.createCell(2);
            c2.setCellValue(sanitizeForExcel(tx.getMsisdn()));
            c2.setCellStyle(textStyle);

            Cell c3 = r.createCell(3);
            c3.setCellValue(sanitizeForExcel(sc));
            c3.setCellStyle(textStyle);

            Cell c4 = r.createCell(4);
            String url = tx.getRemoteUrl();
            if (url != null && (url.contains("127.0.0.1") || url.contains("localhost"))) {
                url = null;
            }
            c4.setCellValue(sanitizeForExcel(url != null && !url.isBlank() ? url : "—"));
            c4.setCellStyle(textStyle);

            Cell c5 = r.createCell(5);
            c5.setCellValue(tx.getTriggeredAt() != null ? tx.getTriggeredAt().format(TS_FORMAT) : "—");
            c5.setCellStyle(textStyle);

            Cell c6 = r.createCell(6);
            long sec = tx.getLatencyMs() != null ? (tx.getLatencyMs() / 1000) : 0;
            c6.setCellValue(sec);
            c6.setCellStyle(numStyle);

            Cell c7 = r.createCell(7);
            String errStr = (tx.getTibcoResponse() != null && !tx.getTibcoResponse().isBlank())
                    ? tx.getTibcoResponse()
                    : (tx.getErrorMessage() != null ? tx.getErrorMessage() : (tx.getErrorCode() != null ? tx.getErrorCode() : "—"));
            c7.setCellValue(sanitizeForExcel(errStr));
            c7.setCellStyle(textStyle);

            Cell c8 = r.createCell(8);
            String reason = (tx.getErrorReason() != null && !tx.getErrorReason().isBlank())
                    ? tx.getErrorReason()
                    : (tx.getErrorMessage() != null ? tx.getErrorMessage() : (tx.getErrorCode() != null ? tx.getErrorCode() : "—"));
            c8.setCellValue(sanitizeForExcel(reason));
            c8.setCellStyle(textStyle);

            Cell c9 = r.createCell(9);
            String reply = (tx.getSubscriberReply() != null && !tx.getSubscriberReply().isBlank())
                    ? tx.getSubscriberReply()
                    : (tx.getErrorMessage() != null ? tx.getErrorMessage() : (tx.getErrorReason() != null ? tx.getErrorReason() : "—"));
            c9.setCellValue(sanitizeForExcel(reply));
            c9.setCellStyle(textStyle);
        }

        private boolean isReadTimeout(String err, String tibco, String code) {
            if ("READ_TIMEOUT".equalsIgnoreCase(code)) return true;
            if (tibco != null && containsIgnoreCase(tibco, "Read timed out")) return true;
            if (err != null && containsIgnoreCase(err, "Read timed out")) return true;
            return false;
        }

        private boolean is504(String err, String tibco, String code) {
            if ("HTTP_504".equalsIgnoreCase(code)) return true;
            if (tibco != null && (containsIgnoreCase(tibco, "504") || containsIgnoreCase(tibco, "Time-out"))) return true;
            if (err != null && (containsIgnoreCase(err, "504") || containsIgnoreCase(err, "Time-out"))) return true;
            return false;
        }

        private boolean containsIgnoreCase(String str, String searchStr) {
            if (str == null || searchStr == null) return false;
            return str.toLowerCase().contains(searchStr.toLowerCase());
        }

        private String extractDescription(UssdTransactionDto tx) {
            if (tx.getTibcoResponse() != null && !tx.getTibcoResponse().isBlank()) {
                return tx.getTibcoResponse().trim();
            }
            if (tx.getErrorMessage() != null && !tx.getErrorMessage().isBlank()) {
                return tx.getErrorMessage().trim();
            }
            if (tx.getErrorCode() != null && !tx.getErrorCode().isBlank()) {
                return tx.getErrorCode().trim();
            }
            return "Unknown Error";
        }

        private static final java.util.regex.Pattern SHORTCODE_EXTRACT_REGEX = java.util.regex.Pattern.compile("(\\*\\d+[0-9*#]*#)");

        private String extractShortCodeFromText(String text) {
            if (text == null) return null;
            java.util.regex.Matcher m = SHORTCODE_EXTRACT_REGEX.matcher(text);
            return m.find() ? m.group(1) : null;
        }

        private String formatTimestamp(Object ts) {
            if (ts == null) return "—";
            try {
                if (ts instanceof java.sql.Timestamp) {
                    return ((java.sql.Timestamp) ts).toLocalDateTime().format(TS_FORMAT);
                }
                if (ts instanceof java.time.LocalDateTime) {
                    return ((java.time.LocalDateTime) ts).format(TS_FORMAT);
                }
                if (ts instanceof java.time.Instant) {
                    return ((java.time.Instant) ts).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().format(TS_FORMAT);
                }
                if (ts instanceof java.util.Date) {
                    return new java.sql.Timestamp(((java.util.Date) ts).getTime()).toLocalDateTime().format(TS_FORMAT);
                }
                String s = String.valueOf(ts).trim();
                if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "—";
                if (s.contains("T")) {
                    try {
                        return LocalDateTime.parse(s.substring(0, 19)).format(TS_FORMAT);
                    } catch (Exception ignored) {}
                }
                return s;
            } catch (Exception e) {
                return String.valueOf(ts);
            }
        }

        private String sanitizeForExcel(String val) {
            if (val == null) return "—";
            // Strip invalid XML control chars that crash Excel SXSSF using precompiled Pattern
            String cleaned = INVALID_XML_CHARS.matcher(val).replaceAll("");
            if (cleaned.length() > 32000) {
                return cleaned.substring(0, 32000) + "... [truncated]";
            }
            return cleaned;
        }

        private boolean finished = false;

        public synchronized void finish() throws IOException {
            if (finished) {
                return;
            }
            finished = true;

            // Populate sheets 1 to 5 from accumulated in-memory stats (takes milliseconds)
            long actualFailed = (seqNumber > 1) ? (seqNumber - 1) : totalFailed;
            buildDailySummarySheet(sheet1, rangeStart, rangeEnd, dateLabel, totalAll, totalSuccess, actualFailed, hourly, readTimedOutCount, error504Count);
            buildHourlySheets(sheet2, sheet3, rangeStart, rangeEnd, dateLabel, hourly);
            buildErrorsByDescriptionSheet(sheet4, rangeStart, rangeEnd, descriptionsMap);
            buildTopShortcodesSheet(sheet5, shortCodeMap, actualFailed);

            if (rowNumSheet6 > 1) {
                sheet6.setAutoFilter(new CellRangeAddress(0, rowNumSheet6 - 1, 0, 9));
            }
            workbook.write(outputStream);
            outputStream.flush();
            workbook.dispose();
            workbook.close();
        }

        @Override
        public void close() {
            try {
                if (!finished) {
                    workbook.dispose();
                    workbook.close();
                }
            } catch (Exception ignored) {}
        }
    }
}
