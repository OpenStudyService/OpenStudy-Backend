package com.openstudy.modules.authorization.service;

import java.util.Set;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;

/** Checks present handlers on every request. Future writes must ALSO use AuthorizedCommands atomically. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AuthorizationWebConfiguration implements WebMvcConfigurer {

    private static final String AUTHORIZED_BRANCH_IDS = "authorizedBranchIds";

    /** Self-account endpoints have no branch scope; session and command boundaries guard profile writes. */
    private static final Set<String> SELF_ACCOUNT_PATHS = Set.of(
            "/api/me",
            "/api/me/profile",
            "/api/me/password",
            "/api/me/avatar"
    );
    private static final Set<String> LINK_HOLDER_PATHS = Set.of("/api/invitations/preview", "/api/invitations/accept");

    private final AuthorizationService authorizationService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                authorize(request);
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    private void authorize(HttpServletRequest request) {
        // An async redispatch only completes a response whose access was checked on the initial request.
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal());

        if (anonymous) {
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (isOutsideBranchScope(path, request.getMethod())) {
            return;
        }

        AuthorizationState state = authorizationService.current(Long.parseLong(authentication.getName()));
        String scope = request.getParameter("scope");
        authorizationService.scope(state, scope, BranchAction.READ);
        BranchAction action = toAction(path, request.getMethod());

        // all is a union, but an administrator endpoint is never a STAFF endpoint, even with an empty union.
        boolean administratorEndpoint = action != BranchAction.READ;

        if (administratorEndpoint && authorizationService.scope(state, scope, action).isEmpty() && !state.owner()) {
            throw AuthorizationService.forbidden();
        }

        request.setAttribute(AUTHORIZED_BRANCH_IDS, authorizationService.scope(state, scope, action));
    }

    private static boolean isOutsideBranchScope(String path, String method) {
        boolean selfAccount = SELF_ACCOUNT_PATHS.contains(path)
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/invitations/approval-status");

        if (selfAccount) {
            return true;
        }

        // Link-holder endpoints are self commands verified by token and own session, not branch administration.
        // A colleague's photo is decided by the photo service (M14-D14), not by people administration.
        if ("GET".equals(method) && path.matches("/api/employees/[0-9]+/avatar")) {
            return true;
        }

        return LINK_HOLDER_PATHS.contains(path) || path.startsWith("/api/password-resets/");
    }

    private static BranchAction toAction(String path, String method) {
        // A leave balance is read by the MANAGER role alone (M12, workforce-requests-api.md "잔여량 조회"); the
        // leave service decides who sees whose, so it is not people administration.
        boolean leaveBalance = path.matches("/api/employees/[0-9]+/leave-balance");

        if (!leaveBalance && path.matches("/api/(employees|invitations)(/.*)?")) {
            return BranchAction.MANAGE_PEOPLE;
        }

        if (path.matches("/api/payments(/.*)?")) {
            // Selling and refunding need payments:write only; a cashier need not read the payment ledger.
            return "POST".equals(method) ? BranchAction.WRITE_PAYMENT_HISTORY : BranchAction.READ_PAYMENTS;
        }

        if (path.matches("/api/sales(/.*)?")) {
            return BranchAction.READ_SALES;
        }

        if (path.matches("/api/ai(/.*)?")) {
            return BranchAction.READ_AI;
        }

        return BranchAction.READ;
    }
}
