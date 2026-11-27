package com.openstudy.modules.payment.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.payment.dto.response.PaymentDetail;
import com.openstudy.modules.payment.dto.response.PaymentPage;
import com.openstudy.modules.payment.service.PaymentService;

/** There is deliberately no DELETE or PATCH mapping: a payment is the original record and is never rewritten (FR-PAY-10). */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "amountKrw");
    private static final String DEFAULT_SORT = "createdAt";
    private static final int MAX_CATEGORY_CODE_LENGTH = 40;
    private static final String ID_FORMAT = "[1-9][0-9]{0,18}";
    private static final String DUPLICATE_VALUE_MESSAGE = "같은 값을 두 번 보낼 수 없습니다.";

    private final PaymentService paymentService;

    @GetMapping
    public PaymentPage listPayments(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String categoryCode,
            HttpServletRequest request
    ) {
        boolean isInvalidCategoryCode = categoryCode != null
                && (categoryCode.isBlank() || categoryCode.length() > MAX_CATEGORY_CODE_LENGTH);

        if (isInvalidCategoryCode) {
            throw ApiException.validation("categoryCode", "올바른 분류 코드를 입력해 주세요.");
        }

        List<String> methods = parseLabels(request, "methods", PaymentService.METHOD_LABELS);
        List<String> productKinds = parseLabels(request, "productKinds", PaymentService.KIND_LABELS);
        List<Long> operatorIds = parseIds(request, "operatorIds");
        List<Long> processingBranchIds = parseIds(request, "processingBranchIds");
        PaymentService.Advanced advanced = new PaymentService.Advanced(
                methods,
                productKinds,
                categoryCode,
                operatorIds,
                processingBranchIds
        );
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, null, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return paymentService.list(actorId, scope, query, status, from, to, customerId, advanced);
    }

    @GetMapping("/{id}")
    public PaymentDetail getPayment(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return paymentService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<PaymentDetail> createPayment(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        PaymentDetail result = paymentService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/payments/" + result.id())).body(result);
    }

    /** A refund is a separate event; the response is the payment with its refund, never a rewritten payment. */
    @PostMapping("/{id}/refund")
    public PaymentDetail refundPayment(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return paymentService.refund(actorId(actor), id, scope, key, body, requestId);
    }

    /** Repeated contract labels to stored values; an unknown or repeated value is 422 (M13-D10). */
    private static List<String> parseLabels(HttpServletRequest request, String name, Map<String, String> stored) {
        String[] values = request.getParameterValues(name);

        if (values == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (String value : values) {
            String key = findStoredValue(stored, value)
                    .orElseThrow(() -> ApiException.validation(name, "허용되지 않은 값입니다."));

            if (result.contains(key)) {
                throw ApiException.validation(name, DUPLICATE_VALUE_MESSAGE);
            }

            result.add(key);
        }

        return result;
    }

    private static Optional<String> findStoredValue(Map<String, String> stored, String label) {
        return stored.entrySet()
                .stream()
                .filter(entry -> entry.getValue().equals(label))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static List<Long> parseIds(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);

        if (values == null) {
            return List.of();
        }

        List<Long> result = new ArrayList<>();

        for (String value : values) {
            boolean isWellFormedId = value != null && value.matches(ID_FORMAT);

            if (!isWellFormedId) {
                throw ApiException.validation(name, "올바른 ID를 입력해 주세요.");
            }

            long id = Long.parseLong(value);

            if (result.contains(id)) {
                throw ApiException.validation(name, DUPLICATE_VALUE_MESSAGE);
            }

            result.add(id);
        }

        return result;
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
