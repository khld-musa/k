package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.response.AuditLogResponse;
import com.example.ussdGateway.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Audit Log Controller
 * Provides read access to audit log entries (ADMIN only)
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogResponse>> getAllLogs(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> logs = auditLogService.getAllLogs(pageable)
                .map(AuditLogResponse::new);
        return ResponseEntity.ok(logs);
    }
}
