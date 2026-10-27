package com.openstudy.modules.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Mapped to the common 429 body; scope is for audit only and never reaches the client. */
public class LoginRateLimitedException extends ResponseStatusException {
    private final LoginAttemptLimiter.Scope scope;
    private final boolean firstRejection;

    LoginRateLimitedException(LoginAttemptLimiter.Scope scope, boolean firstRejection) {
        super(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.");
        this.scope = scope;
        this.firstRejection = firstRejection;
    }

    public LoginAttemptLimiter.Scope scope() { return scope; }

    /** True only for the first rejection of a key within its window, so a flood writes one audit row. */
    public boolean firstRejection() { return firstRejection; }
}
