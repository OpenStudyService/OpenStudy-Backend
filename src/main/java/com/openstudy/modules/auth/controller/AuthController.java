package com.openstudy.modules.auth.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.auth.dto.LoginRequest;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.auth.service.LoginAttemptLimiter;
import com.openstudy.modules.auth.service.LoginAuditService;
import com.openstudy.modules.auth.service.LoginRateLimitedException;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ClientAddressResolver;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final CurrentUserService users;
    private final EmployeeAccountService employees;
    private final LoginAttemptLimiter limiter;
    private final SessionBoundaryPolicy sessions;
    private final ClientAddressResolver clients;
    private final LoginAuditService audit;

    @GetMapping("/api/auth/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token", token.getToken()));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<CurrentUser> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String requestId = ApiRequestFilter.requestId(request);
        try {
            limiter.check(body.email(), clients.resolve(request));
        } catch (LoginRateLimitedException exception) {
            audit.limited(body.email(), exception, requestId);
            throw exception;
        }
        Long employeeId = null;
        try {
            if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw ApiException.validation("password", "비밀번호는 UTF-8 72바이트 이하여야 합니다.");
            }
            var authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.email().trim(), body.password()));
            employeeId = Long.parseLong(authentication.getName());
            var current = users.current(employeeId);
            employees.recordLogin(employeeId);
            sessionStrategy.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            sessions.start(request.getSession(), current.sessionMode());
            contexts.saveContext(context, request, response);
            limiter.success(body.email());
            audit.succeeded(employeeId, requestId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(current);
        } catch (RuntimeException exception) {
            audit.failed(body.email(), employeeId, exception, requestId);
            throw exception;
        }
    }

    @GetMapping("/api/me")
    public ResponseEntity<CurrentUser> me(Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.current(Long.parseLong(authentication.getName()), sessions.mode(request.getSession(false))));
    }

    @PatchMapping("/api/me/profile")
    public ResponseEntity<CurrentUser> updateProfile(Authentication authentication, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.validation(request.getParameterNames().nextElement(), "허용되지 않은 쿼리입니다.");
        }
        var current = users.updateProfile(Long.parseLong(authentication.getName()), sessions.mode(request.getSession(false)),
                body, ApiRequestFilter.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(current);
    }
}
