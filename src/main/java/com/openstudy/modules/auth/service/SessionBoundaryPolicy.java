package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.util.Set;
import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Authentication time and effective user activity survive JDBC session reloads. */
public final class SessionBoundaryPolicy {
    public static final String AUTHENTICATED_AT = "authenticatedAt";
    public static final String LAST_ACTIVITY_AT = "lastEffectiveActivityAt";
    public static final String SESSION_MODE = "sessionMode";
    public static final String WORKSPACE = "WORKSPACE";
    public static final String APPROVAL_ONLY = "APPROVAL_ONLY";
    public static final long IDLE_SECONDS = 60 * 60;
    public static final long MAX_SECONDS = 12 * 60 * 60;
    private final Clock clock;

    public SessionBoundaryPolicy(Clock clock) { this.clock = clock; }

    public void start(HttpSession session, String mode) {
        if (!WORKSPACE.equals(mode) && !APPROVAL_ONLY.equals(mode)) throw new IllegalArgumentException("Invalid session mode");
        long now = clock.instant().getEpochSecond();
        session.setAttribute(AUTHENTICATED_AT, now);
        session.setAttribute(LAST_ACTIVITY_AT, now);
        session.setAttribute(SESSION_MODE, mode);
        session.setMaxInactiveInterval((int) IDLE_SECONDS);
    }

    public String mode(HttpSession session) {
        Object value = session.getAttribute(SESSION_MODE);
        return WORKSPACE.equals(value) || APPROVAL_ONLY.equals(value) ? (String) value : null;
    }

    public boolean expired(HttpSession session) {
        Object authenticatedAt = session.getAttribute(AUTHENTICATED_AT);
        Object lastActivityAt = session.getAttribute(LAST_ACTIVITY_AT);
        if (!(authenticatedAt instanceof Long started) || !(lastActivityAt instanceof Long active)
                || mode(session) == null) return true;
        long now = clock.instant().getEpochSecond();
        return now < started || now < active || now - started >= MAX_SECONDS || now - active >= IDLE_SECONDS;
    }

    public void recordActivity(HttpSession session) {
        session.setAttribute(LAST_ACTIVITY_AT, clock.instant().getEpochSecond());
    }

    public boolean effectiveActivity(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String accept = request.getHeader("Accept");
        // Streaming connections, reconnects and status/token refresh never count as user activity.
        if (path.equals("/api/events") || path.equals("/api/workspace/events") || path.equals("/api/me")
                || path.equals("/api/lookups")
                || path.equals("/api/invitations/approval-status") || path.equals("/api/auth/csrf")
                || (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream"))) return false;
        return !Set.of("HEAD", "OPTIONS").contains(request.getMethod());
    }
}
