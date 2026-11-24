package com.example.ussdGateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simple sliding-window rate limiter for SSH connection attempts.
 * Limits each user to a maximum number of connection attempts within a time window.
 * Prevents brute-force attacks against target SSH servers.
 */
@Slf4j
@Service
public class SshRateLimiter {

    private static final int MAX_ATTEMPTS = 10;         // Max connection attempts
    private static final long WINDOW_MS = 60_000;       // Per 60-second window

    private final Map<String, Queue<Long>> attemptsByUser = new ConcurrentHashMap<>();

    /**
     * Check if a connection attempt is allowed for the given username.
     * @return true if allowed, false if rate-limited
     */
    public boolean tryAcquire(String username) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        Queue<Long> attempts = attemptsByUser.computeIfAbsent(username, k -> new ConcurrentLinkedQueue<>());

        // Evict expired entries
        while (!attempts.isEmpty() && attempts.peek() < windowStart) {
            attempts.poll();
        }

        if (attempts.size() >= MAX_ATTEMPTS) {
            log.warn("Rate limit exceeded for user '{}': {} attempts in the last {}s",
                    username, attempts.size(), WINDOW_MS / 1000);
            return false;
        }

        attempts.add(now);
        return true;
    }

    /**
     * Get remaining attempts for a user in the current window.
     */
    public int remainingAttempts(String username) {
        long windowStart = System.currentTimeMillis() - WINDOW_MS;
        Queue<Long> attempts = attemptsByUser.get(username);
        if (attempts == null) return MAX_ATTEMPTS;

        // Count non-expired entries
        long activeAttempts = attempts.stream().filter(t -> t >= windowStart).count();
        return Math.max(0, MAX_ATTEMPTS - (int) activeAttempts);
    }
}

