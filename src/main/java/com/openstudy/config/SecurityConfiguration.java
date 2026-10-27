package com.openstudy.config;

import java.util.List;
import java.util.Map;
import java.time.Clock;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.exception.ApiError;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.auth.service.AccountSessionFilter;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.employee.service.EmployeeAccountService;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock authenticationClock() { return Clock.systemUTC(); }

    @Bean
    SessionBoundaryPolicy sessionBoundaryPolicy(Clock clock) { return new SessionBoundaryPolicy(clock); }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService userDetailsService(EmployeeAccountService employees) {
        return email -> {
            var account = employees.byEmail(email);
            return User.withUsername(Long.toString(account.id())).password(account.passwordHash())
                    .disabled(!account.canLogin()).roles(account.status()).build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrf, EmployeeAccountService employees, ObjectMapper json, SessionBoundaryPolicy sessions) throws Exception {
        return http
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrf)
                        .ignoringRequestMatchers(request -> "POST".equals(request.getMethod())
                                && request.getRequestURI().equals(request.getContextPath() + "/api/auth/logout")
                                && request.getSession(false) == null))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/csrf", "/actuator/health").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        // Link holders: the service verifies the token (and, for existing accounts, the session).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/invitations/preview",
                                "/api/invitations/accept").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                error(request, response, json, 401, "SESSION_EXPIRED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean csrfError = exception instanceof CsrfException;
                            error(request, response, json, 403, csrfError ? "CSRF_INVALID" : "FORBIDDEN",
                                    csrfError ? "인증 요청을 다시 시도해 주세요." : "접근 권한이 없습니다.");
                        }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setHeader("Cache-Control", "no-store");
                            json.writeValue(response.getOutputStream(), Map.of("ok", true));
                        }))
                .addFilterBefore(new AccountSessionFilter(employees, json, sessions), AuthorizationFilter.class)
                .build();
    }

    public static void error(HttpServletRequest request, HttpServletResponse response, ObjectMapper json,
            int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), new ApiError(code, message, ApiRequestFilter.requestId(request), null, null, null));
    }
}
