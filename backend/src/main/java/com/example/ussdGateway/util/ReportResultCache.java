package com.example.ussdGateway.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Small in-process TTL cache with single-flight loading: concurrent requests for the same key
 * share one computation instead of each hitting the database.
 */
@org.springframework.stereotype.Component
public class ReportResultCache {

    private static final int MAX_ENTRIES = 500;

    private record Entry(FutureTask<Object> task, long expiresAt) {}

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlMillis, Callable<T> loader) {
        long now = System.currentTimeMillis();
        Entry entry = entries.get(key);
        boolean owner = false;
        if (entry == null || entry.expiresAt() < now) {
            if (entries.size() >= MAX_ENTRIES) {
                evict(now);
            }
            Entry fresh = new Entry(new FutureTask<>((Callable<Object>) loader), now + ttlMillis);
            Entry previous = (entry == null)
                    ? entries.putIfAbsent(key, fresh)
                    : (entries.replace(key, entry, fresh) ? null : entries.get(key));
            if (previous == null) {
                entry = fresh;
                owner = true;
            } else {
                entry = previous;
            }
        }
        if (owner) {
            entry.task().run();
        }
        try {
            return (T) entry.task().get();
        } catch (ExecutionException e) {
            entries.remove(key, entry);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for report result", e);
        }
    }

    /** Returns a completed, non-expired value or null (never triggers a load). */
    @SuppressWarnings("unchecked")
    public <T> T peek(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis() || !entry.task().isDone()) {
            return null;
        }
        try {
            return (T) entry.task().get();
        } catch (Exception e) {
            return null;
        }
    }

    /** Drops every cached result (called when the underlying data changed). */
    public void clear() {
        entries.clear();
    }

    private void evict(long now) {
        entries.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
    }
}
