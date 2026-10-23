package com.openstudy.modules.auth.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// Per-instance guard. A shared store is required when scaling to multiple application instances.
@Service
public class LoginAttemptLimiter {
    private record Attempts(int count, Instant expiresAt) {}
    private final Map<String, Attempts> attempts = new HashMap<>();

    public synchronized void check(String email) {
        Instant now = Instant.now();
        attempts.values().removeIf(value -> !now.isBefore(value.expiresAt()));
        String key = email.trim().toLowerCase(Locale.ROOT);
        var current = attempts.get(key);
        if ((current != null && current.count() >= 10) || (current == null && attempts.size() >= 10_000)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        attempts.put(key, current == null ? new Attempts(1, now.plusSeconds(900))
                : new Attempts(current.count() + 1, current.expiresAt()));
    }

    public synchronized void success(String email) {
        attempts.remove(email.trim().toLowerCase(Locale.ROOT));
    }
}
