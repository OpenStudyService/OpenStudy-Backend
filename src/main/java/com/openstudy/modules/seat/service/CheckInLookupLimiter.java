package com.openstudy.modules.seat.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openstudy.common.limit.FixedWindowCounter;

/**
 * #65 (ADR-M5-001 M5-D08): the exact-match check-in lookup reaches customers of every branch, so one employee at one
 * branch may only run it so often (60 per 10 minutes, 2026-11-11 user decision). Per-instance memory like the login
 * limit; a shared store is needed when the server runs as several instances.
 */
@Service
public class CheckInLookupLimiter {
    /** What a refused lookup tells its caller: whether this is the first refusal of the window, to audit it once. */
    public record Refusal(boolean firstInWindow) {}

    private final Clock clock;
    private final FixedWindowCounter lookups;

    public CheckInLookupLimiter(Clock clock,
            @Value("${openstudy.customer-lookup-limit.max-attempts:60}") int maxAttempts,
            @Value("${openstudy.customer-lookup-limit.window-minutes:10}") long windowMinutes,
            @Value("${openstudy.customer-lookup-limit.capacity:10000}") int capacity) {
        this.clock = clock;
        this.lookups = new FixedWindowCounter(maxAttempts, Duration.ofMinutes(windowMinutes), capacity);
    }

    /** Counts the lookup and returns null, or returns the refusal without counting it. */
    public synchronized Refusal check(long employee, long branch) {
        var now = clock.instant();
        String key = employee + ":" + branch;
        lookups.purge(now);
        if (lookups.exhausted(key)) return new Refusal(lookups.markReported(key));
        lookups.record(key, now);
        return null;
    }
}
