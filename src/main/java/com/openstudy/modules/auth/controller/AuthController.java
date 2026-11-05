package com.openstudy.modules.auth.controller;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ClientAddressResolver;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.dto.LoginRequest;
import com.openstudy.modules.auth.service.*;
import com.openstudy.modules.employee.service.EmployeeAccountService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;

    private final CurrentUserService currentUserService;
    private final EmployeeAccountService employeeAccountService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final SessionBoundaryPolicy sessionBoundaryPolicy;
    private final ClientAddressResolver clientAddressResolver;
    private final LoginAuditService loginAuditService;

    @GetMapping("/auth/csrf")
    public ResponseEntity<Map<String, String>> getCsrfToken(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("token", token.getToken()));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<CurrentUser> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        try {
            loginAttemptLimiter.check(
                    body.email(),
                    clientAddressResolver.resolve(request)
            );
        } catch (LoginRateLimitedException exception) {
            loginAuditService.limited(body.email(), exception, requestId);
            throw exception;
        }

        Long employeeId = null;

        try {
            if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw ApiException.validation(
                        "password",
                        "비밀번호는 UTF-8 72바이트 이하여야 합니다."
                );
            }

            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            body.email().trim(),
                            body.password()
                    )
            );

            employeeId = Long.parseLong(authentication.getName());
            CurrentUser currentUser = currentUserService.getCurrentUser(employeeId);

            employeeAccountService.recordLogin(employeeId);
            sessionStrategy.onAuthentication(authentication, request, response);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            sessionBoundaryPolicy.start(request.getSession(), currentUser.sessionMode());
            securityContextRepository.saveContext(context, request, response);

            loginAttemptLimiter.success(body.email());
            loginAuditService.succeeded(employeeId, requestId);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(currentUser);

        } catch (RuntimeException exception) {
            loginAuditService.failed(body.email(), employeeId, exception, requestId);
            throw exception;
        }
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUser> getCurrentUser(
            Authentication authentication,
            HttpServletRequest request
    ) {
        long employeeId = Long.parseLong(authentication.getName());
        String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));

        CurrentUser currentUser = currentUserService.getCurrentUser(employeeId, sessionMode);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentUser);
    }

    @PatchMapping("/me/profile")
    public ResponseEntity<CurrentUser> updateProfile(
            Authentication authentication,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.validation(
                    request.getParameterNames().nextElement(),
                    "허용되지 않은 쿼리입니다."
            );
        }

        long employeeId = Long.parseLong(authentication.getName());
        String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));
        String requestId = ApiRequestFilter.requestId(request);

        CurrentUser currentUser = currentUserService.updateProfile(
                employeeId,
                sessionMode,
                body,
                requestId
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentUser);
    }
}
