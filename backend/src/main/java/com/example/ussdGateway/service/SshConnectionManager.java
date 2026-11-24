package com.example.ussdGateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-host SSH connection limits to prevent flooding remote USSD gateways.
 * A centralized semaphore map guarantees that no matter how many concurrent
 * requests are made (reports, traces, searches), a single IP will never have
 * more than the defined maximum concurrent connections.
 */
@Slf4j
@Service
public class SshConnectionManager {

    // Maximum concurrent SSH sessions allowed per remote IP address
    private static final int MAX_CONCURRENT_CONNECTIONS_PER_HOST = 2;
    // How long to wait for a connection permit before giving up
    private static final int ACQUIRE_TIMEOUT_SECONDS = 15;

    // Map of IP Address -> Semaphore
    private final Map<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire an SSH connection permit for the given host.
     * Blocks for up to ACQUIRE_TIMEOUT_SECONDS.
     *
     * @param serverIp The IP address of the target server
     * @throws RuntimeException if a permit cannot be acquired within the timeout
     */
    public void acquireConnection(String serverIp) {
        Semaphore sem = hostSemaphores.computeIfAbsent(serverIp, k -> new Semaphore(MAX_CONCURRENT_CONNECTIONS_PER_HOST));

        try {
            log.debug("Attempting to acquire SSH connection permit for {}. Available permits: {}", serverIp, sem.availablePermits());
            boolean acquired = sem.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.error("Failed to acquire SSH connection permit for {} after {} seconds. The server is busy.", serverIp, ACQUIRE_TIMEOUT_SECONDS);
                throw new RuntimeException("Server " + serverIp + " is currently processing the maximum number of requests. Please try again later.");
            }
            log.debug("Successfully acquired SSH permit for {}. Remaining permits: {}", serverIp, sem.availablePermits());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for SSH connection permit to " + serverIp, e);
        }
    }

    /**
     * Releases an SSH connection permit for the given host.
     * MUST be called in a finally block after acquireConnection.
     *
     * @param serverIp The IP address of the target server
     */
    public void releaseConnection(String serverIp) {
        Semaphore sem = hostSemaphores.get(serverIp);
        if (sem != null) {
            sem.release();
            log.debug("Released SSH permit for {}. Available permits: {}", serverIp, sem.availablePermits());
        }
    }
}
