package com.openstudy.modules.payment.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.payment.dto.response.PaymentDetail;
import com.openstudy.modules.payment.dto.response.PaymentPage;
import com.openstudy.modules.payment.service.PaymentService;

/** There is deliberately no DELETE or PATCH mapping: a payment is the original record and is never rewritten (FR-PAY-10). */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService service;

    @GetMapping
    public PaymentPage list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String categoryCode, jakarta.servlet.http.HttpServletRequest request) {
        if (categoryCode != null && (categoryCode.isBlank() || categoryCode.length() > 40))
            throw com.openstudy.common.exception.ApiException.validation("categoryCode", "올바른 분류 코드를 입력해 주세요.");
        var advanced = new PaymentService.Advanced(labels(request, "methods", PaymentService.METHOD_LABELS),
                labels(request, "productKinds", PaymentService.KIND_LABELS), categoryCode, ids(request, "operatorIds"),
                ids(request, "processingBranchIds"));
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, sort, direction,
                Set.of("createdAt", "amountKrw"), "createdAt"), status, from, to, customerId, advanced);
    }

    /** Repeated contract labels to stored values; an unknown or repeated value is 422 (M13-D10). */
    private static java.util.List<String> labels(jakarta.servlet.http.HttpServletRequest request, String name, java.util.Map<String, String> stored) {
        String[] values = request.getParameterValues(name);
        if (values == null) return java.util.List.of();
        var result = new java.util.ArrayList<String>();
        for (String value : values) {
            String key = stored.entrySet().stream().filter(entry -> entry.getValue().equals(value)).map(java.util.Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> com.openstudy.common.exception.ApiException.validation(name, "허용되지 않은 값입니다."));
            if (result.contains(key)) throw com.openstudy.common.exception.ApiException.validation(name, "같은 값을 두 번 보낼 수 없습니다.");
            result.add(key);
        }
        return result;
    }

    private static java.util.List<Long> ids(jakarta.servlet.http.HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return java.util.List.of();
        var result = new java.util.ArrayList<Long>();
        for (String value : values) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw com.openstudy.common.exception.ApiException.validation(name, "올바른 ID를 입력해 주세요.");
            long id = Long.parseLong(value);
            if (result.contains(id)) throw com.openstudy.common.exception.ApiException.validation(name, "같은 값을 두 번 보낼 수 없습니다.");
            result.add(id);
        }
        return result;
    }

    @GetMapping("/{id}")
    public PaymentDetail get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<PaymentDetail> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/payments/" + result.id())).body(result);
    }

    /** A refund is a separate event; the response is the payment with its refund, never a rewritten payment. */
    @PostMapping("/{id}/refund")
    public PaymentDetail refund(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.refund(actor(actor), id, scope, key, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
