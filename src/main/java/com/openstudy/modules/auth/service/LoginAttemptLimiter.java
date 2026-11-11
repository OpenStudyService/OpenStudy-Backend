package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.openstudy.common.limit.FixedWindowCounter;

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
    private final FixedWindowCounter accounts;
    private final FixedWindowCounter sources;

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
        this.accounts = new FixedWindowCounter(account.maxAttempts(), account.window(), account.capacity());
        this.sources = new FixedWindowCounter(source.maxAttempts(), source.window(), source.capacity());
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

    /**
     * Password change (#119): the account limit is exhausted. The check counts nothing; only a wrong current password does,
     * through {@link #accountFailure}, so a session holder cannot guess the password through this path either.
     */
    public synchronized boolean accountExhausted(String email) {
        accounts.purge(clock.instant());
        return accounts.exhausted(normalize(email));
    }

    /** One failed check of an account's current password, counted like a failed login on the account key only. */
    public synchronized void accountFailure(String email) {
        Instant now = clock.instant();
        accounts.purge(now);
        accounts.record(normalize(email), now);
    }

    /**
     * Login unlock and a completed reset (#119, M14-D06): clears the account key only; the source keeps counting.
     * Returns whether the account was locked out at that moment. This instance only (see the class comment).
     */
    public synchronized boolean clearAccount(String email) {
        String account = normalize(email);
        accounts.purge(clock.instant());
        boolean locked = accounts.exhausted(account);
        accounts.remove(account);
        return locked;
    }

    /** The public reset-link endpoints count against the source key only, with the login limit (#119). */
    public synchronized void checkSource(String source) {
        Instant now = clock.instant();
        sources.purge(now);
        if (sources.exhausted(source)) throw new LoginRateLimitedException(Scope.SOURCE, sources.markReported(source));
        sources.record(source, now);
    }

    synchronized int size(Scope scope) { return (scope == Scope.ACCOUNT ? accounts : sources).size(); }

    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
