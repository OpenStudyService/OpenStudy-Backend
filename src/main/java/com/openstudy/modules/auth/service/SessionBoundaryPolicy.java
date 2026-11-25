package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.session.Session;

/** Authentication time and effective user activity survive JDBC session reloads. */
@RequiredArgsConstructor
public final class SessionBoundaryPolicy {

    public static final String AUTHENTICATED_AT = "authenticatedAt";
    public static final String LAST_ACTIVITY_AT = "lastEffectiveActivityAt";
    public static final String SESSION_MODE = "sessionMode";

    public static final String WORKSPACE = "WORKSPACE";
    public static final String APPROVAL_ONLY = "APPROVAL_ONLY";

    public static final long IDLE_SECONDS = 60 * 60;
    public static final long MAX_SECONDS = 12 * 60 * 60;

    /** Status and token refresh never count as user activity. */
    private static final Set<String> PASSIVE_PATHS = Set.of(
            "/api/me",
            "/api/lookups",
            "/api/invitations/approval-status",
            "/api/auth/csrf"
    );

    private static final Set<String> PASSIVE_METHODS = Set.of("HEAD", "OPTIONS");

    private final Clock clock;

    public void start(HttpSession session, String mode) {
        if (!isValidMode(mode)) {
            throw new IllegalArgumentException("Invalid session mode");
        }

        long now = nowEpochSecond();

        session.setAttribute(AUTHENTICATED_AT, now);
        session.setAttribute(LAST_ACTIVITY_AT, now);
        session.setAttribute(SESSION_MODE, mode);
        session.setMaxInactiveInterval((int) IDLE_SECONDS);
    }

    public String mode(HttpSession session) {
        return toValidMode(session.getAttribute(SESSION_MODE));
    }

    public boolean expired(HttpSession session) {
        return isExpired(
                session.getAttribute(AUTHENTICATED_AT),
                session.getAttribute(LAST_ACTIVITY_AT),
                mode(session)
        );
    }

    public boolean expired(Session session) {
        return isExpired(
                session.getAttribute(AUTHENTICATED_AT),
                session.getAttribute(LAST_ACTIVITY_AT),
                toValidMode(session.getAttribute(SESSION_MODE))
        );
    }

    public void recordActivity(HttpSession session) {
        session.setAttribute(LAST_ACTIVITY_AT, nowEpochSecond());
    }

    public boolean effectiveActivity(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (PASSIVE_PATHS.contains(path)) {
            return false;
        }

        return !PASSIVE_METHODS.contains(request.getMethod());
    }

    private boolean isExpired(Object authenticatedAt, Object lastActivityAt, String mode) {
        if (!(authenticatedAt instanceof Long startedAt)
                || !(lastActivityAt instanceof Long activeAt)
                || mode == null
        ) {
            return true;
        }

        long now = nowEpochSecond();

        boolean clockWentBack = now < startedAt || now < activeAt;
        boolean pastMaxLifetime = now - startedAt >= MAX_SECONDS;
        boolean pastIdleLimit = now - activeAt >= IDLE_SECONDS;

        return clockWentBack || pastMaxLifetime || pastIdleLimit;
    }

    private static boolean isValidMode(Object mode) {
        return WORKSPACE.equals(mode) || APPROVAL_ONLY.equals(mode);
    }

    private static String toValidMode(Object mode) {
        return isValidMode(mode) ? (String) mode : null;
    }

    private long nowEpochSecond() {
        return clock.instant().getEpochSecond();
    }
}
