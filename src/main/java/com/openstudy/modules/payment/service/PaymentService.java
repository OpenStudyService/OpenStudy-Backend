package com.openstudy.modules.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.payment.dto.response.ActorSummary;
import com.openstudy.modules.payment.dto.response.PaymentDetail;
import com.openstudy.modules.payment.dto.response.PaymentPage;
import com.openstudy.modules.payment.dto.response.PaymentTrail;
import com.openstudy.modules.payment.dto.response.RefundView;
import com.openstudy.modules.payment.entity.Payment;
import com.openstudy.modules.payment.entity.PaymentMethod;
import com.openstudy.modules.payment.entity.PaymentPurpose;
import com.openstudy.modules.payment.entity.PaymentStatus;
import com.openstudy.modules.payment.entity.Refund;
import com.openstudy.modules.payment.repository.PaymentRepository;
import com.openstudy.modules.payment.repository.PaymentSearchRepository;
import com.openstudy.modules.payment.repository.RefundRepository;
import com.openstudy.modules.ticket.dto.PassUsage;
import com.openstudy.modules.ticket.dto.SaleTerms;
import com.openstudy.modules.ticket.service.PassService;
import com.openstudy.modules.ticket.service.ProductService;

/**
 * Mock pass payments (FR-PAY-01/02/04/05/06/08) and their full refund (FR-PAY-09/10), and the ledger side of room
 * bookings and locker assignments. The amount is the price of the product as locked for this sale; no request field
 * can set it. An approved payment and its single pass commit together or not at all, and a failed payment is recorded
 * without a pass. Every check runs in the order the contract lists, under AuthorizedCommands.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Stored value → contract label of the advanced filters (M13-D10). */
    public static final Map<String, String> METHOD_LABELS = Map.of(
            "CASH", "현금",
            "CARD", "카드",
            "TRANSFER", "계좌이체",
            "OTHER", "기타"
    );
    public static final Map<String, String> KIND_LABELS = Map.of(
            "TIME", "시간권",
            "PERIOD", "기간권",
            "STORED_VALUE", "정액권"
    );

    private static final String CREATE = "payments:create";
    private static final String REFUND = "payments:refund";
    private static final String APPROVED_LABEL = "승인";
    private static final String FAILED_LABEL = "실패";
    private static final String REFUNDED_LABEL = "환불";
    private static final String AUDIT_TABLE = "payments";
    private static final String PAYMENT_KEY_PREFIX = "PAY-";
    private static final String REFUND_KEY_PREFIX = "RFD-";
    private static final String ACTIVE_BRANCH = "ACTIVE";
    private static final String APPROVED_OUTCOME = "APPROVED";
    private static final String FAILED_OUTCOME = "FAILED";
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private static final String HASH_ALGORITHM = "SHA-256";

    private final PaymentRepository paymentRepository;
    private final PaymentSearchRepository paymentSearchRepository;
    private final RefundRepository refundRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final PassService passService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    /** The advanced filters of the payment list (#116): stored methods and kinds, the category, operators, processing branches. */
    public record Advanced(
            List<String> methods,
            List<String> productKinds,
            String categoryCode,
            List<Long> operatorIds,
            List<Long> processingBranchIds
    ) {

        public static final Advanced NONE = new Advanced(List.of(), List.of(), null, List.of(), List.of());
    }

    /** What the detail of every payment on one response reads, loaded once for the whole list. */
    private record DetailSources(
            Map<Long, PassUsage> passUsages,
            Map<Long, Long> reservationIds,
            Map<Long, Long> lockerAssignmentIds,
            Map<Long, CustomerSummary> customers,
            Map<Long, Refund> refunds,
            Map<Long, String> actorNames
    ) {
    }

    public PaymentDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("customerId", "branchId", "productId", "method", "mockOutcome");
        long customerId = input.id("customerId");
        long branch = input.id("branchId");
        long productId = input.id("productId");
        PaymentMethod method = toMethod(input.choice("method", "현금", "카드", "계좌이체", "기타"));
        boolean approved = !input.has("mockOutcome")
                || APPROVED_OUTCOME.equals(input.choice("mockOutcome", APPROVED_OUTCOME, FAILED_OUTCOME));
        requireValidKey(key);
        // scope only has to be well-formed and readable; the sale branch is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(customerId, branch, productId, method.name(), approved);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_PAYMENT_HISTORY,
                () -> {},
                state -> {
                    // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
                    Optional<PaymentDetail> storedResult = findStoredResult(actor, CREATE, scopeKey, key, fingerprint);

                    if (storedResult.isPresent()) {
                        return storedResult.get();
                    }

                    boolean isBranchActive = ACTIVE_BRANCH.equals(state.branches().get(branch));

                    if (!isBranchActive) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 판매할 수 없습니다.");
                    }

                    customerService.requirePurchasable(state, customerId);
                    SaleTerms sale = productService.sellable(productId, branch);
                    // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    String storedKey = toStoredKey(PAYMENT_KEY_PREFIX, actor, CREATE, scopeKey, key);
                    Payment newPayment = Payment.pass(
                            customerId,
                            branch,
                            productId,
                            sale.snapshot().name(),
                            method,
                            sale.snapshot().priceKrw(),
                            approved,
                            storedKey,
                            actor,
                            now
                    );
                    Payment payment = paymentRepository.saveAndFlush(newPayment);
                    Long passId = approved
                            ? passService.issue(actor, customerId, branch, payment.getId(), sale, now, requestId)
                            : null;

                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("status", payment.getStatus().name());
                    after.put("amountKrw", payment.getAmountKrw());
                    after.put("productId", Long.toString(productId));
                    after.put("purchaseProductRevisionId", Long.toString(sale.revisionId()));
                    after.put("passId", passId == null ? null : passId.toString());
                    String action = approved ? APPROVED_OUTCOME : FAILED_OUTCOME;
                    auditTrail.record(AuditEvent.change(actor, branch, AUDIT_TABLE, payment.getId(), action, null,
                            objectMapper.writeValueAsString(after), requestId));

                    PaymentDetail result = toDetails(List.of(payment), state, now).getFirst();
                    completeRequest(actor, CREATE, scopeKey, key, fingerprint, CREATED, result);

                    return result;
                }
        );
    }

    /**
     * The payment row of a room booking, inside the booking's transaction: the caller has authorized the actor, locked
     * the room and computed the amount, and its failure rolls this row back. The key is the caller's server-made value.
     * A failed Mock payment is recorded as well; what it means for the booking is the caller's decision.
     */
    public long chargeRoom(
            long actor,
            long customerId,
            long branch,
            String methodLabel,
            long amountKrw,
            boolean approved,
            String storedKey,
            Instant at,
            String requestId
    ) {
        PaymentMethod method = toMethod(methodLabel);
        Payment newPayment = Payment.room(customerId, branch, method, amountKrw, approved, storedKey, actor, at);
        Payment payment = paymentRepository.saveAndFlush(newPayment);

        return recordCharge(actor, branch, payment, requestId);
    }

    /**
     * The payment row of a locker assignment or extension, on the same terms as a room booking: the caller has authorized
     * the actor, locked the locker and computed the amount from it, and its failure rolls this row back.
     */
    public long chargeLocker(
            long actor,
            long customerId,
            long branch,
            String methodLabel,
            long amountKrw,
            boolean approved,
            String storedKey,
            Instant at,
            String requestId
    ) {
        PaymentMethod method = toMethod(methodLabel);
        Payment newPayment = Payment.locker(customerId, branch, method, amountKrw, approved, storedKey, actor, at);
        Payment payment = paymentRepository.saveAndFlush(newPayment);

        return recordCharge(actor, branch, payment, requestId);
    }

    /** Wire status of these payments, for a caller that has already decided the reader may see them. */
    @Transactional(readOnly = true)
    public Map<Long, String> statusLabels(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new HashMap<>();
        paymentRepository.findAllById(ids)
                .forEach(payment -> result.put(payment.getId(), toLabel(payment.getStatus())));

        return result;
    }

    /**
     * Full refund of an approved pass payment whose pass is unused and unexpired at the processing instant (M4-D04).
     * Only payments:write at the payment branch may refund, and that branch is the processing branch (M4-D08). The
     * refund event, both REFUNDED transitions, audit, events and the idempotent result commit together; amount and
     * approval time of the original payment are never touched (FR-PAY-10). Only pass payments come here: a room is
     * refunded by cancelling its reservation and a locker is not refunded (space-operations-api.md).
     */
    public PaymentDetail refund(
            long actor,
            long id,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        String reason = new StrictInput(body).only("reason").text("reason", MAX_REASON_LENGTH);
        requireValidKey(key);
        // A payment outside the actor's branches is hidden (404); inside them, a missing payments:write is 403 below.
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);
        long branch = paymentRepository.findById(id)
                .map(Payment::getBranchId)
                .filter(selected::contains)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(id, reason)));

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_PAYMENT_HISTORY,
                () -> {},
                state -> {
                    Optional<PaymentDetail> storedResult = findStoredResult(actor, REFUND, scopeKey, key, fingerprint);

                    if (storedResult.isPresent()) {
                        return storedResult.get();
                    }

                    // Payment, then pass: every check below is repeated under these locks (contract transaction table).
                    paymentRepository.lock(id);
                    Payment payment = paymentRepository.findById(id).orElseThrow();
                    requireRefundable(payment);
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    long passId = passService.refund(actor, id, now, requestId);
                    String storedKey = toStoredKey(REFUND_KEY_PREFIX, actor, REFUND, scopeKey, key);
                    Refund refund = refundInFull(payment, actor, reason, storedKey, now);

                    Map<String, Object> after = toRefundedState(payment, refund);
                    after.put("passId", Long.toString(passId));
                    auditTrail.record(AuditEvent.change(actor, branch, AUDIT_TABLE, id, "REFUNDED", approvedStateJson(),
                            objectMapper.writeValueAsString(after), requestId));

                    PaymentDetail result = toDetails(List.of(payment), state, now).getFirst();
                    completeRequest(actor, REFUND, scopeKey, key, fingerprint, OK, result);

                    return result;
                }
        );
    }

    /**
     * The full refund of a room payment, inside the cancellation's transaction: the caller has authorized the actor,
     * replayed any stored result and locked the reservation, and its failure rolls this back. The key is the caller's
     * server-made value and the processing branch is the payment's own (M4-D08). uk_refunds_1 stays the last guard.
     */
    public long refundRoom(long actor, long paymentId, String reason, String storedKey, Instant at, String requestId) {
        paymentRepository.lock(paymentId);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();

        if (payment.getPurpose() != PaymentPurpose.ROOM) {
            throw new IllegalStateException("A reservation holds a room payment");
        }

        Refund refund = refundInFull(payment, actor, reason, storedKey, at);

        Map<String, Object> after = toRefundedState(payment, refund);
        auditTrail.record(AuditEvent.change(actor, payment.getBranchId(), AUDIT_TABLE, paymentId, "REFUNDED",
                approvedStateJson(), objectMapper.writeValueAsString(after), requestId));

        return refund.getId();
    }

    /**
     * Who sold and who refunded these payments, for a customer's pass history. No amounts leave here (NFR-SEC-06), which
     * is why this needs no payments:read; the caller has authorized the passes that name the payments.
     */
    @Transactional(readOnly = true)
    public Map<Long, PaymentTrail> trails(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, Refund> refunds = findRefunds(ids);
        Map<Long, PaymentTrail> result = new HashMap<>();

        for (Payment payment : paymentRepository.findAllById(ids)) {
            Refund refund = refunds.get(payment.getId());
            PaymentTrail.RefundTrail refundTrail = refund == null ? null : toRefundTrail(refund);
            result.put(payment.getId(), new PaymentTrail(payment.getApprovedAt(), payment.getCreatedBy(), refundTrail));
        }

        return result;
    }

    @Transactional(readOnly = true)
    public PaymentPage list(
            long actor,
            String scope,
            ListQuery query,
            String status,
            String from,
            String to,
            String customerId,
            Advanced advanced
    ) {
        PaymentStatus statusFilter = status == null ? null : parseStatus(status);
        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        boolean isEmptyRange = start != null && end != null && !start.isBefore(end);

        if (isEmptyRange) {
            throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        }

        Long customer = customerId == null ? null : new StrictInput(Map.of("customerId", customerId)).id("customerId");
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ_PAYMENTS);

        // M13-D10: a refund's processing branch must be one the requester sees (403 otherwise).
        if (!advanced.processingBranchIds().isEmpty()) {
            authorizationService.require(state, Set.copyOf(advanced.processingBranchIds()), BranchAction.READ);
        }

        PaymentSearchRepository.Sort sort = "amountKrw".equals(query.sort())
                ? PaymentSearchRepository.Sort.AMOUNT
                : PaymentSearchRepository.Sort.CREATED_AT;
        PaymentSearchRepository.Filter filter = new PaymentSearchRepository.Filter(
                selected,
                statusFilter,
                start,
                end,
                customer,
                advanced.methods(),
                advanced.productKinds(),
                advanced.categoryCode(),
                advanced.operatorIds(),
                advanced.processingBranchIds()
        );
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        PaymentSearchRepository.Page page = paymentSearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );

        PaymentPage.Filter applied = new PaymentPage.Filter(
                toIdStrings(selected.stream().sorted().toList()),
                status,
                start,
                end,
                customer == null ? null : customer.toString(),
                toLabels(advanced.methods(), METHOD_LABELS),
                toLabels(advanced.productKinds(), KIND_LABELS),
                advanced.categoryCode(),
                toIdStrings(advanced.operatorIds()),
                toIdStrings(advanced.processingBranchIds())
        );
        List<PaymentDetail> items = toDetails(page.rows(), state, clock.instant());
        PageResponse.Meta meta = new PageResponse.Meta(query.page(), query.pageSize(), page.total());

        return new PaymentPage(items, meta, applied, toSummary(page.totals()));
    }

    /** 404 hides payments of branches without payments:read, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public PaymentDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        Payment payment = paymentRepository.findById(id)
                .filter(found -> selected.contains(found.getBranchId()))
                .filter(found -> authorizationService.permits(state, found.getBranchId(), BranchAction.READ_PAYMENTS))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return toDetails(List.of(payment), state, clock.instant()).getFirst();
    }

    private long recordCharge(long actor, long branch, Payment payment, String requestId) {
        boolean approved = payment.getStatus() == PaymentStatus.APPROVED;
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", payment.getStatus().name());
        after.put("purpose", payment.getPurpose().name());
        after.put("amountKrw", payment.getAmountKrw());
        String action = approved ? APPROVED_OUTCOME : FAILED_OUTCOME;
        auditTrail.record(AuditEvent.change(actor, branch, AUDIT_TABLE, payment.getId(), action, null,
                objectMapper.writeValueAsString(after), requestId));

        return payment.getId();
    }

    /** A stored result of the same request, replayed; the same key with a different input is a conflict. */
    private Optional<PaymentDetail> findStoredResult(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint
    ) {
        Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, operation, scopeKey, key);

        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), PaymentDetail.class));
    }

    private void completeRequest(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            int status,
            PaymentDetail result
    ) {
        String responseJson = objectMapper.writeValueAsString(result);
        Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
        idempotencyStore.complete(actor, operation, scopeKey, key, fingerprint, status, responseJson, expiresAt);
    }

    private static void requireRefundable(Payment payment) {
        if (payment.getPurpose() != PaymentPurpose.PASS) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE", "이용권 결제만 이 요청으로 환불할 수 있습니다.");
        }

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw ApiException.conflict("ALREADY_REFUNDED", "이미 환불된 결제입니다.");
        }

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE", "승인된 결제만 환불할 수 있습니다.");
        }
    }

    /** The refund event, its closed-day adjustment and the REFUNDED payment, in this order. */
    private Refund refundInFull(Payment payment, long actor, String reason, String storedKey, Instant at) {
        Refund refund = refundRepository.saveAndFlush(Refund.full(payment, actor, reason, storedKey, at));
        adjustClosedDay(payment, refund);
        payment.refund();
        paymentRepository.saveAndFlush(payment);

        return refund;
    }

    /** A refund of a payment approved on a closed day is recorded on that closing (M13-D04·D11, #115). */
    private void adjustClosedDay(Payment payment, Refund refund) {
        LocalDate approvedDay = LocalDate.ofInstant(payment.getApprovedAt(), BUSINESS_ZONE);
        refundRepository.recordClosingAdjustment(refund.getId(), payment.getBranchId(), approvedDay);
    }

    private static Map<String, Object> toRefundedState(Payment payment, Refund refund) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", payment.getStatus().name());
        after.put("refundId", Long.toString(refund.getId()));
        after.put("amountKrw", refund.getAmountKrw());

        return after;
    }

    private String approvedStateJson() {
        return objectMapper.writeValueAsString(Map.of("status", PaymentStatus.APPROVED.name()));
    }

    private Map<Long, Refund> findRefunds(Collection<Long> paymentIds) {
        Map<Long, Refund> refunds = new HashMap<>();
        refundRepository.findByPaymentIdIn(paymentIds).forEach(refund -> refunds.put(refund.getPaymentId(), refund));

        return refunds;
    }

    private static PaymentTrail.RefundTrail toRefundTrail(Refund refund) {
        return new PaymentTrail.RefundTrail(
                refund.getRefundedAt(),
                refund.getProcessedBy(),
                refund.getProcessingBranchId(),
                refund.getReason()
        );
    }

    private static PaymentPage.Summary toSummary(List<PaymentSearchRepository.StatusTotal> totals) {
        Map<PaymentStatus, PaymentPage.StatusTotal> byStatus = new EnumMap<>(PaymentStatus.class);
        totals.forEach(total -> byStatus.put(
                total.status(),
                new PaymentPage.StatusTotal(total.count(), total.amountKrw())
        ));
        PaymentPage.StatusTotal none = new PaymentPage.StatusTotal(0, 0);

        return new PaymentPage.Summary(
                byStatus.getOrDefault(PaymentStatus.APPROVED, none),
                byStatus.getOrDefault(PaymentStatus.FAILED, none),
                byStatus.getOrDefault(PaymentStatus.REFUNDED, none)
        );
    }

    private List<PaymentDetail> toDetails(List<Payment> payments, AuthorizationState state, Instant now) {
        List<Long> ids = payments.stream()
                .map(Payment::getId)
                .toList();
        Map<Long, PassUsage> passUsages = passService.byPayments(ids, now);
        Map<Long, Long> reservationIds = new HashMap<>();
        Map<Long, Long> lockerAssignmentIds = new HashMap<>();

        if (!ids.isEmpty()) {
            paymentRepository.findRoomReservations(ids)
                    .forEach(ref -> reservationIds.put(ref.getPaymentId(), ref.getReservationId()));
            paymentRepository.findLockerAssignments(ids)
                    .forEach(ref -> lockerAssignmentIds.put(ref.getPaymentId(), ref.getAssignmentId()));
        }

        List<Long> customerIds = payments.stream()
                .map(Payment::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> customers = customerService.summaries(customerIds);
        Map<Long, Refund> refunds = findRefunds(ids);
        List<Long> actorIds = Stream.concat(
                        payments.stream().map(Payment::getCreatedBy),
                        refunds.values().stream().map(Refund::getProcessedBy)
                )
                .toList();
        Map<Long, String> actorNames = employeeAccountService.names(actorIds);
        DetailSources sources = new DetailSources(
                passUsages,
                reservationIds,
                lockerAssignmentIds,
                customers,
                refunds,
                actorNames
        );

        return payments.stream()
                .map(payment -> toDetail(payment, sources, state))
                .toList();
    }

    private PaymentDetail toDetail(Payment payment, DetailSources sources, AuthorizationState state) {
        PassUsage pass = sources.passUsages().get(payment.getId());
        boolean canRefund = payment.getStatus() == PaymentStatus.APPROVED
                && pass != null
                && pass.refundable()
                && authorizationService.permits(state, payment.getBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);
        Refund refund = sources.refunds().get(payment.getId());
        ActorSummary createdBy = toActorSummary(payment.getCreatedBy(), sources.actorNames());

        return new PaymentDetail(
                Long.toString(payment.getId()),
                ApiVersion.fromDatabase(payment.getVersion()),
                true,
                toLabel(payment.getPurpose()),
                Long.toString(payment.getBranchId()),
                sources.customers().get(payment.getCustomerId()),
                toIdString(payment.getProductId()),
                payment.getProductNameSnapshot(),
                toLabel(payment.getMethod()),
                payment.getAmountKrw(),
                toLabel(payment.getStatus()),
                payment.getApprovedAt(),
                payment.getFailedAt(),
                payment.getCreatedAt(),
                createdBy,
                pass == null ? null : Long.toString(pass.passId()),
                refund == null ? null : toRefundView(refund, sources.actorNames()),
                canRefund,
                toIdString(sources.reservationIds().get(payment.getId())),
                toIdString(sources.lockerAssignmentIds().get(payment.getId()))
        );
    }

    private static RefundView toRefundView(Refund refund, Map<Long, String> actorNames) {
        return new RefundView(
                Long.toString(refund.getId()),
                refund.getAmountKrw(),
                refund.getReason(),
                refund.getRefundedAt(),
                Long.toString(refund.getProcessingBranchId()),
                toActorSummary(refund.getProcessedBy(), actorNames)
        );
    }

    private static ActorSummary toActorSummary(long employeeId, Map<Long, String> actorNames) {
        return new ActorSummary(Long.toString(employeeId), actorNames.getOrDefault(employeeId, ""));
    }

    private static String toIdString(Long value) {
        return value == null ? null : value.toString();
    }

    private static List<String> toIdStrings(List<Long> ids) {
        return ids.stream()
                .map(String::valueOf)
                .toList();
    }

    private static List<String> toLabels(List<String> storedValues, Map<String, String> labels) {
        return storedValues.stream()
                .map(labels::get)
                .toList();
    }

    /**
     * payments.idempotency_key and refunds.idempotency_key are globally unique and 120 characters, so they store a hash
     * of (actor, operation, scope, client key) instead of the raw key: two employees may reuse a key without colliding
     * (contract "멱등 키"). The scope is the branch for a sale and the payment for a refund.
     */
    private static String toStoredKey(String prefix, long actor, String operation, String scopeKey, String key) {
        String source = actor + "\n" + operation + "\n" + scopeKey + "\n" + key;

        return prefix + HexFormat.of().formatHex(digest(source));
    }

    private static PaymentStatus parseStatus(String label) {
        return switch (label) {
            case APPROVED_LABEL -> PaymentStatus.APPROVED;
            case FAILED_LABEL -> PaymentStatus.FAILED;
            case REFUNDED_LABEL -> PaymentStatus.REFUNDED;
            default -> throw ApiException.validation("status", "올바른 결제 상태를 선택해 주세요.");
        };
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "ISO 8601 UTC 시각을 입력해 주세요.");
        }
    }

    private static PaymentMethod toMethod(String label) {
        return switch (label) {
            case "현금" -> PaymentMethod.CASH;
            case "카드" -> PaymentMethod.CARD;
            case "계좌이체" -> PaymentMethod.TRANSFER;
            default -> PaymentMethod.OTHER;
        };
    }

    private static String toLabel(PaymentPurpose purpose) {
        return switch (purpose) {
            case PASS -> "이용권";
            case ROOM -> "스터디룸";
            case LOCKER -> "사물함";
        };
    }

    private static String toLabel(PaymentMethod method) {
        return switch (method) {
            case CASH -> "현금";
            case CARD -> "카드";
            case TRANSFER -> "계좌이체";
            case OTHER -> "기타";
        };
    }

    private static String toLabel(PaymentStatus status) {
        return switch (status) {
            case APPROVED -> APPROVED_LABEL;
            case FAILED -> FAILED_LABEL;
            case REFUNDED -> REFUNDED_LABEL;
            case PENDING -> throw new IllegalStateException("Mock payments are never stored as PENDING");
        };
    }

    private static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
