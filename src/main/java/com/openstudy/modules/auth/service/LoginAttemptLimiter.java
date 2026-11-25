package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openstudy.common.limit.FixedWindowCounter;

/**
 * Per-instance guard. A shared store is required when scaling to multiple application instances.
 * State is lost on restart, which resets every window.
 */
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
    private final FixedWindowCounter accountCounter;
    private final FixedWindowCounter sourceCounter;

    @Autowired
    public LoginAttemptLimiter(
            Clock clock,
            @Value("${openstudy.auth.login-limit.account.max-attempts:10}") int accountMaxAttempts,
            @Value("${openstudy.auth.login-limit.account.window-minutes:15}") long accountWindowMinutes,
            @Value("${openstudy.auth.login-limit.source.max-attempts:30}") int sourceMaxAttempts,
            @Value("${openstudy.auth.login-limit.source.window-minutes:15}") long sourceWindowMinutes,
            @Value("${openstudy.auth.login-limit.capacity:10000}") int capacity
    ) {
        this(
                clock,
                new Policy(accountMaxAttempts, Duration.ofMinutes(accountWindowMinutes), capacity),
                new Policy(sourceMaxAttempts, Duration.ofMinutes(sourceWindowMinutes), capacity)
        );
    }

    LoginAttemptLimiter(Clock clock, Policy accountPolicy, Policy sourcePolicy) {
        this.clock = clock;
        this.accountCounter = createCounter(accountPolicy);
        this.sourceCounter = createCounter(sourcePolicy);
    }

    /** Counts the attempt against both keys only when neither is exhausted; a rejected attempt counts against neither. */
    public synchronized void check(String email, String source) {
        Instant now = clock.instant();
        String accountKey = normalize(email);

        accountCounter.purge(now);
        sourceCounter.purge(now);

        if (accountCounter.exhausted(accountKey)) {
            throw new LoginRateLimitedException(Scope.ACCOUNT, accountCounter.markReported(accountKey));
        }

        if (sourceCounter.exhausted(source)) {
            throw new LoginRateLimitedException(Scope.SOURCE, sourceCounter.markReported(source));
        }

        accountCounter.record(accountKey, now);
        sourceCounter.record(source, now);
    }

    /**
     * Clears the account only.
     * The source keeps counting so one valid login cannot reset a source spraying other accounts.
     */
    public synchronized void success(String email) {
        accountCounter.remove(normalize(email));
    }

    /**
     * Password change (#119): the account limit is exhausted. The check counts nothing;
     * only a wrong current password does, through {@link #accountFailure},
     * so a session holder cannot guess the password through this path either.
     */
    public synchronized boolean accountExhausted(String email) {
        accountCounter.purge(clock.instant());

        return accountCounter.exhausted(normalize(email));
    }

    /** One failed check of an account's current password, counted like a failed login on the account key only. */
    public synchronized void accountFailure(String email) {
        Instant now = clock.instant();

        accountCounter.purge(now);
        accountCounter.record(normalize(email), now);
    }

    /**
     * Login unlock and a completed reset (#119, M14-D06): clears the account key only; the source keeps counting.
     * Returns whether the account was locked out at that moment. This instance only (see the class comment).
     */
    public synchronized boolean clearAccount(String email) {
        String accountKey = normalize(email);

        accountCounter.purge(clock.instant());
        boolean locked = accountCounter.exhausted(accountKey);
        accountCounter.remove(accountKey);

        return locked;
    }

    /** The public reset-link endpoints count against the source key only, with the login limit (#119). */
    public synchronized void checkSource(String source) {
        Instant now = clock.instant();

        sourceCounter.purge(now);

        if (sourceCounter.exhausted(source)) {
            throw new LoginRateLimitedException(Scope.SOURCE, sourceCounter.markReported(source));
        }

        sourceCounter.record(source, now);
    }

    synchronized int size(Scope scope) {
        FixedWindowCounter counter = scope == Scope.ACCOUNT ? accountCounter : sourceCounter;

        return counter.size();
    }

    private static FixedWindowCounter createCounter(Policy policy) {
        return new FixedWindowCounter(policy.maxAttempts(), policy.window(), policy.capacity());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
