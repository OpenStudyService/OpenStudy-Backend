package com.openstudy.modules.auth.service;

import java.io.IOException;
import java.time.Instant;

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

    public AccountSessionFilter(EmployeeAccountService employees, ObjectMapper json) {
        this.employees = employees;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            Object authenticatedAt = session.getAttribute("authenticatedAt");
            boolean expired = !(authenticatedAt instanceof Long started)
                    || Instant.now().getEpochSecond() - started >= 12 * 60 * 60;
            EmployeeAccount account = null;
            try {
                account = employees.byId(Long.parseLong(authentication.getName()));
            } catch (UsernameNotFoundException | NumberFormatException ignored) {
                // A removed account must not keep an authenticated session.
            }
            if (expired || account == null || !account.canLogin()) {
                session.invalidate();
                SecurityContextHolder.clearContext();
                SecurityConfiguration.error(request, response, json, 401, "SESSION_EXPIRED", "다시 로그인해 주세요.");
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if ("PENDING".equals(account.status()) && !path.equals("/api/me") && !path.equals("/api/auth/csrf")
                    && !path.equals("/api/auth/login")) {
                SecurityConfiguration.error(request, response, json, 403, "FORBIDDEN", "관리자 승인이 필요합니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
