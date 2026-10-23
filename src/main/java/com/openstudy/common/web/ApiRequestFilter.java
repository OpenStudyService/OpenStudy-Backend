package com.openstudy.common.web;

import java.io.IOException;
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
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiError;

/** Runs before Security, including anonymous/CSRF failures. Never logs URI, query or body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = ApiRequestFilter.class.getName() + ".requestId";
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
            if (api) log.info("API requestId={} status={}", id, response.getStatus());
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
