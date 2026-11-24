package com.example.ussdGateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Token blacklist service using Redis.
 * Stores invalidated JWT token IDs (jti) with TTL matching the token's remaining lifetime.
 * After the token would have expired naturally, the blacklist entry is automatically cleaned up by Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Add a JWT token ID to the blacklist.
     *
     * @param jti           the JWT ID (jti claim)
     * @param remainingTtlMs remaining time-to-live in milliseconds
     */
    public void blacklist(String jti, long remainingTtlMs) {
        if (jti == null || jti.isBlank() || remainingTtlMs <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", remainingTtlMs, TimeUnit.MILLISECONDS);
        log.debug("Blacklisted token jti={} for {}ms", jti, remainingTtlMs);
    }

    /**
     * Check if a JWT token ID has been blacklisted.
     *
     * @param jti the JWT ID (jti claim)
     * @return true if the token is blacklisted
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }
}

