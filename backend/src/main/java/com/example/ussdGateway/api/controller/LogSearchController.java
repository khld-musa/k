package com.example.ussdGateway.api.controller;

import com.example.ussdGateway.api.request.LogSearchRequest;
import com.example.ussdGateway.api.request.LogSearchStartRequest;
import com.example.ussdGateway.api.response.LogSearchResponse;
import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.repository.UssdServerRepository;
import com.example.ussdGateway.service.AuditLogService;
import com.example.ussdGateway.service.LogSearchSessionService;
import com.example.ussdGateway.service.SshLogService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
public class LogSearchController {

    private final SshLogService sshLogService;
    private final AuditLogService auditLogService;
    private final LogSearchSessionService logSearchSessionService;
    private final UssdServerRepository ussdServerRepository;

    @Autowired
    public LogSearchController(SshLogService sshLogService, AuditLogService auditLogService,
                                LogSearchSessionService logSearchSessionService,
                                UssdServerRepository ussdServerRepository) {
        this.sshLogService = sshLogService;
        this.auditLogService = auditLogService;
        this.logSearchSessionService = logSearchSessionService;
        this.ussdServerRepository = ussdServerRepository;
    }

    /**
     * [FIX SEC-2] Step 1: Register SSH credentials securely.
     * Credentials are sent in the request body (over HTTPS) and stored encrypted in Redis.
     * Returns an opaque sessionId valid for 5 minutes, reusable for multiple searches.
     *
     * When useStoredCredentials=true, the password is resolved from the encrypted DB entry
     * for the given nodeId — no raw password required from the frontend.
     */
    @PostMapping("/search/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<?> startSearchSession(@Valid @RequestBody LogSearchStartRequest request,
                                                HttpServletRequest httpRequest,
                                                Authentication authentication) {
        try {
            String portalUser = authentication.getName();
            String clientIp = getClientIp(httpRequest);

            String username = request.getUsername();
            String password = request.getPassword();

            // If the frontend is using stored credentials, resolve the password from the DB
            if (request.isUseStoredCredentials()) {
                if (request.getNodeId() == null || request.getNodeId().isBlank()) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", "nodeId is required when useStoredCredentials is true"));
                }
                try {
                    UssdServer server = ussdServerRepository
                            .findById(Long.parseLong(request.getNodeId()))
                            .orElse(null);
                    if (server == null || server.getSshPassword() == null) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "No stored SSH credentials found for the selected server. Please enter the password manually."));
                    }
                    // Override with DB-stored (and already decrypted by AttributeEncryptor) values
                    if (server.getSshUsername() != null && !server.getSshUsername().isBlank()) {
                        username = server.getSshUsername();
                    }
                    password = server.getSshPassword();
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid nodeId format"));
                }
            } else if (password == null || password.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "SSH password is required"));
            }

            String sessionId = logSearchSessionService.createSession(username, password, clientIp, portalUser);

            log.info("Log-search session created for user '{}' from IP {}", portalUser, clientIp);
            auditLogService.log("START_LOG_SEARCH_SESSION", portalUser, null,
                    "SSH credential session registered for log search", clientIp);

            return ResponseEntity.ok(Map.of("searchSessionId", sessionId));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid search-start input: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * [FIX SEC-2] Step 2: Execute the search using the sessionId — no raw password in this request.
     * The controller resolves credentials from Redis before passing them to SshLogService.
     */
    @PostMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<?> searchLogs(@Valid @RequestBody LogSearchRequest request,
                                        HttpServletRequest httpRequest,
                                        Authentication authentication) {
        try {
            // Resolve SSH credentials from the Redis session
            String[] sessionData = logSearchSessionService.resolveSession(request.getSessionId());
            if (sessionData == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Invalid or expired search session. Please call /search/start again."));
            }
            String username = sessionData[0];
            String password = sessionData[1];
            // sessionData[2] is portalUser from session — use auth.getName() as the authoritative source
            String authUser = authentication != null ? authentication.getName() : "SYSTEM";

            LogSearchResponse response = sshLogService.searchRemoteLogs(request, username, password);

            String msisdnsStr = String.join(",", request.getMsisdns());
            int nodeCount = request.getNodes() != null ? request.getNodes().size() : 0;
            String details = String.format("Searched %s nodes for MSISDNs: %s on date: %s",
                    nodeCount > 0 ? nodeCount : "Global", msisdnsStr, request.getDate());
            auditLogService.log("SEARCH_LOGS", authUser, null, details, getClientIp(httpRequest));

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid log search input: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Log search failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Log search failed"));
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
