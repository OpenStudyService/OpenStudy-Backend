package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LoginAttemptLimiterTests {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    }

    static final int ACCOUNT_MAX = LoginAttemptLimiter.ACCOUNT_DEFAULT.maxAttempts();
    static final int SOURCE_MAX = LoginAttemptLimiter.SOURCE_DEFAULT.maxAttempts();

    final MutableClock clock = new MutableClock();
    final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock,
            LoginAttemptLimiter.ACCOUNT_DEFAULT, LoginAttemptLimiter.SOURCE_DEFAULT);

    @Test
    void defaultsMatchTheDocumentedPolicy() {
        assertThat(LoginAttemptLimiter.ACCOUNT_DEFAULT).isEqualTo(new LoginAttemptLimiter.Policy(10, Duration.ofMinutes(15), 10_000));
        assertThat(LoginAttemptLimiter.SOURCE_DEFAULT).isEqualTo(new LoginAttemptLimiter.Policy(30, Duration.ofMinutes(15), 10_000));
        assertThatThrownBy(() -> new LoginAttemptLimiter.Policy(0, Duration.ofMinutes(15), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter.Policy(1, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsTenAttemptsPerAccountAcrossSourcesAndRejectsTheEleventh() {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "203.0.113." + i);
        }
        assertRateLimited("owner@example.com", "203.0.113.200", LoginAttemptLimiter.Scope.ACCOUNT);
    }

    @Test
    void sameSourceRotatingAccountsIsLimitedBySource() {
        for (int i = 0; i < SOURCE_MAX; i++) {
            limiter.check("user-" + i + "@example.com", "203.0.113.7");
        }
        assertRateLimited("fresh@example.com", "203.0.113.7", LoginAttemptLimiter.Scope.SOURCE);
        assertThatCode(() -> limiter.check("fresh@example.com", "203.0.113.8")).doesNotThrowAnyException();
    }

    @Test
    void rejectedAttemptCountsAgainstNeitherKey() {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "203.0.113.7");
        }
        for (int i = 0; i < 50; i++) {
            assertRateLimited("owner@example.com", "203.0.113.7", LoginAttemptLimiter.Scope.ACCOUNT);
        }
        for (int i = ACCOUNT_MAX; i < SOURCE_MAX; i++) {
            limiter.check("user-" + i + "@example.com", "203.0.113.7");
        }
        assertRateLimited("other@example.com", "203.0.113.7", LoginAttemptLimiter.Scope.SOURCE);
    }

    @Test
    void normalizesEmailSoCaseAndWhitespaceDoNotBypassTheLimit() {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check(i % 2 == 0 ? " Owner@Example.com " : "owner@example.com", "203.0.113." + i);
        }
        assertRateLimited("OWNER@example.COM", "203.0.113.99", LoginAttemptLimiter.Scope.ACCOUNT);
    }

    @Test
    void windowIsFixedFromTheFirstAttemptAndExpiresExactlyAtItsEnd() {
        limiter.check("owner@example.com", "203.0.113.7");
        clock.advance(Duration.ofMinutes(10));
        for (int i = 1; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "203.0.113.7");
        }
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertRateLimited("owner@example.com", "203.0.113.7", LoginAttemptLimiter.Scope.ACCOUNT);

        clock.advance(Duration.ofNanos(1));
        assertThatCode(() -> limiter.check("owner@example.com", "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void sourceWindowExpiresSoTheLastAdministratorCanLogInAgain() {
        for (int i = 0; i < SOURCE_MAX; i++) {
            limiter.check("user-" + i + "@example.com", "203.0.113.7");
        }
        assertRateLimited("owner@example.com", "203.0.113.7", LoginAttemptLimiter.Scope.SOURCE);
        clock.advance(LoginAttemptLimiter.SOURCE_DEFAULT.window());
        assertThatCode(() -> limiter.check("owner@example.com", "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void successClearsOnlyThatAccountAndNotTheSource() {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "203.0.113.7");
            limiter.check("staff@example.com", "203.0.113.8");
        }
        limiter.success("Owner@Example.com");

        assertThatCode(() -> limiter.check("owner@example.com", "203.0.113.9")).doesNotThrowAnyException();
        assertRateLimited("staff@example.com", "203.0.113.9", LoginAttemptLimiter.Scope.ACCOUNT);

        var shared = new LoginAttemptLimiter(clock, LoginAttemptLimiter.ACCOUNT_DEFAULT, new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 10));
        for (int i = 0; i < 3; i++) {
            shared.check("owner@example.com", "203.0.113.7");
            shared.success("owner@example.com");
        }
        assertThatThrownBy(() -> shared.check("owner@example.com", "203.0.113.7")).isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void onlyTheFirstRejectionInAWindowIsMarkedForAudit() {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "203.0.113." + i);
        }
        assertThat(rejection("owner@example.com", "203.0.113.50").firstRejection()).isTrue();
        assertThat(rejection("owner@example.com", "203.0.113.51").firstRejection()).isFalse();

        clock.advance(LoginAttemptLimiter.ACCOUNT_DEFAULT.window());
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            limiter.check("owner@example.com", "198.51.100." + i);
        }
        assertThat(rejection("owner@example.com", "198.51.100.50").firstRejection()).isTrue();
    }

    @Test
    void floodOfDistinctKeysAtCapacityDoesNotLockOutNewAccounts() {
        var small = new LoginAttemptLimiter(clock, new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 5),
                new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 5));
        for (int i = 0; i < 20; i++) {
            small.check("flood-" + i + "@example.com", "198.51.100." + i);
        }
        assertThat(small.size(LoginAttemptLimiter.Scope.ACCOUNT)).isEqualTo(5);
        assertThat(small.size(LoginAttemptLimiter.Scope.SOURCE)).isEqualTo(5);
        assertThatCode(() -> small.check("owner@example.com", "203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void evictionAtCapacityKeepsTheAccountUnderRepeatedAttack() {
        var small = new LoginAttemptLimiter(clock, new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 5),
                new LoginAttemptLimiter.Policy(1_000, Duration.ofMinutes(15), 1_000));
        for (int i = 0; i < 3; i++) {
            small.check("victim@example.com", "203.0.113.7");
        }
        for (int i = 0; i < 20; i++) {
            small.check("flood-" + i + "@example.com", "203.0.113.7");
        }
        assertThat(small.size(LoginAttemptLimiter.Scope.ACCOUNT)).isEqualTo(5);
        assertThatThrownBy(() -> small.check("victim@example.com", "203.0.113.8")).isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void expiredEntriesAreRemovedBeforeCapacityIsChecked() {
        var small = new LoginAttemptLimiter(clock, new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 5),
                new LoginAttemptLimiter.Policy(3, Duration.ofMinutes(15), 5));
        for (int i = 0; i < 5; i++) {
            small.check("old-" + i + "@example.com", "198.51.100." + i);
        }
        clock.advance(Duration.ofMinutes(15));
        small.check("owner@example.com", "203.0.113.7");
        assertThat(small.size(LoginAttemptLimiter.Scope.ACCOUNT)).isEqualTo(1);
        assertThat(small.size(LoginAttemptLimiter.Scope.SOURCE)).isEqualTo(1);
    }

    @Test
    void concurrentAttemptsNeverExceedTheLimit() throws Exception {
        int threads = 64;
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < threads; i++) {
            String source = "203.0.113." + i;
            tasks.add(() -> {
                start.await();
                try {
                    limiter.check("owner@example.com", source);
                    return true;
                } catch (LoginRateLimitedException exception) {
                    return false;
                }
            });
        }
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            long allowed = 0;
            for (var future : futures) if (future.get()) allowed++;
            assertThat(allowed).isEqualTo(ACCOUNT_MAX);
        }
    }

    private LoginRateLimitedException rejection(String email, String source) {
        return catchThrowableOfType(LoginRateLimitedException.class, () -> limiter.check(email, source));
    }

    private void assertRateLimited(String email, String source, LoginAttemptLimiter.Scope scope) {
        var exception = rejection(email, source);
        assertThat(exception).isNotNull();
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exception.scope()).isEqualTo(scope);
    }
}
