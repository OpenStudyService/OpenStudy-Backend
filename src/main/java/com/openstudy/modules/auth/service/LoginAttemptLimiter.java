package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// Per-instance guard. A shared store is required when scaling to multiple application instances.
@Service
public class LoginAttemptLimiter {
    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int CAPACITY = 10_000;

    private record Attempts(int count, Instant expiresAt) {}
    private final Map<String, Attempts> attempts = new HashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;
    private final int capacity;

    @Autowired
    public LoginAttemptLimiter(Clock clock) { this(clock, MAX_ATTEMPTS, WINDOW, CAPACITY); }

    LoginAttemptLimiter(Clock clock, int maxAttempts, Duration window, int capacity) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.capacity = capacity;
    }

    public synchronized void check(String email) {
        Instant now = clock.instant();
        attempts.values().removeIf(value -> !now.isBefore(value.expiresAt()));
        String key = email.trim().toLowerCase(Locale.ROOT);
        var current = attempts.get(key);
        if (current != null && current.count() >= maxAttempts) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (current == null && attempts.size() >= capacity) {
            // Rejecting every new key here would let a flood of distinct emails lock out all accounts,
            // so the entry carrying the least evidence of attack makes room instead.
            evictLeastAttempted();
        }
        attempts.put(key, current == null ? new Attempts(1, now.plus(window))
                : new Attempts(current.count() + 1, current.expiresAt()));
    }

    public synchronized void success(String email) {
        attempts.remove(email.trim().toLowerCase(Locale.ROOT));
    }

    synchronized int size() { return attempts.size(); }

    private void evictLeastAttempted() {
        attempts.entrySet().stream()
                .min(Comparator.comparingInt((Map.Entry<String, Attempts> entry) -> entry.getValue().count())
                        .thenComparing(entry -> entry.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .ifPresent(attempts::remove);
    }
}
