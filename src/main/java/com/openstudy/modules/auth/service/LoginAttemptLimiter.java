package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Per-instance guard. A shared store is required when scaling to multiple application instances.
// State is lost on restart, which resets every window.
@Service
public class LoginAttemptLimiter {
    public enum Scope { ACCOUNT, SOURCE }

    record Policy(int maxAttempts, Duration window, int capacity) {
        Policy {
            if (maxAttempts < 1 || window.isNegative() || window.isZero() || capacity < 1) {
                throw new IllegalArgumentException("Login limit settings must be positive");
            }
        }
    }

    static final Policy ACCOUNT_DEFAULT = new Policy(10, Duration.ofMinutes(15), 10_000);
    static final Policy SOURCE_DEFAULT = new Policy(30, Duration.ofMinutes(15), 10_000);

    private final Clock clock;
    private final Window accounts;
    private final Window sources;

    @Autowired
    public LoginAttemptLimiter(Clock clock,
            @Value("${openstudy.auth.login-limit.account.max-attempts:10}") int accountMaxAttempts,
            @Value("${openstudy.auth.login-limit.account.window-minutes:15}") long accountWindowMinutes,
            @Value("${openstudy.auth.login-limit.source.max-attempts:30}") int sourceMaxAttempts,
            @Value("${openstudy.auth.login-limit.source.window-minutes:15}") long sourceWindowMinutes,
            @Value("${openstudy.auth.login-limit.capacity:10000}") int capacity) {
        this(clock, new Policy(accountMaxAttempts, Duration.ofMinutes(accountWindowMinutes), capacity),
                new Policy(sourceMaxAttempts, Duration.ofMinutes(sourceWindowMinutes), capacity));
    }

    LoginAttemptLimiter(Clock clock, Policy account, Policy source) {
        this.clock = clock;
        this.accounts = new Window(account);
        this.sources = new Window(source);
    }

    /** Counts the attempt against both keys only when neither is exhausted; a rejected attempt counts against neither. */
    public synchronized void check(String email, String source) {
        Instant now = clock.instant();
        String account = normalize(email);
        accounts.purge(now);
        sources.purge(now);
        if (accounts.exhausted(account)) throw new LoginRateLimitedException(Scope.ACCOUNT, accounts.markReported(account));
        if (sources.exhausted(source)) throw new LoginRateLimitedException(Scope.SOURCE, sources.markReported(source));
        accounts.record(account, now);
        sources.record(source, now);
    }

    /** Clears the account only. The source keeps counting so one valid login cannot reset a source spraying other accounts. */
    public synchronized void success(String email) {
        accounts.remove(normalize(email));
    }

    synchronized int size(Scope scope) { return (scope == Scope.ACCOUNT ? accounts : sources).attempts.size(); }

    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    private static final class Window {
        private record Attempts(int count, Instant expiresAt, boolean reported) {}
        private final Map<String, Attempts> attempts = new HashMap<>();
        private final Policy policy;

        Window(Policy policy) { this.policy = policy; }

        void purge(Instant now) { attempts.values().removeIf(value -> !now.isBefore(value.expiresAt())); }

        boolean exhausted(String key) {
            var current = attempts.get(key);
            return current != null && current.count() >= policy.maxAttempts();
        }

        boolean markReported(String key) {
            var current = attempts.get(key);
            if (current.reported()) return false;
            attempts.put(key, new Attempts(current.count(), current.expiresAt(), true));
            return true;
        }

        void record(String key, Instant now) {
            var current = attempts.get(key);
            if (current == null && attempts.size() >= policy.capacity()) {
                // Rejecting every new key here would let a flood of distinct keys lock out everyone,
                // so the entry carrying the least evidence of attack makes room instead.
                evictLeastAttempted();
            }
            attempts.put(key, current == null ? new Attempts(1, now.plus(policy.window()), false)
                    : new Attempts(current.count() + 1, current.expiresAt(), false));
        }

        void remove(String key) { attempts.remove(key); }

        private void evictLeastAttempted() {
            attempts.entrySet().stream()
                    .min(Comparator.comparingInt((Map.Entry<String, Attempts> entry) -> entry.getValue().count())
                            .thenComparing(entry -> entry.getValue().expiresAt()))
                    .map(Map.Entry::getKey)
                    .ifPresent(attempts::remove);
        }
    }
}
