package com.openstudy.common.web;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiError;

/**
 * Runs before Security, including anonymous/CSRF failures. Never logs URI, query or body. Each API request ends with one
 * line carrying the route template, status, duration, employee and branches as key-value pairs, which the prod profile
 * writes as JSON fields for CloudWatch (#102, ADR-M11-001 M11-D04).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = ApiRequestFilter.class.getName() + ".requestId";
    /** Set by the session filter once the signed-in employee is known; read here after the chain. */
    public static final String EMPLOYEE = ApiRequestFilter.class.getName() + ".employeeId";
    private static final Logger log = LoggerFactory.getLogger(ApiRequestFilter.class);
    private final ObjectMapper json;

    public ApiRequestFilter(ObjectMapper json) { this.json = json; }

    public static String requestId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String value) return value;
        String value = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, value);
        return value;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request);
        response.setHeader(HEADER, id);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean api = path.equals("/api") || path.startsWith("/api/");
        if (api) response.setHeader("Cache-Control", "no-store");
        long started = System.nanoTime();
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException exception) {
            if (!api || response.isCommitted()) throw exception;
            log.error("API filter failure requestId={} type={}", id, exception.getClass().getName());
            response.resetBuffer();
            response.setStatus(500);
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), new ApiError("INTERNAL_ERROR",
                    "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", id, null, null, null));
        } finally {
            if (api) logRequest(request, response.getStatus(), id, (System.nanoTime() - started) / 1_000_000);
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }

    /** The route template, never the raw path, so IDs and tokens in a URL stay out of the log. */
    private static void logRequest(HttpServletRequest request, int status, String id, long durationMs) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String template = route == null ? "UNMATCHED" : route.toString();
        var line = log.atInfo().addKeyValue("method", request.getMethod()).addKeyValue("route", template)
                .addKeyValue("status", status).addKeyValue("durationMs", durationMs);
        if (request.getAttribute(EMPLOYEE) != null) line = line.addKeyValue("employeeId", request.getAttribute(EMPLOYEE));
        String branches = branches(request);
        if (branches != null) line = line.addKeyValue("branchIds", branches);
        line.log("API requestId={} status={} route={} durationMs={}", id, status, template, durationMs);
    }

    /** The branch a request names: a {branchId} path variable, else the branchId/branchIds query parameters. */
    static String branches(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get("branchId") != null) return variables.get("branchId").toString();
        String[] values = request.getParameterValues("branchIds");
        if (values == null) values = request.getParameterValues("branchId");
        return values == null ? null : String.join(",", values);
    }
}
