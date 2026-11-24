package com.example.ussdGateway.controller;

import com.example.ussdGateway.service.SystemSettingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system-settings")
@RequiredArgsConstructor
public class SystemSettingController {

    private final SystemSettingService systemSettingService;

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, String>> getSetting(@PathVariable String key) {
        String value = systemSettingService.getSettingValue(key, "");
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> updateSetting(@PathVariable String key, @RequestBody SettingUpdateRequest request) {
        systemSettingService.saveSettingValue(key, request.getValue(), request.getDescription());
        return ResponseEntity.ok().build();
    }

    @Data
    public static class SettingUpdateRequest {
        private String value;
        private String description;
    }
}
