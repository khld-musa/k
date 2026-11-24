package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.response.ApnSendResponseDto;
import com.example.ussdGateway.service.ApnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public endpoint called directly by the USSD platform / gateway.
 * Processes real-time requests synchronously, rate-limits to protect downstream API,
 * and returns the flash message response under the USSD session timeout.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ussd")
@RequiredArgsConstructor
@Tag(name = "USSD APN Gateway", description = "Real-time synchronous USSD APN endpoints")
public class UssdApnController {

    private final ApnService apnService;

    @Operation(summary = "Send APN request from USSD", description = "Accepts flexible USSD payload containing imsi and msisdn, queries external APN API, and returns response message.")
    @PostMapping(value = {"/send-apn", "/apn"})
    public ResponseEntity<ApnSendResponseDto> sendApn(@RequestBody(required = false) Map<String, Object> payload) {
        log.info("Received real-time USSD APN request: {}", payload);
        ApnSendResponseDto response = apnService.processUssdApnRequest(payload != null ? payload : Map.of());
        return ResponseEntity.ok(response);
    }
}
