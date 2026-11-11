package com.openstudy.modules.seat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #65: the check-in lookup limit per employee and branch, on the counter shared with the login limit. */
class CheckInLookupLimiterTests {
    static final Instant START = Instant.parse("2026-11-11T00:00:00Z");

    static final class MovableClock extends Clock {
        Instant now = START;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void theLimitIsPerEmployeeAndBranchAndAFullWindowResetsIt() {
        var clock = new MovableClock();
        var limiter = new CheckInLookupLimiter(clock, 60, 10, 10_000);
        for (int i = 0; i < 60; i++) assertThat(limiter.check(7, 1)).as("lookup %d", i + 1).isNull();
        var first = limiter.check(7, 1);
        assertThat(first).isNotNull();
        assertThat(first.firstInWindow()).isTrue();
        assertThat(limiter.check(7, 1).firstInWindow()).as("reported once per window").isFalse();
        // Another branch, or another employee, has its own count.
        assertThat(limiter.check(7, 2)).isNull();
        assertThat(limiter.check(8, 1)).isNull();
        // The window runs from the first lookup; once it has passed the employee may look up again.
        clock.now = START.plus(Duration.ofMinutes(10));
        assertThat(limiter.check(7, 1)).isNull();
    }

    @Test void settingsMustBePositive() {
        assertThatThrownBy(() -> new CheckInLookupLimiter(new MovableClock(), 0, 10, 10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckInLookupLimiter(new MovableClock(), 60, 0, 10_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
