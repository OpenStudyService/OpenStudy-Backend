package com.openstudy.common.limit;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * A fixed-window attempt counter per key, moved out of the login limiter so other limits reuse it (#65, ADR-M5-001
 * "Rate Limiter 재사용 검토"). Not thread-safe: the owning limiter synchronizes. Per-instance memory, reset on restart.
 */
public final class FixedWindowCounter {
    private record Attempts(int count, Instant expiresAt, boolean reported) {}

    private final Map<String, Attempts> attempts = new HashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final int capacity;

    public FixedWindowCounter(int maxAttempts, Duration window, int capacity) {
        if (maxAttempts < 1 || window.isNegative() || window.isZero() || capacity < 1) {
            throw new IllegalArgumentException("Limit settings must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.capacity = capacity;
    }

    public void purge(Instant now) { attempts.values().removeIf(value -> !now.isBefore(value.expiresAt())); }

    public boolean exhausted(String key) {
        var current = attempts.get(key);
        return current != null && current.count() >= maxAttempts;
    }

    /** True the first time an exhausted key is reported in its window, so a flood is audited once. */
    public boolean markReported(String key) {
        var current = attempts.get(key);
        if (current.reported()) return false;
        attempts.put(key, new Attempts(current.count(), current.expiresAt(), true));
        return true;
    }

    public void record(String key, Instant now) {
        var current = attempts.get(key);
        if (current == null && attempts.size() >= capacity) {
            // Rejecting every new key here would let a flood of distinct keys lock out everyone,
            // so the entry carrying the least evidence of attack makes room instead.
            evictLeastAttempted();
        }
        attempts.put(key, current == null ? new Attempts(1, now.plus(window), false)
                : new Attempts(current.count() + 1, current.expiresAt(), false));
    }

    public void remove(String key) { attempts.remove(key); }

    public int size() { return attempts.size(); }

    private void evictLeastAttempted() {
        attempts.entrySet().stream()
                .min(Comparator.comparingInt((Map.Entry<String, Attempts> entry) -> entry.getValue().count())
                        .thenComparing(entry -> entry.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .ifPresent(attempts::remove);
    }
}
