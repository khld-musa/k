package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.response.DashboardStatsResponse;
import com.example.ussdGateway.api.response.DashboardSummaryResponse;
import com.example.ussdGateway.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Full admin dashboard — USSD stats, node health, balance status, activity.
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DashboardStatsResponse> getStats() {
        return ResponseEntity.ok(dashboardService.getDashboardStats());
    }

    /**
     * Lightweight summary — accessible to ALL authenticated users.
     * Returns aggregate USSD traffic stats for today (no sensitive data).
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<DashboardSummaryResponse> getSummary(Authentication auth) {
        return ResponseEntity.ok(dashboardService.getDashboardSummary(auth.getName()));
    }

    /**
     * Current user's recent search/trace activity.
     */
    @GetMapping("/my-activity")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<List<DashboardStatsResponse.AuditLogDto>> getMyActivity(Authentication auth) {
        return ResponseEntity.ok(dashboardService.getUserActivity(auth.getName()));
    }
}
