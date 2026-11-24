package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.BalanceRecordDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for generating Excel reports for balance records.
 * Uses Apache POI to create a formatted .xlsx file.
 */
@Service
@Slf4j
public class BalanceRecordExcelService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] generateExcel(List<BalanceRecordDto> records) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Balance Status");

            // Styles
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle numberStyle = createNumberStyle(workbook);
            CellStyle textStyle = createTextStyle(workbook);
            CellStyle dateStyle = createDateStyle(workbook);

            int r = 0;

            // 1. Title Row
            Row titleRow = sheet.createRow(r++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Current Balance Status");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            // 2. Description Row
            Row descRow = sheet.createRow(r++);
            Cell descCell = descRow.createCell(0);
            descCell.setCellValue("Real-time balance status for all monitored accounts");
            descCell.setCellStyle(textStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 6));

            // 3. Exported At Row
            Row exportRow = sheet.createRow(r++);
            Cell exportCellK = exportRow.createCell(0);
            exportCellK.setCellValue("Exported At:");
            exportCellK.setCellStyle(textStyle);
            Cell exportCellV = exportRow.createCell(1);
            exportCellV.setCellValue(LocalDateTime.now().format(DATE_TIME_FORMATTER));
            exportCellV.setCellStyle(textStyle);

            r++; // Spacer row

            // --- Summary Statistics ---
            long totalAccounts = records.size();
            double avgBalance = records.stream()
                    .mapToDouble(rec -> rec.getBalance() != null ? rec.getBalance().doubleValue() : 0.0)
                    .average().orElse(0.0);
            long belowThreshold = records.stream().filter(rec -> Boolean.TRUE.equals(rec.getIsBelowThreshold())).count();
            long activeAlerts = records.stream().filter(rec -> Boolean.TRUE.equals(rec.getAlertTriggered())).count();
            double belowPct = totalAccounts > 0 ? (belowThreshold * 100.0 / totalAccounts) : 0.0;

            r = addSummaryRow(sheet, r, headerStyle, textStyle, "Total Accounts", totalAccounts, "monitored accounts");
            r = addSummaryRow(sheet, r, headerStyle, numberStyle, "Average Balance", avgBalance, "across all accounts");
            r = addSummaryRow(sheet, r, headerStyle, textStyle, "Below Threshold", belowThreshold, String.format("%.1f%% of accounts", belowPct));
            r = addSummaryRow(sheet, r, headerStyle, textStyle, "Active Alerts", activeAlerts, "alerts currently active");

            r++; // Spacer row

            // 4. Table Headers
            Row headerRow = sheet.createRow(r++);
            String[] headers = {"Username", "Client ID", "Current Balance", "Threshold", "Status", "Alert Status", "Last Checked"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 5. Data Rows
            for (BalanceRecordDto record : records) {
                Row row = sheet.createRow(r++);
                
                row.createCell(0).setCellValue(record.getUsername() != null ? record.getUsername() : "—");
                row.createCell(1).setCellValue(record.getClientId());
                
                Cell balanceCell = row.createCell(2);
                balanceCell.setCellValue(record.getBalance() != null ? record.getBalance().doubleValue() : 0.0);
                balanceCell.setCellStyle(numberStyle);
                
                Cell thresholdCell = row.createCell(3);
                thresholdCell.setCellValue(record.getThreshold() != null ? record.getThreshold().doubleValue() : 0.0);
                thresholdCell.setCellStyle(numberStyle);
                
                row.createCell(4).setCellValue(record.getIsBelowThreshold() ? "Below Threshold" : "OK");
                row.createCell(5).setCellValue(record.getAlertTriggered() ? "Alert Active" : "OK");
                
                Cell checkedAtCell = row.createCell(6);
                checkedAtCell.setCellValue(record.getCheckedAt()); // String from DTO usually
                checkedAtCell.setCellStyle(textStyle);
            }

            // Fixed column widths (autoSizeColumn is not supported / too slow with SXSSFWorkbook)
            int[] colWidths = {18, 14, 18, 14, 18, 16, 22};
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i] * 256);
            }

            workbook.write(baos);
            workbook.dispose();
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to generate Balance Record Excel", e);
            throw new RuntimeException("Excel generation failed", e);
        }
    }

    // --- Style Helpers ---

    private CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        font.setColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createNumberStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createTextStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createDateStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private int addSummaryRow(Sheet sheet, int rowIdx, CellStyle keyStyle, CellStyle valStyle, String key, Object value, String subtext) {
        Row row = sheet.createRow(rowIdx);
        Cell k = row.createCell(0);
        k.setCellValue(key);
        k.setCellStyle(keyStyle);
        
        Cell v = row.createCell(1);
        if (value instanceof Number) {
            v.setCellValue(((Number) value).doubleValue());
        } else {
            v.setCellValue(String.valueOf(value));
        }
        v.setCellStyle(valStyle);
        
        Cell s = row.createCell(2);
        s.setCellValue(subtext);
        s.setCellStyle(createTextStyle(sheet.getWorkbook())); // simple small text style
        
        return rowIdx + 1;
    }
}
