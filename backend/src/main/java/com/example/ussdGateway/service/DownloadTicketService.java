package com.example.ussdGateway.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived download tickets so the browser can download a large export natively
 * (streamed to disk) without putting the JWT in the URL. A ticket is bound to one job and user.
 */
@Service
public class DownloadTicketService {

    private static final long TTL_MILLIS = 15 * 60 * 1000L;

    public record Ticket(String jobId, String username, String authority, long expiresAt) {}

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String issue(String jobId, String username, String authority) {
        purgeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new Ticket(jobId.toLowerCase(), username, authority, System.currentTimeMillis() + TTL_MILLIS));
        return ticket;
    }

    public Optional<Ticket> resolve(String ticket, String jobId) {
        Ticket t = tickets.get(ticket);
        if (t == null || t.expiresAt() < System.currentTimeMillis() || !t.jobId().equalsIgnoreCase(jobId)) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    public long ttlSeconds() {
        return TTL_MILLIS / 1000;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        tickets.values().removeIf(t -> t.expiresAt() < now);
    }
}
