package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.BalanceRecordDto;
import com.example.ussdGateway.service.BalanceRecordExcelService;
import com.example.ussdGateway.service.BalanceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * REST controller for managing and querying balance status records.
 * Provides endpoints to retrieve the current balance status for alarm configurations.
 * Note: Each alarm config has only one record that is updated on each check.
 */
@RestController
@RequestMapping("/api/v1/balance-records")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
public class BalanceRecordController {

    private final BalanceRecordService balanceRecordService;
    private final BalanceRecordExcelService balanceRecordExcelService;

    /**
     * Get the current balance record for a specific alarm configuration.
     */
    @GetMapping("/alarm/{alarmConfigId}")
    public ResponseEntity<BalanceRecordDto> getRecordByAlarmConfig(
            @PathVariable Long alarmConfigId) {
        BalanceRecordDto record = balanceRecordService.getRecordByAlarmConfigId(alarmConfigId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * Get all current balance records (one per alarm config).
     */
    @GetMapping
    public ResponseEntity<List<BalanceRecordDto>> getAllRecords() {
        List<BalanceRecordDto> records = balanceRecordService.getAllRecords();
        return ResponseEntity.ok(records);
    }

    /**
     * Get balance records for multiple alarm configurations.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<BalanceRecordDto>> getRecordsByAlarmConfigs(
            @RequestBody List<Long> alarmConfigIds) {
        List<BalanceRecordDto> records = balanceRecordService.getRecordsByAlarmConfigIds(alarmConfigIds);
        return ResponseEntity.ok(records);
    }

    /**
     * Export all current balance records as an Excel file.
     */
    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel() {
        List<BalanceRecordDto> records = balanceRecordService.getAllRecords();
        byte[] excelBytes = balanceRecordExcelService.generateExcel(records);

        String filename = String.format("balance-status-%s.xlsx", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}

