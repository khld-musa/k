package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.ApnRequestDto;
import com.example.ussdGateway.api.dto.response.ApnReportSummaryDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

@Service
@Slf4j
public class ApnExcelService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.regex.Pattern INVALID_XML_CHARS = java.util.regex.Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private static final String[] HEADERS = {
            "Timestamp", "MSISDN", "IMSI", "Status", "Response Message",
            "Latency (ms)", "HTTP Status", "Call ID"
    };
    private static final int[] WIDTHS = {20, 16, 18, 14, 40, 14, 12, 28};

    public byte[] generateExcel(List<ApnRequestDto> requests, ApnReportSummaryDto summary) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dateStyle = workbook.createCellStyle();
            CellStyle dataStyle = workbook.createCellStyle();

            // Summary Sheet
            Sheet summarySheet = workbook.createSheet("APN Summary");
            summarySheet.setColumnWidth(0, 25 * 256);
            summarySheet.setColumnWidth(1, 20 * 256);

            int r = 0;
            Row titleRow = summarySheet.createRow(r++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("APN Traffic Report");

            r++; // blank
            createSummaryRow(summarySheet, r++, "Date Range", summary.getStartDate() + " to " + summary.getEndDate());
            createSummaryRow(summarySheet, r++, "Total Requests", String.valueOf(summary.getTotalRequests()));
            createSummaryRow(summarySheet, r++, "Successful", String.valueOf(summary.getSuccessCount()));
            createSummaryRow(summarySheet, r++, "Throttled (Rate Limited)", String.valueOf(summary.getThrottledCount()));
            createSummaryRow(summarySheet, r++, "Failed / Timeout", String.valueOf(summary.getFailedCount()));
            createSummaryRow(summarySheet, r++, "Success Rate", summary.getSuccessRatePercent() + "%");
            createSummaryRow(summarySheet, r++, "Avg Latency", (summary.getAverageLatencyMs() != null ? Math.round(summary.getAverageLatencyMs()) + " ms" : "—"));
            createSummaryRow(summarySheet, r++, "Circuit Breaker State", summary.getCircuitBreakerState());

            // Transactions Sheet
            Sheet txSheet = workbook.createSheet("APN Transactions");
            for (int i = 0; i < WIDTHS.length; i++) {
                txSheet.setColumnWidth(i, WIDTHS[i] * 256);
            }

            Row hRow = txSheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (ApnRequestDto item : requests) {
                writeApnRow(txSheet, rowIdx++, item);
            }

            workbook.write(baos);
            workbook.dispose();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate APN Excel export", e);
            throw new RuntimeException("Excel export failed", e);
        }
    }

    /** Creates a streaming writer for paged APN exports. */
    public StreamingApnExcelWriter createStreamingWriter(OutputStream outputStream, ApnReportSummaryDto summary) {
        return new StreamingApnExcelWriter(outputStream, summary);
    }

    private static final int MAX_ROWS_PER_SHEET = 1_000_000;

    public class StreamingApnExcelWriter implements Consumer<ApnRequestDto>, AutoCloseable {
        private final SXSSFWorkbook workbook;
        private final OutputStream outputStream;
        private Sheet txSheet;
        private final CellStyle headerStyle;
        private int rowIndex = 1;
        private int sheetCount = 1;

        StreamingApnExcelWriter(OutputStream outputStream, ApnReportSummaryDto summary) {
            this.workbook = new SXSSFWorkbook(100);
            this.outputStream = outputStream;

            this.headerStyle = createHeaderStyle(workbook);

            Sheet summarySheet = workbook.createSheet("APN Summary");
            summarySheet.setColumnWidth(0, 25 * 256);
            summarySheet.setColumnWidth(1, 20 * 256);
            int r = 0;
            summarySheet.createRow(r++).createCell(0).setCellValue("APN Traffic Report");
            r++;
            createSummaryRow(summarySheet, r++, "Date Range", summary.getStartDate() + " to " + summary.getEndDate());
            createSummaryRow(summarySheet, r++, "Total Requests", String.valueOf(summary.getTotalRequests()));
            createSummaryRow(summarySheet, r++, "Successful", String.valueOf(summary.getSuccessCount()));
            createSummaryRow(summarySheet, r++, "Throttled (Rate Limited)", String.valueOf(summary.getThrottledCount()));
            createSummaryRow(summarySheet, r++, "Failed / Timeout", String.valueOf(summary.getFailedCount()));
            createSummaryRow(summarySheet, r++, "Success Rate", summary.getSuccessRatePercent() + "%");
            createSummaryRow(summarySheet, r++, "Avg Latency", (summary.getAverageLatencyMs() != null ? Math.round(summary.getAverageLatencyMs()) + " ms" : "—"));
            createSummaryRow(summarySheet, r++, "Circuit Breaker State", summary.getCircuitBreakerState());

            this.txSheet = workbook.createSheet("APN Transactions");
            for (int i = 0; i < WIDTHS.length; i++) {
                txSheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            Row hRow = txSheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }
        }

        @Override
        public void accept(ApnRequestDto item) {
            if (rowIndex >= MAX_ROWS_PER_SHEET) {
                txSheet = workbook.createSheet("APN Transactions (" + (++sheetCount) + ")");
                for (int i = 0; i < WIDTHS.length; i++) {
                    txSheet.setColumnWidth(i, WIDTHS[i] * 256);
                }
                Row hRow = txSheet.createRow(0);
                for (int i = 0; i < HEADERS.length; i++) {
                    Cell c = hRow.createCell(i);
                    c.setCellValue(HEADERS[i]);
                    c.setCellStyle(headerStyle);
                }
                rowIndex = 1;
            }
            writeApnRow(txSheet, rowIndex++, item);
        }

        public void finish() throws IOException {
            try {
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

    private String sanitize(String val) {
        if (val == null) return "—";
        String cleaned = INVALID_XML_CHARS.matcher(val).replaceAll("");
        if (cleaned.length() > 32000) {
            return cleaned.substring(0, 32000) + "... [truncated]";
        }
        return cleaned;
    }

    private void writeApnRow(Sheet sheet, int rowIdx, ApnRequestDto item) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(item.getCreatedAt() != null ? item.getCreatedAt().format(DATE_TIME_FORMATTER) : "—");
        row.createCell(1).setCellValue(sanitize(item.getMsisdn()));
        row.createCell(2).setCellValue(sanitize(item.getImsi()));
        row.createCell(3).setCellValue(item.getStatus() != null ? item.getStatus() : "—");
        row.createCell(4).setCellValue(sanitize(item.getResponseMessage()));
        if (item.getLatencyMs() != null) {
            row.createCell(5).setCellValue(item.getLatencyMs());
        } else {
            row.createCell(5).setCellValue("—");
        }
        if (item.getHttpStatus() != null) {
            row.createCell(6).setCellValue(item.getHttpStatus());
        } else {
            row.createCell(6).setCellValue("—");
        }
        row.createCell(7).setCellValue(sanitize(item.getCallId()));
    }

    private void createSummaryRow(Sheet sheet, int rowIdx, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }
}
