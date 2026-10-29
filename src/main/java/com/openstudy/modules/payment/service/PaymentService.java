package com.openstudy.modules.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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
import com.openstudy.modules.payment.dto.response.RefundView;
import com.openstudy.modules.payment.entity.Payment;
import com.openstudy.modules.payment.entity.PaymentMethod;
import com.openstudy.modules.payment.entity.PaymentStatus;
import com.openstudy.modules.payment.entity.Refund;
import com.openstudy.modules.payment.repository.PaymentRepository;
import com.openstudy.modules.payment.repository.PaymentSearchRepository;
import com.openstudy.modules.payment.repository.RefundRepository;
import com.openstudy.modules.ticket.service.PassService;
import com.openstudy.modules.ticket.service.ProductService;

/**
 * Mock pass payments (FR-PAY-01/02/04/05/06/08) and their full refund (FR-PAY-09/10). The amount is the price of the product as locked for this sale; no
 * request field can set it. An approved payment and its single pass commit together or not at all, and a failed
 * payment is recorded without a pass. Every check runs in the order the contract lists, under AuthorizedCommands.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final String CREATE = "payments:create";
    private static final String REFUND = "payments:refund";
    private static final String APPROVED = "승인";
    private static final String FAILED = "실패";
    private static final String REFUNDED = "환불";
    private final PaymentRepository repository;
    private final PaymentSearchRepository search;
    private final RefundRepository refunds;
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
                    sale.snapshot().priceKrw(), approved, storedKey("PAY-", actor, CREATE, scopeKey, key), actor, now));
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

    /**
     * Full refund of an approved pass payment whose pass is unused and unexpired at the processing instant (M4-D04).
     * Only payments:write at the payment branch may refund, and that branch is the processing branch (M4-D08). The
     * refund event, both REFUNDED transitions, audit, events and the idempotent result commit together; amount and
     * approval time of the original payment are never touched (FR-PAY-10).
     */
    public PaymentDetail refund(long actor, long id, String scope, String key, Map<String, Object> body, String requestId) {
        String reason = new StrictInput(body).only("reason").text("reason", 500);
        validKey(key);
        // A payment outside the actor's branches is hidden (404); inside them, a missing payments:write is 403 below.
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        long branch = repository.findById(id).map(Payment::getBranchId).filter(selected::contains)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(id, reason)));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY, () -> {}, state -> {
            var prior = requests.lock(actor, REFUND, scopeKey, key);
            if (prior.isPresent()) {
                if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.get().getResponseJson(), PaymentDetail.class);
            }
            // Payment, then pass: every check below is repeated under these locks (contract transaction table).
            repository.lock(id);
            var payment = repository.findById(id).orElseThrow();
            if (payment.getStatus() == PaymentStatus.REFUNDED)
                throw ApiException.conflict("ALREADY_REFUNDED", "이미 환불된 결제입니다.");
            if (payment.getStatus() != PaymentStatus.APPROVED)
                throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE", "승인된 결제만 환불할 수 있습니다.");
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            long passId = passes.refund(actor, id, now, requestId);
            var refund = refunds.saveAndFlush(Refund.full(payment, actor, reason,
                    storedKey("RFD-", actor, REFUND, scopeKey, key), now));
            payment.refund();
            repository.saveAndFlush(payment);
            var after = new LinkedHashMap<String, Object>();
            after.put("status", payment.getStatus().name());
            after.put("refundId", Long.toString(refund.getId()));
            after.put("amountKrw", refund.getAmountKrw());
            after.put("passId", Long.toString(passId));
            audit.record(AuditEvent.change(actor, branch, "payments", id, "REFUNDED",
                    json.writeValueAsString(Map.of("status", PaymentStatus.APPROVED.name())), json.writeValueAsString(after),
                    requestId));
            outbox.invalidate(branch, "payments", id);
            var result = views(List.of(payment), state, now).getFirst();
            requests.complete(actor, REFUND, scopeKey, key, fingerprint, 200, json.writeValueAsString(result),
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
        Map<Long, Refund> refunded = new HashMap<>();
        refunds.findByPaymentIdIn(ids).forEach(refund -> refunded.put(refund.getPaymentId(), refund));
        var actors = employees.names(Stream.concat(payments.stream().map(Payment::getCreatedBy),
                refunded.values().stream().map(Refund::getProcessedBy)).toList());
        return payments.stream().map(payment -> {
            var pass = usage.get(payment.getId());
            boolean canRefund = payment.getStatus() == PaymentStatus.APPROVED && pass != null && pass.refundable()
                    && policy.permits(state, payment.getBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);
            var refund = refunded.get(payment.getId());
            return new PaymentDetail(Long.toString(payment.getId()), ApiVersion.fromDatabase(payment.getVersion()), true,
                    "이용권", Long.toString(payment.getBranchId()), names.get(payment.getCustomerId()),
                    Long.toString(payment.getProductId()), payment.getProductNameSnapshot(), label(payment.getMethod()),
                    payment.getAmountKrw(), label(payment.getStatus()), payment.getApprovedAt(), payment.getFailedAt(),
                    payment.getCreatedAt(), new ActorSummary(Long.toString(payment.getCreatedBy()),
                            actors.getOrDefault(payment.getCreatedBy(), "")),
                    pass == null ? null : Long.toString(pass.passId()), refund == null ? null : view(refund, actors),
                    canRefund);
        }).toList();
    }

    private static RefundView view(Refund refund, Map<Long, String> actors) {
        return new RefundView(Long.toString(refund.getId()), refund.getAmountKrw(), refund.getReason(),
                refund.getRefundedAt(), Long.toString(refund.getProcessingBranchId()),
                new ActorSummary(Long.toString(refund.getProcessedBy()), actors.getOrDefault(refund.getProcessedBy(), "")));
    }

    /**
     * payments.idempotency_key and refunds.idempotency_key are globally unique and 120 characters, so they store a hash
     * of (actor, operation, scope, client key) instead of the raw key: two employees may reuse a key without colliding
     * (contract "멱등 키"). The scope is the branch for a sale and the payment for a refund.
     */
    private static String storedKey(String prefix, long actor, String operation, String scopeKey, String key) {
        return prefix + HexFormat.of().formatHex(digest(actor + "\n" + operation + "\n" + scopeKey + "\n" + key));
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
