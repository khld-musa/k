package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.request.McnCheckerRequest;
import com.example.ussdGateway.api.response.McnCheckerResponse;
import com.example.ussdGateway.service.AuditLogService;
import com.example.ussdGateway.service.McnCheckerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs/mcn")
@RequiredArgsConstructor
public class McnCheckerController {

    private final McnCheckerService mcnCheckerService;
    private final AuditLogService auditLogService;

    @PostMapping("/check")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<?> checkMcnLogs(@Valid @RequestBody McnCheckerRequest request,
                                          HttpServletRequest httpRequest,
                                          Authentication authentication) {
        try {
            String authUser = authentication != null ? authentication.getName() : "SYSTEM";
            String clientIp = getClientIp(httpRequest);

            log.info("MCN log check initiated by user '{}' for MSISDN: {}", authUser, request.getMsisdn());
            McnCheckerResponse response = mcnCheckerService.checkLogs(request);

            String details = String.format("Checked MCN logs for MSISDN: %s", request.getMsisdn());
            auditLogService.log("CHECK_MCN_LOGS", authUser, null, details, clientIp);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid MCN log check input: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("MCN log check failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An unexpected error occurred during the MCN log check."));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
