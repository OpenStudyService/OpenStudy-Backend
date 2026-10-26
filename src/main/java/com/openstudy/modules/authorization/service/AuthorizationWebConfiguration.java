package com.openstudy.modules.authorization.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.openstudy.modules.authorization.dto.BranchAction;

/** Checks present handlers on every request. Future writes must ALSO use AuthorizedCommands atomically. */
@Configuration(proxyBeanMethods = false)
public class AuthorizationWebConfiguration implements WebMvcConfigurer {
    private final AuthorizationService authorization;
    public AuthorizationWebConfiguration(AuthorizationService authorization) { this.authorization = authorization; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !authentication.isAuthenticated()
                        || "anonymousUser".equals(authentication.getPrincipal())) return true;
                String path = request.getRequestURI().substring(request.getContextPath().length());
                // Self-account endpoints have no branch scope; session and command boundaries guard profile writes.
                if (path.equals("/api/me") || path.equals("/api/me/profile") || path.startsWith("/api/auth/")
                        || path.startsWith("/api/invitations/approval-status")) return true;
                var state = authorization.current(Long.parseLong(authentication.getName()));
                String scope = request.getParameter("scope");
                authorization.scope(state, scope, BranchAction.READ);
                BranchAction action = path.matches("/api/(employees|invitations)(/.*)?") ? BranchAction.MANAGE_PEOPLE
                        : path.matches("/api/payments(/.*)?") ? BranchAction.READ_PAYMENTS
                        : path.matches("/api/sales(/.*)?") ? BranchAction.READ_SALES
                        : path.matches("/api/ai(/.*)?") ? BranchAction.READ_AI : BranchAction.READ;
                // all is a union, but an administrator endpoint is never a STAFF endpoint, even with an empty union.
                if (action != BranchAction.READ && authorization.scope(state, scope, action).isEmpty() && !state.owner())
                    throw AuthorizationService.forbidden();
                request.setAttribute("authorizedBranchIds", authorization.scope(state, scope, action));
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
