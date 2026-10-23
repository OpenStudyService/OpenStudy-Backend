package com.openstudy.modules.auth.service;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.config.SecurityConfiguration;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.service.EmployeeAccountService;

public class AccountSessionFilter extends OncePerRequestFilter {
    private final EmployeeAccountService employees;
    private final ObjectMapper json;
    private final SessionBoundaryPolicy sessions;

    public AccountSessionFilter(EmployeeAccountService employees, ObjectMapper json, SessionBoundaryPolicy sessions) {
        this.employees = employees;
        this.json = json;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            boolean expired = sessions.expired(session);
            EmployeeAccount account = null;
            try {
                account = employees.byId(Long.parseLong(authentication.getName()));
            } catch (UsernameNotFoundException | NumberFormatException ignored) {
                // A removed account must not keep an authenticated session.
            }
            if (expired || account == null || !account.canLogin()
                    || (SessionBoundaryPolicy.WORKSPACE.equals(sessions.mode(session)) && "PENDING".equals(account.status()))) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                SecurityConfiguration.error(request, response, json, 401, "SESSION_EXPIRED", "다시 로그인해 주세요.");
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (SessionBoundaryPolicy.APPROVAL_ONLY.equals(sessions.mode(session))
                    && !path.equals("/api/me") && !path.equals("/api/auth/csrf")
                    && !path.equals("/api/auth/login") && !path.equals("/api/auth/logout")
                    && !path.equals("/api/invitations/approval-status")) {
                SecurityConfiguration.error(request, response, json, 403, "FORBIDDEN", "관리자 승인이 필요합니다.");
                return;
            }
            chain.doFilter(request, response);
            if (response.getStatus() < 400 && sessions.effectiveActivity(request)) {
                try { sessions.recordActivity(session); }
                catch (IllegalStateException ignored) { /* Login/logout may have replaced the session. */ }
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
