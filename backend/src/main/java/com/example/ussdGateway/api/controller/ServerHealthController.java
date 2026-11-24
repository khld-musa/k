package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.dto.response.ServerHealthResponse;
import com.example.ussdGateway.service.ServerHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/health")
@RequiredArgsConstructor
public class ServerHealthController {

    private final ServerHealthService serverHealthService;

    /**
     * Production server resource diagnostics: Storage partitions, RAM usage, CPU load,
     * Database connection pool, JVM runtime, and USSD node reachability.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<ServerHealthResponse> getHealth() {
        return ResponseEntity.ok(serverHealthService.getHealthStatus());
    }
}
