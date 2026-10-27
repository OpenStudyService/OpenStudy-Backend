package com.openstudy.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.auth.repository.LoginAuditRepository;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/**
 * One audit row per login attempt, named by its outcome: LOGIN_SUCCEEDED, LOGIN_FAILED or LOGIN_RATE_LIMITED.
 * Writes run outside any transaction so a failed login still leaves its row, and a failed write is logged
 * without changing the login result. Rate-limited attempts write only the first rejection per key and window.
 */
@Service
public class LoginAuditService {
    public static final String SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String FAILED = "LOGIN_FAILED";
    public static final String RATE_LIMITED = "LOGIN_RATE_LIMITED";
    private static final Logger log = LoggerFactory.getLogger(LoginAuditService.class);
    private final LoginAuditRepository repository;
    private final EmployeeAccountService employees;

    public LoginAuditService(LoginAuditRepository repository, EmployeeAccountService employees) {
        this.repository = repository;
        this.employees = employees;
    }

    public void succeeded(long employeeId, String requestId) {
        write(employeeId, employeeId, SUCCEEDED, null, "SUCCESS", requestId);
    }

    /** @param authenticated set when credentials were accepted but a later step failed */
    public void failed(String email, Long authenticated, RuntimeException cause, String requestId) {
        Long target = authenticated != null ? authenticated : target(email);
        write(null, target, FAILED, reason(cause), "FAILED", requestId);
    }

    public void limited(String email, LoginRateLimitedException exception, String requestId) {
        if (!exception.firstRejection()) return;
        boolean account = exception.scope() == LoginAttemptLimiter.Scope.ACCOUNT;
        write(null, account ? target(email) : null, RATE_LIMITED, account ? "ACCOUNT_LIMIT" : "SOURCE_LIMIT",
                "DENIED", requestId);
    }

    private Long target(String email) {
        try {
            return employees.byEmail(email).id();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String reason(RuntimeException cause) {
        if (cause instanceof BadCredentialsException) return "BAD_CREDENTIALS";
        if (cause instanceof DisabledException) return "ACCOUNT_DISABLED";
        if (cause instanceof LockedException) return "ACCOUNT_LOCKED";
        if (cause instanceof AccountExpiredException || cause instanceof CredentialsExpiredException) return "ACCOUNT_EXPIRED";
        if (cause instanceof AuthenticationException) return "AUTHENTICATION_FAILED";
        if (cause instanceof ApiException) return "INVALID_INPUT";
        return "ERROR";
    }

    private void write(Long actor, Long target, String action, String reason, String result, String requestId) {
        try {
            repository.record(actor, target, action, reason, result, requestId);
        } catch (RuntimeException exception) {
            // Exception text can carry SQL or bound values; log the type only.
            log.error("Login audit write failed requestId={} action={} type={}", requestId, action, exception.getClass().getName());
        }
    }
}
