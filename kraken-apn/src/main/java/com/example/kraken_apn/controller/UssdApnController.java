package com.example.kraken_apn.controller;

import com.example.kraken_apn.dto.ApnSendResponseDto;
import com.example.kraken_apn.service.ApnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ussd")
@RequiredArgsConstructor
public class UssdApnController {

    private final ApnService apnService;

    @PostMapping(value = {"/send-apn", "/apn"})
    public ResponseEntity<ApnSendResponseDto> sendApn(@RequestBody(required = false) Map<String, Object> payload) {
        log.info("Received real-time USSD APN request: {}", payload);
        ApnSendResponseDto response = apnService.processUssdApnRequest(payload != null ? payload : Map.of());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "kraken-apn"));
    }
}
