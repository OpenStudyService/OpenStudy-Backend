package com.openstudy.modules.auth.service;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.config.SecurityConfiguration;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.service.EmployeeAccountService;

@RequiredArgsConstructor
public class AccountSessionFilter extends OncePerRequestFilter {

    private static final String EMPLOYEE_ID_MDC_KEY = "employeeId";

    /** Paths an approval-only session may still call. */
    private static final Set<String> APPROVAL_ONLY_PATHS = Set.of(
            "/api/me",
            "/api/auth/csrf",
            "/api/auth/login",
            "/api/auth/logout",
            "/api/invitations/approval-status",
            "/api/invitations/preview"
    );

    private static final String APPROVAL_ONLY_PATH_PREFIX = "/api/password-resets/";

    private final EmployeeAccountService employeeAccountService;
    private final ObjectMapper objectMapper;
    private final SessionBoundaryPolicy sessionBoundaryPolicy;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (session == null || !isEmployee(authentication)) {
            chain.doFilter(request, response);
            return;
        }

        boolean expired = sessionBoundaryPolicy.expired(session);
        EmployeeAccount account = findAccount(authentication);
        String sessionMode = sessionBoundaryPolicy.mode(session);

        if (expired || !canKeepSession(account, sessionMode)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            SecurityConfiguration.error(request, response, objectMapper, 401, "SESSION_EXPIRED", "다시 로그인해 주세요.");
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (SessionBoundaryPolicy.APPROVAL_ONLY.equals(sessionMode) && !isApprovalOnlyPath(path)) {
            SecurityConfiguration.error(request, response, objectMapper, 403, "FORBIDDEN", "관리자 승인이 필요합니다.");
            return;
        }

        // #102: who made the request, for the request log line and any log written while it runs.
        request.setAttribute(ApiRequestFilter.EMPLOYEE, account.id());
        MDC.put(EMPLOYEE_ID_MDC_KEY, Long.toString(account.id()));

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(EMPLOYEE_ID_MDC_KEY);
        }

        if (response.getStatus() < 400 && sessionBoundaryPolicy.effectiveActivity(request)) {
            recordActivity(session);
        }
    }

    private static boolean isEmployee(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }

    /** A removed account must not keep an authenticated session, so a missing account is null here. */
    private EmployeeAccount findAccount(Authentication authentication) {
        try {
            return employeeAccountService.byId(Long.parseLong(authentication.getName()));
        } catch (UsernameNotFoundException | NumberFormatException ignored) {
            return null;
        }
    }

    /** A workspace session ends when its account goes back to pending approval. */
    private static boolean canKeepSession(EmployeeAccount account, String sessionMode) {
        if (account == null || !account.canLogin()) {
            return false;
        }

        boolean pendingInWorkspace = SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)
                && "PENDING".equals(account.status());

        return !pendingInWorkspace;
    }

    private static boolean isApprovalOnlyPath(String path) {
        return APPROVAL_ONLY_PATHS.contains(path) || path.startsWith(APPROVAL_ONLY_PATH_PREFIX);
    }

    private void recordActivity(HttpSession session) {
        try {
            sessionBoundaryPolicy.recordActivity(session);
        } catch (IllegalStateException ignored) {
            // Login/logout may have replaced the session.
        }
    }
}
