package com.openstudy.common.web;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.exception.ApiError;

/**
 * Runs before Security, including anonymous/CSRF failures. Never logs URI, query or body.
 *
 * <p>Each API request ends with one line carrying the route template, status, duration, employee and branches
 * as key-value pairs, which the prod profile writes as JSON fields for CloudWatch (#102, ADR-M11-001 M11-D04).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiRequestFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";

    /** Set by the session filter once the signed-in employee is known; read here after the chain. */
    public static final String EMPLOYEE = ApiRequestFilter.class.getName() + ".employeeId";

    private static final String ATTRIBUTE = ApiRequestFilter.class.getName() + ".requestId";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final ObjectMapper objectMapper;

    public static String requestId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String value) {
            return value;
        }

        String value = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, value);

        return value;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        response.setHeader(HEADER, requestId);

        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean api = path.equals("/api") || path.startsWith("/api/");
        if (api) {
            response.setHeader("Cache-Control", "no-store");
        }

        long startedAt = System.nanoTime();
        String previousRequestId = MDC.get(REQUEST_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);

        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException exception) {
            if (!api || response.isCommitted()) {
                throw exception;
            }
            writeInternalError(response, requestId, exception);
        } finally {
            if (api) {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                logRequest(request, response.getStatus(), requestId, durationMs);
            }
            restoreRequestId(previousRequestId);
        }
    }

    /** The branch a request names: a {branchId} path variable, else the branchId/branchIds query parameters. */
    static String branches(HttpServletRequest request) {
        Object pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathVariables instanceof Map<?, ?> variables && variables.get("branchId") != null) {
            return variables.get("branchId").toString();
        }

        String[] values = request.getParameterValues("branchIds");
        if (values == null) {
            values = request.getParameterValues("branchId");
        }

        return values == null ? null : String.join(",", values);
    }

    private void writeInternalError(HttpServletResponse response, String requestId, Exception exception)
            throws IOException {
        log.error("API filter failure requestId={} type={}", requestId, exception.getClass().getName());

        response.resetBuffer();
        response.setStatus(500);
        response.setContentType("application/json");

        ApiError error = new ApiError(
                "INTERNAL_ERROR",
                "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                requestId,
                null,
                null,
                null
        );
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    /** The route template, never the raw path, so IDs and tokens in a URL stay out of the log. */
    private static void logRequest(HttpServletRequest request, int status, String requestId, long durationMs) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String template = route == null ? "UNMATCHED" : route.toString();

        LoggingEventBuilder line = log.atInfo()
                .addKeyValue("method", request.getMethod())
                .addKeyValue("route", template)
                .addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs);

        Object employeeId = request.getAttribute(EMPLOYEE);
        if (employeeId != null) {
            line = line.addKeyValue("employeeId", employeeId);
        }

        String branches = branches(request);
        if (branches != null) {
            line = line.addKeyValue("branchIds", branches);
        }

        line.log("API requestId={} status={} route={} durationMs={}", requestId, status, template, durationMs);
    }

    private static void restoreRequestId(String previousRequestId) {
        if (previousRequestId == null) {
            MDC.remove(REQUEST_ID_MDC_KEY);
        } else {
            MDC.put(REQUEST_ID_MDC_KEY, previousRequestId);
        }
    }
}
