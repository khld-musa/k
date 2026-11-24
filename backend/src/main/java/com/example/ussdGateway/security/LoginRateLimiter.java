package com.example.ussdGateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed login rate limiter.
 * Tracks login attempts by IP + username combination.
 * - After 5 failed attempts in 60 seconds → block for 60s
 * - After 10 consecutive failures → block for 15 minutes
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login-attempt:";
    private static final String BLOCK_PREFIX = "login-block:";
    private static final int MAX_ATTEMPTS_PER_MINUTE = 5;
    private static final int LOCKOUT_THRESHOLD = 10;
    private static final long ATTEMPT_WINDOW_SECONDS = 60;
    private static final long SHORT_BLOCK_SECONDS = 60;
    private static final long LONG_BLOCK_SECONDS = 900; // 15 minutes

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check if a login attempt is allowed for the given IP + username.
     * @return true if the attempt is allowed, false if rate-limited
     */
    public boolean isAllowed(String ip, String username) {
        String blockKey = BLOCK_PREFIX + ip + ":" + username;

        // Check if currently blocked
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
            log.warn("Login blocked for IP={} user={}", ip, username);
            return false;
        }

        String attemptKey = KEY_PREFIX + ip + ":" + username;
        String countStr = redisTemplate.opsForValue().get(attemptKey);
        int count = countStr != null ? Integer.parseInt(countStr) : 0;

        return count < MAX_ATTEMPTS_PER_MINUTE;
    }

    /**
     * Record a failed login attempt.
     */
    public void recordFailure(String ip, String username) {
        String attemptKey = KEY_PREFIX + ip + ":" + username;
        Long count = redisTemplate.opsForValue().increment(attemptKey);

        // Set TTL on first attempt
        if (count != null && count == 1) {
            redisTemplate.expire(attemptKey, ATTEMPT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        // Block after threshold
        if (count != null) {
            if (count >= LOCKOUT_THRESHOLD) {
                String blockKey = BLOCK_PREFIX + ip + ":" + username;
                redisTemplate.opsForValue().set(blockKey, "locked", LONG_BLOCK_SECONDS, TimeUnit.SECONDS);
                log.warn("Account locked for 15 minutes: IP={} user={} (after {} attempts)", ip, username, count);
            } else if (count >= MAX_ATTEMPTS_PER_MINUTE) {
                String blockKey = BLOCK_PREFIX + ip + ":" + username;
                redisTemplate.opsForValue().set(blockKey, "throttled", SHORT_BLOCK_SECONDS, TimeUnit.SECONDS);
                log.warn("Login throttled for 60s: IP={} user={} (after {} attempts)", ip, username, count);
            }
        }
    }

    /**
     * Clear attempts on successful login.
     */
    public void recordSuccess(String ip, String username) {
        String attemptKey = KEY_PREFIX + ip + ":" + username;
        String blockKey = BLOCK_PREFIX + ip + ":" + username;
        redisTemplate.delete(attemptKey);
        redisTemplate.delete(blockKey);
    }
}
