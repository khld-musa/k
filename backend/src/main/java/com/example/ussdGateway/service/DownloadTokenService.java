package com.example.ussdGateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages short-lived, single-use download tokens for SFTP file downloads.
 * These tokens replace the practice of passing the main JWT as a query parameter,
 * which leaks it into browser history, server logs, and referrer headers.
 */
@Slf4j
@Service
public class DownloadTokenService {

    private static final long TOKEN_TTL_MS = 60_000; // 60 seconds
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, DownloadTokenEntry> tokens = new ConcurrentHashMap<>();

    /**
     * Generate a single-use download token tied to a specific user.
     */
    public String generateToken(String username) {
        // Clean up expired tokens periodically
        cleanupExpired();

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        tokens.put(token, new DownloadTokenEntry(username, System.currentTimeMillis() + TOKEN_TTL_MS));
        log.debug("Generated download token for user: {}", username);
        return token;
    }

    /**
     * Validate and consume a download token. Returns the username if valid, null otherwise.
     * Each token can only be used once.
     */
    public String validateAndConsume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        DownloadTokenEntry entry = tokens.remove(token); // Single-use: remove on read
        if (entry == null) {
            log.warn("Download token not found (already used or never issued)");
            return null;
        }

        if (System.currentTimeMillis() > entry.expiresAt()) {
            log.warn("Download token expired for user: {}", entry.username());
            return null;
        }

        return entry.username();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }

    private record DownloadTokenEntry(String username, long expiresAt) {}
}

