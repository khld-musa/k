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
 * [FIX SEC-2] Manages short-lived log-search sessions stored in Redis.
 *
 * This prevents SSH credentials from travelling in every search request body,
 * where they would appear in request-logging middleware and browser DevTools.
 *
 * Flow:
 *  1. Frontend POSTs credentials to /api/v1/logs/search/start (JWT-authenticated)
 *  2. Backend validates, encrypts password, stores in Redis with 5-min TTL, returns sessionId
 *  3. Frontend POSTs search params + sessionId to /api/v1/logs/search (no password)
 *  4. Backend resolves credentials from Redis and performs the SSH search
 *
 * Unlike Live Trace (single-use), search sessions are reusable within the TTL window
 * so the user can run multiple searches without re-entering credentials.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogSearchSessionService {

    private static final long SESSION_TTL_SECONDS = 300; // 5 minutes
    private static final int TOKEN_BYTES = 32;
    private static final String KEY_PREFIX = "log-search:";
    private static final String DELIMITER = "\u001F"; // Unit separator

    private final StringRedisTemplate redisTemplate;
    private final EncryptionUtil encryptionUtil;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Store search-session credentials in Redis and return an opaque session ID.
     *
     * @param username  SSH username
     * @param password  SSH password (will be AES-256-GCM encrypted at rest)
     * @param clientIp  the client's IP (for audit; not used for binding since searches are stateless)
     * @param portalUser the authenticated portal username
     * @return opaque session ID valid for 5 minutes
     */
    public String createSession(String username, String password, String clientIp, String portalUser) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        String encryptedPassword = encryptionUtil.encrypt(password);
        String value = String.join(DELIMITER, username, encryptedPassword, portalUser, clientIp);

        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, value, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("Created log-search session {} for user '{}' from IP {}", sessionId, portalUser, clientIp);
        return sessionId;
    }

    /**
     * Resolve credentials from a session. The session remains valid (reusable) until TTL expires.
     *
     * @param sessionId the session ID
     * @return String array [username, password, portalUser] or null if not found / expired
     */
    public String[] resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String key = KEY_PREFIX + sessionId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            log.warn("Log-search session not found or expired: {}", sessionId);
            return null;
        }
        String[] parts = value.split(DELIMITER, 4);
        if (parts.length != 4) {
            log.error("Corrupted log-search session data for: {}", sessionId);
            return null;
        }
        String decryptedPassword = encryptionUtil.decrypt(parts[1]);
        return new String[]{ parts[0], decryptedPassword, parts[2] }; // [username, password, portalUser]
    }
}

