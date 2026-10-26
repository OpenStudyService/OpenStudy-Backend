package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTests {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    }

    final MutableClock clock = new MutableClock();
    final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void allowsTenAttemptsWithinTheWindowAndRejectsTheEleventh() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.check("owner@example.com");
        }
        assertRateLimited("owner@example.com");
    }

    @Test
    void normalizesEmailSoCaseAndWhitespaceDoNotBypassTheLimit() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.check(i % 2 == 0 ? " Owner@Example.com " : "owner@example.com");
        }
        assertRateLimited("OWNER@example.COM");
    }

    @Test
    void windowIsFixedFromTheFirstAttemptAndExpiresExactlyAtItsEnd() {
        limiter.check("owner@example.com");
        clock.advance(Duration.ofMinutes(10));
        for (int i = 1; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.check("owner@example.com");
        }
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertRateLimited("owner@example.com");

        clock.advance(Duration.ofNanos(1));
        assertThatCode(() -> limiter.check("owner@example.com")).doesNotThrowAnyException();
    }

    @Test
    void successClearsOnlyThatAccount() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.check("owner@example.com");
            limiter.check("staff@example.com");
        }
        limiter.success("Owner@Example.com");

        assertThatCode(() -> limiter.check("owner@example.com")).doesNotThrowAnyException();
        assertRateLimited("staff@example.com");
    }

    @Test
    void floodOfDistinctEmailsAtCapacityDoesNotLockOutNewAccounts() {
        var small = new LoginAttemptLimiter(clock, 3, Duration.ofMinutes(15), 5);
        for (int i = 0; i < 20; i++) {
            small.check("flood-" + i + "@example.com");
        }
        assertThat(small.size()).isEqualTo(5);
        assertThatCode(() -> small.check("owner@example.com")).doesNotThrowAnyException();
    }

    @Test
    void evictionAtCapacityKeepsTheAccountUnderRepeatedAttack() {
        var small = new LoginAttemptLimiter(clock, 3, Duration.ofMinutes(15), 5);
        for (int i = 0; i < 3; i++) {
            small.check("victim@example.com");
        }
        for (int i = 0; i < 20; i++) {
            small.check("flood-" + i + "@example.com");
        }
        assertThat(small.size()).isEqualTo(5);
        assertThatThrownBy(() -> small.check("victim@example.com")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiredEntriesAreRemovedBeforeCapacityIsChecked() {
        var small = new LoginAttemptLimiter(clock, 3, Duration.ofMinutes(15), 5);
        for (int i = 0; i < 5; i++) {
            small.check("old-" + i + "@example.com");
        }
        clock.advance(Duration.ofMinutes(15));
        small.check("owner@example.com");
        assertThat(small.size()).isEqualTo(1);
    }

    private void assertRateLimited(String email) {
        assertThatThrownBy(() -> limiter.check(email))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
