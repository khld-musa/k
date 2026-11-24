package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.AlarmConfigDto;
import com.example.ussdGateway.api.dto.AlarmHistoryDto;
import com.example.ussdGateway.service.AlarmConfigService;
import com.example.ussdGateway.service.BalanceMonitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.example.ussdGateway.config.ratelimit.RateLimit;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alarms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AlarmConfigController {

    private final AlarmConfigService alarmConfigService;
    private final BalanceMonitorService balanceMonitorService;


    @GetMapping
    public ResponseEntity<List<AlarmConfigDto>> getAllConfigs() {
        return ResponseEntity.ok(alarmConfigService.getAllConfigs());
    }

    @PostMapping
    public ResponseEntity<AlarmConfigDto> saveConfig(@Valid @RequestBody AlarmConfigDto dto) {
        return ResponseEntity.ok(alarmConfigService.saveConfig(dto));
    }

    @PostMapping("/trigger-sync")
    @RateLimit(calls = 1, durationSeconds = 60)
    public ResponseEntity<Map<String, String>> triggerSync() {
        balanceMonitorService.triggerManualCheck();
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/{id}/reset-cooldown")
    public ResponseEntity<Map<String, String>> resetCooldown(@PathVariable Long id) {
        alarmConfigService.resetCooldown(id);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @GetMapping("/history")
    public ResponseEntity<List<AlarmHistoryDto>> getAllHistory() {
        return ResponseEntity.ok(alarmConfigService.getAllHistory());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        // [FIX REL-5] Return 404 if the config doesn't exist instead of silently doing nothing
        if (!alarmConfigService.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        alarmConfigService.deleteConfig(id);
        return ResponseEntity.noContent().build();
    }
}
