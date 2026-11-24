package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.UssdTransactionDto;
import com.example.ussdGateway.api.response.ReportSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generates the .xlsx export for USSD transaction reports: a "Summary" sheet (totals and breakdowns)
 * and a "Transactions" sheet (one row per call, filterable, header frozen).
 * Styling is deliberately plain - bold headers on a light gray band, thin gray rules, no colored fills.
 */
@Service
@Slf4j
public class ReportExcelService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] TRANSACTION_HEADERS = {
            "Time", "MSISDN", "Short Code", "Product", "Status", "Error Code", "Error Message", "Reason", "TIBCO Response",
            "Latency (ms)", "Node IP", "Channel", "Service Type", "Lang", "VLR", "Cell ID", "Transaction ID", "Call ID"};
    // Column widths in characters (autoSizeColumn is too slow for large exports)
    private static final int[] TRANSACTION_WIDTHS = {20, 15, 13, 20, 10, 14, 45, 35, 55, 12, 15, 18, 13, 6, 16, 10, 38, 26};

    public byte[] generateExcel(List<UssdTransactionDto> transactions, ReportSummaryResponse summary) {
        // Streaming workbook keeps only a window of rows in memory, so month-long exports don't exhaust the heap
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Styles styles = new Styles(workbook);
            writeSummarySheet(workbook.createSheet("Summary"), summary, styles);
            writeTransactionsSheet(workbook.createSheet("Transactions"), transactions, styles);

            workbook.write(baos);
            workbook.dispose();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate USSD Transaction Report Excel", e);
            throw new RuntimeException("Excel generation failed", e);
        }
    }

    /**
     * Streaming variant: writes the workbook directly to the given {@code OutputStream}
     * and returns a {@link Consumer} that the caller feeds one row at a time.
     * Call {@link StreamingExcelWriter#finish()} when all rows have been written so
     * the workbook is flushed + disposed.
     */
    public StreamingExcelWriter createStreamingWriter(OutputStream outputStream, ReportSummaryResponse summary) {
        return new StreamingExcelWriter(outputStream, summary);
    }

    /**
     * Holds the open workbook and sheet state so rows can be added one at a time from the
     * paginated query loop without buffering everything in memory.
     */
    private static final int MAX_ROWS_PER_SHEET = 1_000_000;

    public class StreamingExcelWriter implements Consumer<UssdTransactionDto>, AutoCloseable {
        private final SXSSFWorkbook workbook;
        private final OutputStream outputStream;
        private Sheet txSheet;
        private final Styles styles;
        private int rowIndex = 1; // row 0 is the header
        private int sheetCount = 1;
        private long totalRows = 0;

        StreamingExcelWriter(OutputStream outputStream, ReportSummaryResponse summary) {
            this.workbook = new SXSSFWorkbook(200);
            this.outputStream = outputStream;
            this.styles = new Styles(workbook);
            writeSummarySheet(workbook.createSheet("Summary"), summary, styles);
            this.txSheet = workbook.createSheet("Transactions");
            for (int i = 0; i < TRANSACTION_WIDTHS.length; i++) {
                txSheet.setColumnWidth(i, TRANSACTION_WIDTHS[i] * 256);
            }
            headerRow(txSheet, 0, TRANSACTION_HEADERS, styles);
            txSheet.createFreezePane(0, 1);
        }

        @Override
        public void accept(UssdTransactionDto tx) {
            if (rowIndex >= MAX_ROWS_PER_SHEET) {
                txSheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, TRANSACTION_HEADERS.length - 1));
                txSheet = workbook.createSheet("Transactions (" + (++sheetCount) + ")");
                for (int i = 0; i < TRANSACTION_WIDTHS.length; i++) {
                    txSheet.setColumnWidth(i, TRANSACTION_WIDTHS[i] * 256);
                }
                headerRow(txSheet, 0, TRANSACTION_HEADERS, styles);
                txSheet.createFreezePane(0, 1);
                rowIndex = 1;
            }
            writeTransactionRow(txSheet, rowIndex++, tx, styles);
            totalRows++;
        }

        public int getRowCount() {
            return (int) Math.min(totalRows, Integer.MAX_VALUE);
        }

        /** Flushes the auto-filter, writes the workbook to the output stream and disposes temp files. */
        public void finish() throws IOException {
            try {
                txSheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIndex - 1, 1), 0, TRANSACTION_HEADERS.length - 1));
                workbook.write(outputStream);
            } finally {
                try { workbook.dispose(); } catch (Exception ignored) {}
                try { workbook.close();   } catch (Exception ignored) {}
            }
        }

        @Override
        public void close() {
            try { workbook.dispose(); } catch (Exception ignored) {}
            try { workbook.close();   } catch (Exception ignored) {}
        }
    }

    private void writeSummarySheet(Sheet sheet, ReportSummaryResponse summary, Styles styles) {
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 14 * 256);
        sheet.setColumnWidth(3, 14 * 256);
        sheet.setColumnWidth(4, 50 * 256);

        int r = 0;
        setText(sheet.createRow(r++), 0, "USSD Transaction Report", styles.title);
        r++;
        r = keyValue(sheet, r, "Period", summary.getStartDate() + " to " + summary.getEndDate(), styles);
        r = keyValue(sheet, r, "Node", summary.getNodeIp() != null ? summary.getNodeIp() : "All nodes", styles);
        r = keyValue(sheet, r, "Generated", LocalDateTime.now().format(DATE_TIME_FORMATTER), styles);
        r++;

        setText(sheet.createRow(r++), 0, "Totals", styles.section);
        r = keyNumber(sheet, r, "Total transactions", summary.getTotalTransactions(), styles.integer, styles);
        r = keyNumber(sheet, r, "Successful", summary.getSuccessCount(), styles.integer, styles);
        r = keyNumber(sheet, r, "Failed", summary.getFailedCount(), styles.integer, styles);
        r = keyNumber(sheet, r, "Success rate", summary.getSuccessRatePercent() / 100.0, styles.percent, styles);
        if (summary.getAverageLatencyMs() != null) {
            r = keyNumber(sheet, r, "Average latency (ms)", summary.getAverageLatencyMs(), styles.integer, styles);
        }
        r++;

        if (summary.getDailyBreakdown() != null && !summary.getDailyBreakdown().isEmpty()) {
            setText(sheet.createRow(r++), 0, "By day", styles.section);
            r = headerRow(sheet, r, new String[]{"Date", "Total", "Successful", "Failed", "Success rate"}, styles);
            for (ReportSummaryResponse.DailyCount d : summary.getDailyBreakdown()) {
                Row row = sheet.createRow(r++);
                setText(row, 0, d.getDay().toString(), styles.text);
                setNumber(row, 1, d.getTotal(), styles.integer);
                setNumber(row, 2, d.getSuccess(), styles.integer);
                setNumber(row, 3, d.getFailed(), styles.integer);
                setNumber(row, 4, d.getTotal() == 0 ? 0 : (double) d.getSuccess() / d.getTotal(), styles.percent);
            }
            r++;
        }

        if (summary.getTopShortCodes() != null && !summary.getTopShortCodes().isEmpty()) {
            setText(sheet.createRow(r++), 0, "Top short codes", styles.section);
            r = headerRow(sheet, r, new String[]{"Short code", "Product", "Total", "Failed", "Success rate"}, styles);
            for (ReportSummaryResponse.ShortCodeCount c : summary.getTopShortCodes()) {
                Row row = sheet.createRow(r++);
                setText(row, 0, c.getShortCode(), styles.text);
                setText(row, 1, dash(c.getProductName()), styles.text);
                setNumber(row, 2, c.getTotal(), styles.integer);
                setNumber(row, 3, c.getFailed(), styles.integer);
                setNumber(row, 4, c.getTotal() == 0 ? 0 : (double) c.getSuccess() / c.getTotal(), styles.percent);
            }
            r++;
        }

        if (summary.getTopErrorCodes() != null && !summary.getTopErrorCodes().isEmpty()) {
            setText(sheet.createRow(r++), 0, "Top error codes", styles.section);
            r = headerRow(sheet, r, new String[]{"Error code", "Occurrences", "", "", "Example message"}, styles);
            for (ReportSummaryResponse.ErrorCodeCount e : summary.getTopErrorCodes()) {
                Row row = sheet.createRow(r++);
                setText(row, 0, e.getErrorCode(), styles.text);
                setNumber(row, 1, e.getTotal(), styles.integer);
                setText(row, 4, dash(e.getSampleMessage()), styles.text);
            }
        }
    }

    private void writeTransactionsSheet(Sheet sheet, List<UssdTransactionDto> transactions, Styles styles) {
        for (int i = 0; i < TRANSACTION_WIDTHS.length; i++) {
            sheet.setColumnWidth(i, TRANSACTION_WIDTHS[i] * 256);
        }
        headerRow(sheet, 0, TRANSACTION_HEADERS, styles);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(transactions.size(), 1), 0, TRANSACTION_HEADERS.length - 1));

        int r = 1;
        for (UssdTransactionDto tx : transactions) {
            writeTransactionRow(sheet, r++, tx, styles);
        }
    }

    private void writeTransactionRow(Sheet sheet, int r, UssdTransactionDto tx, Styles styles) {
        Row row = sheet.createRow(r);
        int c = 0;
        if (tx.getTriggeredAt() != null) {
            Cell cell = row.createCell(c++);
            cell.setCellValue(tx.getTriggeredAt());
            cell.setCellStyle(styles.dateTime);
        } else {
            setText(row, c++, "", styles.text);
        }
        setText(row, c++, dash(tx.getMsisdn()), styles.text);
        setText(row, c++, dash(tx.getShortCode()), styles.text);
        setText(row, c++, dash(tx.getProductName()), styles.text);
        setText(row, c++, statusLabel(tx.getStatus()), styles.text);
        setText(row, c++, blank(tx.getErrorCode()), styles.text);
        setText(row, c++, blank(tx.getErrorMessage()), styles.text);
        setText(row, c++, blank(tx.getErrorReason()), styles.text);
        setText(row, c++, blank(tx.getTibcoResponse()), styles.text);
        if (tx.getLatencyMs() != null) {
            setNumber(row, c++, tx.getLatencyMs(), styles.integer);
        } else {
            setText(row, c++, "", styles.text);
        }
        setText(row, c++, dash(tx.getNodeIp()), styles.text);
        setText(row, c++, dash(tx.getChannel()), styles.text);
        setText(row, c++, dash(tx.getServiceType()), styles.text);
        setText(row, c++, dash(tx.getLang()), styles.text);
        setText(row, c++, dash(tx.getVlr()), styles.text);
        setText(row, c++, dash(tx.getCellId()), styles.text);
        setText(row, c++, dash(tx.getTransactionId()), styles.text);
        setText(row, c, dash(tx.getCallId()), styles.text);
    }

    private int keyValue(Sheet sheet, int r, String key, String value, Styles styles) {
        Row row = sheet.createRow(r);
        setText(row, 0, key, styles.label);
        setText(row, 1, value, styles.plain);
        return r + 1;
    }

    private int keyNumber(Sheet sheet, int r, String key, double value, CellStyle valueStyle, Styles styles) {
        Row row = sheet.createRow(r);
        setText(row, 0, key, styles.label);
        setNumber(row, 1, value, valueStyle);
        return r + 1;
    }

    private int headerRow(Sheet sheet, int r, String[] headers, Styles styles) {
        Row row = sheet.createRow(r);
        for (int i = 0; i < headers.length; i++) {
            setText(row, i, headers[i], styles.header);
        }
        return r + 1;
    }

    private static void setText(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void setNumber(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String statusLabel(String status) {
        if ("SUCCESS".equalsIgnoreCase(status)) return "Success";
        if ("FAILED".equalsIgnoreCase(status)) return "Failed";
        return "Unknown";
    }

    private static String dash(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    private static String blank(String value) {
        return value != null ? value : "";
    }

    /** Shared cell styles - created once per workbook (Excel caps the number of distinct styles). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle section;
        final CellStyle label;
        final CellStyle plain;
        final CellStyle header;
        final CellStyle text;
        final CellStyle integer;
        final CellStyle percent;
        final CellStyle dateTime;

        Styles(Workbook wb) {
            DataFormat format = wb.createDataFormat();

            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            Font boldFont = wb.createFont();
            boldFont.setBold(true);

            section = wb.createCellStyle();
            section.setFont(boldFont);

            Font mutedFont = wb.createFont();
            mutedFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            label = wb.createCellStyle();
            label.setFont(mutedFont);

            plain = wb.createCellStyle();

            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            ruled(header);

            text = wb.createCellStyle();
            ruled(text);

            integer = wb.createCellStyle();
            integer.setDataFormat(format.getFormat("#,##0"));
            ruled(integer);

            percent = wb.createCellStyle();
            percent.setDataFormat(format.getFormat("0.0%"));
            ruled(percent);

            dateTime = wb.createCellStyle();
            dateTime.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm:ss"));
            dateTime.setAlignment(HorizontalAlignment.LEFT);
            ruled(dateTime);
        }

        private static void ruled(CellStyle style) {
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
