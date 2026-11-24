package com.example.ussdGateway.service;

import com.example.ussdGateway.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Manages short-lived live trace sessions stored in Redis.
 * This avoids passing SSH credentials and JWT tokens as URL query parameters
 * (which would leak them into browser history, server logs, and referrer headers).
 *
 * Flow:
 *  1. Frontend POSTs credentials to /api/v1/logs/live-trace/start (authenticated via JWT header)
 *  2. Backend validates, stores config in Redis with a 120s TTL, returns opaque session ID
 *  3. Frontend connects EventSource to /api/v1/logs/live-trace/stream?sessionId={id}
 *  4. Backend reads config from Redis (single-use: deleted after read) and starts SSE stream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTraceSessionService {

    private static final long SESSION_TTL_SECONDS = 120;
    private static final int TOKEN_BYTES = 32;
    private static final String KEY_PREFIX = "live-trace:";
    private static final String CONSUMED_PREFIX = "live-trace-consumed:";
    private static final String DELIMITER = "\u001F"; // Unit separator — safe delimiter
    // [FIX LT-6] TTL for the "already consumed" marker — allows browser to see a clear message
    private static final long CONSUMED_TTL_SECONDS = 300;

    private final StringRedisTemplate redisTemplate;
    private final EncryptionUtil encryptionUtil;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Store live trace session config in Redis and return an opaque session ID.
     * The session is bound to the client's IP address for defense-in-depth.
     *
     * @param nodes     comma-separated "id:ip" pairs
     * @param username  SSH username
     * @param password  SSH password
     * @param msisdns   comma-separated MSISDNs
     * @param portalUser the authenticated portal username (for audit/logging)
     * @param clientIp  the client's IP address (for binding)
     * @return opaque session ID
     */
    public String createSession(String nodes, String username, String password,
                                String msisdns, String portalUser, String clientIp) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        String encryptedPassword = encryptionUtil.encrypt(password);

        // Store as a single delimited value — includes client IP for binding
        String value = String.join(DELIMITER, nodes, username, encryptedPassword, msisdns, portalUser, clientIp);
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, value, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

        log.debug("Created live trace session {} for user {} from IP {}", sessionId, portalUser, clientIp);
        return sessionId;
    }

    /**
     * Retrieve and consume (single-use) a live trace session.
     * Validates the client IP matches the one that created the session.
     *
     * @param sessionId the session ID to consume
     * @param clientIp  the requesting client's IP (must match creator's IP)
     * @return String array: [nodes, username, password, msisdns, portalUser], or null if invalid/expired/IP mismatch
     */
    public String[] consumeSession(String sessionId, String clientIp) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String key = KEY_PREFIX + sessionId;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            // [FIX LT-6] Check if this session was already consumed (vs. truly expired/invalid)
            String consumedKey = CONSUMED_PREFIX + sessionId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(consumedKey))) {
                log.warn("Live trace session already consumed (stream already active): {}", sessionId);
            } else {
                log.warn("Live trace session not found or expired: {}", sessionId);
            }
            return null;
        }
        String[] parts = value.split(DELIMITER, 6);
        if (parts.length != 6) {
            log.error("Corrupted live trace session data for: {}", sessionId);
            return null;
        }

        // Validate IP binding
        String storedIp = parts[5];
        if (clientIp == null || !clientIp.equals(storedIp)) {
            log.warn("IP mismatch for live trace session {}: stored={}, request={}", sessionId, storedIp, clientIp);
            return null;
        }

        // [FIX LT-6] Write a "consumed" marker so reconnects get a clear error
        redisTemplate.opsForValue().set(CONSUMED_PREFIX + sessionId, "active", CONSUMED_TTL_SECONDS, TimeUnit.SECONDS);

        String decryptedPassword = encryptionUtil.decrypt(parts[2]);

        // Return the original 5 fields (excluding the IP)
        return new String[] { parts[0], parts[1], decryptedPassword, parts[3], parts[4] };
    }
}

