package com.openstudy.modules.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.payment.dto.response.ActorSummary;
import com.openstudy.modules.payment.dto.response.PaymentDetail;
import com.openstudy.modules.payment.entity.Payment;
import com.openstudy.modules.payment.entity.PaymentMethod;
import com.openstudy.modules.payment.entity.PaymentStatus;
import com.openstudy.modules.payment.repository.PaymentRepository;
import com.openstudy.modules.payment.repository.PaymentSearchRepository;
import com.openstudy.modules.ticket.service.PassService;
import com.openstudy.modules.ticket.service.ProductService;

/**
 * Mock pass payments (FR-PAY-01/02/04/05/06/08). The amount is the price of the product as locked for this sale; no
 * request field can set it. An approved payment and its single pass commit together or not at all, and a failed
 * payment is recorded without a pass. Every check runs in the order the contract lists, under AuthorizedCommands.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final String CREATE = "payments:create";
    private static final String APPROVED = "승인";
    private static final String FAILED = "실패";
    private static final String REFUNDED = "환불";
    private final PaymentRepository repository;
    private final PaymentSearchRepository search;
    private final CustomerService customers;
    private final ProductService products;
    private final PassService passes;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    public PaymentDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("customerId", "branchId", "productId", "method", "mockOutcome");
        long customerId = input.id("customerId");
        long branch = input.id("branchId");
        long productId = input.id("productId");
        PaymentMethod method = method(input.choice("method", "현금", "카드", "계좌이체", "기타"));
        boolean approved = !input.has("mockOutcome") || "APPROVED".equals(input.choice("mockOutcome", "APPROVED", "FAILED"));
        validKey(key);
        // scope only has to be well-formed and readable; the sale branch is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(customerId, branch, productId, method.name(), approved)));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            if (prior.isPresent()) {
                if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.get().getResponseJson(), PaymentDetail.class);
            }
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 판매할 수 없습니다.");
            customers.requirePurchasable(state, customerId);
            var sale = products.sellable(productId, branch);
            // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var payment = repository.saveAndFlush(Payment.pass(customerId, branch, productId, sale.snapshot().name(), method,
                    sale.snapshot().priceKrw(), approved, storedKey(actor, scopeKey, key), actor, now));
            Long passId = approved ? passes.issue(actor, customerId, branch, payment.getId(), sale, now, requestId) : null;
            var after = new LinkedHashMap<String, Object>();
            after.put("status", payment.getStatus().name());
            after.put("amountKrw", payment.getAmountKrw());
            after.put("productId", Long.toString(productId));
            after.put("purchaseProductRevisionId", Long.toString(sale.revisionId()));
            after.put("passId", passId == null ? null : passId.toString());
            audit.record(AuditEvent.change(actor, branch, "payments", payment.getId(), approved ? "APPROVED" : "FAILED", null,
                    json.writeValueAsString(after), requestId));
            outbox.invalidate(branch, "payments", payment.getId());
            var result = views(List.of(payment), state, now).getFirst();
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentDetail> list(long actor, String scope, ListQuery query, String status, String from, String to,
            String customerId) {
        PaymentStatus filter = status == null ? null : switch (status) {
            case APPROVED -> PaymentStatus.APPROVED;
            case FAILED -> PaymentStatus.FAILED;
            case REFUNDED -> PaymentStatus.REFUNDED;
            default -> throw ApiException.validation("status", "올바른 결제 상태를 선택해 주세요.");
        };
        Instant start = instant(from, "from");
        Instant end = instant(to, "to");
        if (start != null && end != null && !start.isBefore(end)) throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        Long customer = customerId == null ? null : new StrictInput(Map.of("customerId", customerId)).id("customerId");
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ_PAYMENTS);
        var sort = "amountKrw".equals(query.sort()) ? PaymentSearchRepository.Sort.AMOUNT : PaymentSearchRepository.Sort.CREATED_AT;
        var page = search.find(new PaymentSearchRepository.Filter(selected, filter, start, end, customer), sort,
                query.direction() == ListQuery.Direction.DESC, query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state, clock.instant()),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides payments of branches without payments:read, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public PaymentDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var payment = repository.findById(id)
                .filter(found -> selected.contains(found.getBranchId()))
                .filter(found -> policy.permits(state, found.getBranchId(), BranchAction.READ_PAYMENTS))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return views(List.of(payment), state, clock.instant()).getFirst();
    }

    private List<PaymentDetail> views(List<Payment> payments, AuthorizationState state, Instant now) {
        var ids = payments.stream().map(Payment::getId).toList();
        var usage = passes.byPayments(ids, now);
        var names = customers.summaries(payments.stream().map(Payment::getCustomerId).toList());
        var actors = employees.names(payments.stream().map(Payment::getCreatedBy).toList());
        return payments.stream().map(payment -> {
            var pass = usage.get(payment.getId());
            boolean canRefund = payment.getStatus() == PaymentStatus.APPROVED && pass != null && pass.refundable()
                    && policy.permits(state, payment.getBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);
            // Refund events arrive with #58; until then no payment can reach REFUNDED.
            return new PaymentDetail(Long.toString(payment.getId()), ApiVersion.fromDatabase(payment.getVersion()), true,
                    "이용권", Long.toString(payment.getBranchId()), names.get(payment.getCustomerId()),
                    Long.toString(payment.getProductId()), payment.getProductNameSnapshot(), label(payment.getMethod()),
                    payment.getAmountKrw(), label(payment.getStatus()), payment.getApprovedAt(), payment.getFailedAt(),
                    payment.getCreatedAt(), new ActorSummary(Long.toString(payment.getCreatedBy()),
                            actors.getOrDefault(payment.getCreatedBy(), "")),
                    pass == null ? null : Long.toString(pass.passId()), null, canRefund);
        }).toList();
    }

    /**
     * payments.idempotency_key is globally unique and 120 characters, so it stores a hash of (actor, operation, branch,
     * client key) instead of the raw key: two employees may reuse a key without colliding (contract "멱등 키").
     */
    private static String storedKey(long actor, String scopeKey, String key) {
        return "PAY-" + HexFormat.of().formatHex(digest(actor + "\n" + CREATE + "\n" + scopeKey + "\n" + key));
    }

    private static Instant instant(String value, String field) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { throw ApiException.validation(field, "ISO 8601 UTC 시각을 입력해 주세요."); }
    }

    private static PaymentMethod method(String label) {
        return switch (label) {
            case "현금" -> PaymentMethod.CASH;
            case "카드" -> PaymentMethod.CARD;
            case "계좌이체" -> PaymentMethod.TRANSFER;
            default -> PaymentMethod.OTHER;
        };
    }

    private static String label(PaymentMethod method) {
        return switch (method) {
            case CASH -> "현금";
            case CARD -> "카드";
            case TRANSFER -> "계좌이체";
            case OTHER -> "기타";
        };
    }

    private static String label(PaymentStatus status) {
        return switch (status) {
            case APPROVED -> APPROVED;
            case FAILED -> FAILED;
            case REFUNDED -> REFUNDED;
            case PENDING -> throw new IllegalStateException("Mock payments are never stored as PENDING");
        };
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
