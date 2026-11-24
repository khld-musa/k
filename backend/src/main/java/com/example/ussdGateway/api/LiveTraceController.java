package com.example.ussdGateway.api;

import com.example.ussdGateway.api.dto.request.LiveTraceRequest;
import com.example.ussdGateway.api.response.LiveLogUpdate;
import com.example.ussdGateway.api.response.LogEvent;
import com.example.ussdGateway.security.JwtTokenProvider;
import com.example.ussdGateway.service.LiveTraceService;
import com.example.ussdGateway.service.LiveTraceSessionService;
import com.example.ussdGateway.service.AuditLogService;
import com.example.ussdGateway.util.InputSanitizer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs/live-trace")
@RequiredArgsConstructor
public class LiveTraceController {

    private final LiveTraceService liveTraceService;
    private final LiveTraceSessionService liveTraceSessionService;
    private final AuditLogService auditLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final com.example.ussdGateway.repository.UssdServerRepository ussdServerRepository;

    // [FIX P1-10] Per-user concurrent trace limit — prevents a single user from hogging SSH connections
    private static final int MAX_TRACES_PER_USER = 3;
    private final ConcurrentHashMap<String, AtomicInteger> activeTracesPerUser = new ConcurrentHashMap<>();

    /**
     * Step 1: Start a live trace session. Credentials are sent securely in the request body.
     * Returns an opaque session ID that the frontend uses to connect the EventSource.
     */
    @PostMapping("/start")
    public ResponseEntity<?> startTrace(@Valid @RequestBody LiveTraceRequest request,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        try {
            // Validate inputs against injection
            InputSanitizer.validateMsisdns(request.getMsisdns());
            InputSanitizer.validateNodes(request.getNodes());

            String portalUser = authentication.getName();
            String clientIp = getClientIp(httpRequest);

            // [FIX P1-10] Enforce per-user concurrent trace limit
            AtomicInteger userCount = activeTracesPerUser.computeIfAbsent(portalUser, k -> new AtomicInteger(0));
            if (userCount.get() >= MAX_TRACES_PER_USER) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Maximum concurrent traces (" + MAX_TRACES_PER_USER + ") reached. Stop an existing trace first."));
            }

            String nodesStr = request.getNodes().entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");

            String msisdnsStr = String.join(",", request.getMsisdns());

            String username = request.getUsername();
            String password = request.getPassword();

            // If credentials are empty or password is missing, try to fetch stored credentials from the DB
            if ((username == null || username.isBlank() || password == null || password.isBlank())
                    && !request.getNodes().isEmpty()) {
                String firstNodeId = request.getNodes().keySet().iterator().next();
                com.example.ussdGateway.entity.UssdServer server = ussdServerRepository.findById(Long.parseLong(firstNodeId)).orElse(null);
                if (server != null && server.getSshUsername() != null) {
                    if (username == null || username.isBlank()) username = server.getSshUsername();
                    if (password == null || password.isBlank()) password = server.getSshPassword();
                }
            }

            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("SSH credentials required (neither provided nor stored)");
            }

            InputSanitizer.validateSshUsername(username);

            String sessionId = liveTraceSessionService.createSession(
                    nodesStr, username, password, msisdnsStr, portalUser, clientIp);

            log.info("Live trace session created by user '{}' for MSISDNs: {}", portalUser, request.getMsisdns());
            userCount.incrementAndGet(); // [FIX P1-10] Track active trace

            int nodeCount = request.getNodes() != null ? request.getNodes().size() : 0;
            String details = String.format("Started live trace on %s nodes for MSISDNs: %s",
                    nodeCount > 0 ? nodeCount : "Auto (Local)", msisdnsStr);
            auditLogService.log("START_LIVE_TRACE", portalUser, null, details, clientIp);

            return ResponseEntity.ok(Map.of("traceSessionId", sessionId));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid live trace input: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Step 2: Stream live trace events via SSE.
     * [FIX SEC-1] The JWT token is validated via the optional 'token' query parameter,
     * because EventSource does not support custom request headers. This provides
     * defense-in-depth on top of the Redis session's IP binding and single-use guarantee.
     * [FIX UI] Invalid session errors are sent as data events so the frontend can read them.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<LiveLogUpdate>> streamLogs(@RequestParam String sessionId,
                                                            @RequestParam(required = false) String token,
                                                            HttpServletRequest httpRequest,
                                                            HttpServletResponse httpResponse) {

        // [FIX LT-19] Stop nginx and any other proxy from buffering the stream — buffered SSE events
        // only reach the browser once the proxy buffer fills (i.e. after more traffic arrives).
        httpResponse.setHeader("X-Accel-Buffering", "no");
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");

        // [FIX P0-2] JWT token is now REQUIRED for SSE stream — reject if missing or invalid.
        // EventSource cannot send Authorization headers, so the token is passed as a query param.
        if (token == null || token.isBlank() || !jwtTokenProvider.validateToken(token)) {
            log.warn("Missing or invalid JWT token on live trace stream request for session: {}", sessionId);
            return buildErrorFlux("Authentication failed: valid token is required");
        }

        String clientIp = getClientIp(httpRequest);
        String[] sessionData = liveTraceSessionService.consumeSession(sessionId, clientIp);
        if (sessionData == null) {
            log.warn("Invalid or expired live trace session: {}", sessionId);
            // [FIX] Return a data event instead of a comment — frontend onmessage handler can read this
            return buildErrorFlux("Invalid or expired session. Please start a new trace.");
        }

        String nodesStr = sessionData[0];
        String username = sessionData[1];
        String password = sessionData[2];
        String msisdnsStr = sessionData[3];
        String portalUser = sessionData[4];

        List<String> msisdnList = Arrays.asList(msisdnsStr.split(","));
        Map<String, String> nodeMap = new HashMap<>();
        Arrays.stream(nodesStr.split(",")).forEach(node -> {
            String[] parts = node.split(":", 2);
            if (parts.length == 2) {
                nodeMap.put(parts[0], parts[1]);
            }
        });

        log.info("Starting live trace stream for user '{}' on nodes: {}", portalUser, nodeMap.keySet());

        return liveTraceService.streamLogs(nodeMap, username, password, msisdnList)
                .map(update -> ServerSentEvent.<LiveLogUpdate>builder()
                        .data(update)
                        .build())
                .mergeWith(Flux.interval(Duration.ofSeconds(15))
                        .map(i -> ServerSentEvent.<LiveLogUpdate>builder()
                                .comment("keep-alive")
                                .build()))
                .doOnError(e -> log.error("SSE stream error: {}", e.getMessage()))
                .doFinally(signal -> {
                    // [FIX P1-10] Decrement active trace count on stream end (complete, error, or cancel)
                    AtomicInteger count = activeTracesPerUser.get(portalUser);
                    if (count != null) {
                        int val = count.decrementAndGet();
                        if (val <= 0) activeTracesPerUser.remove(portalUser);
                    }
                    log.info("SSE stream terminated for user '{}' (signal={})", portalUser, signal);
                });
    }

    /** Builds a single-element error Flux with the error message as a parseable data event */
    private Flux<ServerSentEvent<LiveLogUpdate>> buildErrorFlux(String message) {
        LogEvent errEvent = new LogEvent(Instant.now().toString(), "ERROR", message, null, message);
        LiveLogUpdate errUpdate = new LiveLogUpdate("SYSTEM_ERROR", "", "ERROR", "", "", "", errEvent);
        return Flux.just(ServerSentEvent.<LiveLogUpdate>builder().data(errUpdate).build());
    }

    /**
     * Resolves the real client IP, accounting for reverse proxies via X-Forwarded-For.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For can be a comma-separated list; the first entry is the original client IP
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
