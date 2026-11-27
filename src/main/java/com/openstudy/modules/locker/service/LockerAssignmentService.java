package com.openstudy.modules.locker.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.openstudy.modules.locker.dto.response.ActorSummary;
import com.openstudy.modules.locker.dto.response.CustomerLockerUsage;
import com.openstudy.modules.locker.dto.response.LockerAssignmentView;
import com.openstudy.modules.locker.dto.response.LockerChargeView;
import com.openstudy.modules.locker.dto.response.LockerRef;
import com.openstudy.modules.locker.entity.Locker;
import com.openstudy.modules.locker.entity.LockerAssignment;
import com.openstudy.modules.locker.entity.LockerAssignmentCharge;
import com.openstudy.modules.locker.entity.LockerAssignmentStatus;
import com.openstudy.modules.locker.entity.LockerChargeKind;
import com.openstudy.modules.locker.entity.LockerState;
import com.openstudy.modules.locker.repository.LockerAssignmentChargeRepository;
import com.openstudy.modules.locker.repository.LockerAssignmentRepository;
import com.openstudy.modules.locker.repository.LockerAssignmentSearchRepository;
import com.openstudy.modules.locker.repository.LockerRepository;
import com.openstudy.modules.payment.service.PaymentService;

/**
 * Paid assignment, extension and return of a locker (FR-LOCK-03/04, FR-PAY-07/12, M6-D03·D05). The payment, the
 * assignment or its new end date and the charge commit together or not at all. No database constraint refuses two
 * overlapping periods, so every assignment and extension takes the locker row's write lock and decides, under it and in
 * the contract's order, from a locking read of what holds the locker. The amount comes only from that locked row, and
 * the period is judged before any payment is written, so a refused request leaves no payment. A return moves no money.
 */
@Service
@RequiredArgsConstructor
public class LockerAssignmentService {

    private static final String CREATE = "locker-assignments:create";
    private static final String EXTEND = "locker-assignments:extend";
    private static final String ENTITY = "locker-assignments";
    private static final int DAYS_PER_TERM = 28;
    private static final String PAYMENT_KEY_PREFIX = "LPAY-";
    private static final String ACTIVE_BRANCH = "ACTIVE";
    private static final String APPROVED_OUTCOME = "APPROVED";
    private static final String FAILED_OUTCOME = "FAILED";
    private static final int DATE_LENGTH = 10;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);

    private final LockerAssignmentRepository lockerAssignmentRepository;
    private final LockerAssignmentSearchRepository lockerAssignmentSearchRepository;
    private final LockerAssignmentChargeRepository lockerAssignmentChargeRepository;
    private final LockerRepository lockerRepository;
    private final CustomerService customerService;
    private final PaymentService paymentService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public LockerAssignmentView create(
            long actor,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body)
                .only("branchId", "lockerId", "customerId", "startDate", "endDate", "method", "mockOutcome");
        long branch = input.id("branchId");
        long lockerId = input.id("lockerId");
        long customerId = input.id("customerId");
        LocalDate start = LockerInputs.parseDate(input.text("startDate", DATE_LENGTH), "startDate");
        LocalDate end = LockerInputs.parseDate(input.text("endDate", DATE_LENGTH), "endDate");
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome")
                || APPROVED_OUTCOME.equals(input.choice("mockOutcome", APPROVED_OUTCOME, FAILED_OUTCOME));
        LockerInputs.requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(
                branch,
                lockerId,
                customerId,
                start.toString(),
                end.toString(),
                method,
                approved
        );
        byte[] fingerprint = LockerInputs.digest(objectMapper.writeValueAsString(fingerprintInput));

        // WRITE_PAYMENT_HISTORY, not NEW_PAYMENT: a stopped branch must answer BRANCH_NOT_ACTIVE at its place in the table.
        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_PAYMENT_HISTORY,
                () -> {},
                state -> {
                    Optional<LockerAssignmentView> replayed = replay(actor, CREATE, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Locker locker = lockerRepository.findLocked(lockerId)
                            .filter(found -> authorizationService.permits(
                                    state,
                                    found.getBranchId(),
                                    BranchAction.READ
                            ))
                            .orElseThrow(LockerAssignmentService::createNotFound);

                    if (locker.getBranchId() != branch) {
                        throw ApiException.unprocessable("LOCKER_BRANCH_MISMATCH", "요청한 지점의 사물함이 아닙니다.");
                    }

                    requireOperating(state, branch);
                    requireInService(locker);
                    // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    LocalDate today = LockerInputs.toBusinessDate(now);

                    if (start.isBefore(today)) {
                        throw createTermInvalid();
                    }

                    int terms = countTerms(start, end);
                    customerService.requirePurchasable(state, customerId);
                    List<LockerAssignment> holding = lockerAssignmentRepository.findHolding(lockerId);

                    if (holding.stream().anyMatch(held -> held.overdue(today))) {
                        throw LockerConflicts.createNotReturned();
                    }

                    if (holding.stream().anyMatch(held -> held.overlaps(start, end))) {
                        throw LockerConflicts.createPeriodConflict();
                    }

                    long rate = locker.getRatePer4WeeksKrw();
                    long amount = calculateAmount(rate, terms);
                    String paymentKey = LockerInputs.toStoredKey(PAYMENT_KEY_PREFIX, actor, CREATE, scopeKey, key);
                    long paymentId = LockerConflicts.translating(() -> paymentService.chargeLocker(actor, customerId,
                            branch, method, amount, approved, paymentKey, now, requestId));
                    LockerAssignment newAssignment = LockerAssignment.paid(lockerId, customerId, start, end, approved,
                            actor);
                    LockerAssignment assignment = lockerAssignmentRepository.saveAndFlush(newAssignment);
                    LockerAssignmentCharge newCharge = LockerAssignmentCharge.builder()
                            .assignmentId(assignment.getId())
                            .paymentId(paymentId)
                            .kind(LockerChargeKind.INITIAL)
                            .startDate(start)
                            .endDate(end)
                            .terms(terms)
                            .ratePer4WeeksSnapshotKrw(rate)
                            .amountKrw(amount)
                            .approved(approved)
                            .at(now)
                            .build();
                    LockerAssignmentCharge charge = lockerAssignmentChargeRepository.saveAndFlush(newCharge);
                    String snapshot = objectMapper.writeValueAsString(toSnapshot(assignment, charge));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, assignment.getId(),
                            assignment.getStatus().name(), null, null, snapshot, requestId));
                    announce(branch, assignment);
                    LockerAssignmentView result = toViews(List.of(assignment), state, today).getFirst();
                    completeRequest(actor, CREATE, scopeKey, key, fingerprint, CREATED, result);

                    return result;
                }
        );
    }

    /**
     * Continues from the current end date, whether that is ahead or already past: an overdue assignment pays for the days
     * it has held. The rate is today's, not the first charge's. A failed payment leaves a failed charge and the old date.
     */
    public LockerAssignmentView extend(
            long actor,
            long id,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only("version", "endDate", "method", "mockOutcome");
        long version = input.version("version");
        LocalDate end = LockerInputs.parseDate(input.text("endDate", DATE_LENGTH), "endDate");
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome")
                || APPROVED_OUTCOME.equals(input.choice("mockOutcome", APPROVED_OUTCOME, FAILED_OUTCOME));
        LockerInputs.requireValidKey(key);
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(id);
        List<Object> fingerprintInput = Arrays.asList(id, version, end.toString(), method, approved);
        byte[] fingerprint = LockerInputs.digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> lockerAssignmentRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY);
                    // Before the version check: the retry of an extension that went through carries the version it
                    // replaced.
                    Optional<LockerAssignmentView> replayed = replay(actor, EXTEND, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    LockerAssignment assignment = lockerAssignmentRepository.findLocked(id)
                            .orElseThrow(LockerAssignmentService::createNotFound);
                    ApiVersion.requireCurrent(version, assignment.getVersion(), toReloadPath(id));

                    if (assignment.getStatus() != LockerAssignmentStatus.ACTIVE) {
                        throw ApiException.conflict("INVALID_STATE", "배정 상태만 연장할 수 있습니다.");
                    }

                    requireOperating(state, branch);
                    Locker locker = lockerRepository.findLocked(assignment.getLockerId()).orElseThrow();
                    requireInService(locker);
                    LocalDate from = assignment.getEndDate();
                    int terms = countTerms(from, end);
                    boolean overlapsAnother = lockerAssignmentRepository.findHolding(locker.getId())
                            .stream()
                            .anyMatch(held -> !held.getId().equals(assignment.getId()) && held.overlaps(from, end));

                    if (overlapsAnother) {
                        throw LockerConflicts.createPeriodConflict();
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    long rate = locker.getRatePer4WeeksKrw();
                    long amount = calculateAmount(rate, terms);
                    String paymentKey = LockerInputs.toStoredKey(PAYMENT_KEY_PREFIX, actor, EXTEND, scopeKey, key);
                    long paymentId = LockerConflicts.translating(() -> paymentService.chargeLocker(actor,
                            assignment.getCustomerId(), branch, method, amount, approved, paymentKey, now, requestId));
                    LockerAssignmentCharge newCharge = LockerAssignmentCharge.builder()
                            .assignmentId(id)
                            .paymentId(paymentId)
                            .kind(LockerChargeKind.EXTENSION)
                            .startDate(from)
                            .endDate(end)
                            .terms(terms)
                            .ratePer4WeeksSnapshotKrw(rate)
                            .amountKrw(amount)
                            .approved(approved)
                            .at(now)
                            .build();
                    LockerAssignmentCharge charge = lockerAssignmentChargeRepository.saveAndFlush(newCharge);

                    if (approved) {
                        assignment.extendTo(end, actor);
                        lockerAssignmentRepository.saveAndFlush(assignment);
                    }

                    Map<String, String> before = Map.of("endDate", from.toString());
                    String action = approved ? "EXTENDED" : "EXTENSION_FAILED";
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, action, null,
                            objectMapper.writeValueAsString(before),
                            objectMapper.writeValueAsString(toSnapshot(assignment, charge)), requestId));
                    announce(branch, assignment);
                    LockerAssignmentView result = toViews(List.of(assignment), state, LockerInputs.toBusinessDate(now))
                            .getFirst();
                    completeRequest(actor, EXTEND, scopeKey, key, fingerprint, OK, result);

                    return result;
                }
        );
    }

    /**
     * Any employee of the branch, also while the branch is stopped or the locker is out of service (FR-BR-08). Nothing is
     * refunded and the end date stays; the locker is free at once because a returned assignment holds no period.
     */
    public LockerAssignmentView returnLocker(
            long actor,
            long id,
            String scope,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.has("reason") ? input.text("reason", MAX_REASON_LENGTH) : null;
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> lockerAssignmentRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    LockerAssignment assignment = lockerAssignmentRepository.findLocked(id)
                            .orElseThrow(LockerAssignmentService::createNotFound);
                    ApiVersion.requireCurrent(version, assignment.getVersion(), toReloadPath(id));

                    if (assignment.getStatus() != LockerAssignmentStatus.ACTIVE) {
                        throw ApiException.conflict("INVALID_STATE", "배정 상태만 반납할 수 있습니다.");
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    assignment.returnLocker(actor, reason, now);
                    lockerAssignmentRepository.saveAndFlush(assignment);

                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("status", assignment.getStatus().name());
                    after.put("returnedAt", now.toString());
                    String action = assignment.getStatus().name();
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, action, reason,
                            objectMapper.writeValueAsString(Map.of("status", LockerAssignmentStatus.ACTIVE.name())),
                            objectMapper.writeValueAsString(after), requestId));
                    announce(branch, assignment);

                    return toViews(List.of(assignment), state, LockerInputs.toBusinessDate(now)).getFirst();
                }
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<LockerAssignmentView> list(
            long actor,
            String scope,
            ListQuery query,
            String lockerId,
            String customerId,
            String status
    ) {
        Long locker = lockerId == null ? null : Long.valueOf(LockerInputs.parseId(lockerId, "lockerId"));
        Long customer = customerId == null ? null : Long.valueOf(LockerInputs.parseId(customerId, "customerId"));
        LockerAssignmentStatus filterStatus = status == null ? null : LockerInputs.parseAssignmentStatus(status);
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        LockerAssignmentSearchRepository.Filter filter = new LockerAssignmentSearchRepository.Filter(
                selected,
                locker,
                customer,
                filterStatus
        );
        LockerAssignmentSearchRepository.Page page = lockerAssignmentSearchRepository.find(
                filter,
                query.offset(),
                query.pageSize()
        );
        List<LockerAssignmentView> items = toViews(page.rows(), state, LockerInputs.toBusinessDate(clock.instant()));

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides assignments outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public LockerAssignmentView get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        if (lockerAssignmentRepository.findBranch(id).filter(selected::contains).isEmpty()) {
            throw createNotFound();
        }

        LockerAssignment assignment = lockerAssignmentRepository.findById(id)
                .orElseThrow(LockerAssignmentService::createNotFound);

        return toViews(List.of(assignment), state, LockerInputs.toBusinessDate(clock.instant())).getFirst();
    }

    /**
     * One customer's assignments at these branches, for a caller that has gated the customer and the branches. Through the
     * same mapping as every other response, so a history cannot disagree with the ledger; amounts and payment references
     * stay subject to {@code state}, branch by branch.
     */
    @Transactional(readOnly = true)
    public List<LockerAssignmentView> ofCustomer(AuthorizationState state, long customerId, Set<Long> branches) {
        List<LockerAssignment> rows = lockerAssignmentSearchRepository.findByCustomer(customerId, branches);

        return toViews(rows, state, LockerInputs.toBusinessDate(clock.instant()));
    }

    /**
     * The customer's latest locker use at any branch, reduced to place and time for the cross-branch summary (M6-D06). A
     * use runs from the start of its first day to the return; until then it has no end, even past the paid period, because
     * the things are still inside. An assignment returned before its start was never used.
     */
    @Transactional(readOnly = true)
    public List<CustomerLockerUsage> recentUsages(long customerId, int limit, Instant now) {
        List<LockerAssignment> rows = lockerAssignmentRepository
                .findHeldBy(customerId, LockerInputs.toBusinessDate(now))
                .stream()
                .filter(LockerAssignmentService::wasUsed)
                .limit(limit)
                .toList();
        List<Long> lockerIds = rows.stream()
                .map(LockerAssignment::getLockerId)
                .distinct()
                .toList();
        Map<Long, Long> branchOf = new HashMap<>();
        lockerRepository.findAllById(lockerIds).forEach(locker -> branchOf.put(locker.getId(), locker.getBranchId()));

        return rows.stream()
                .map(row -> new CustomerLockerUsage(
                        branchOf.get(row.getLockerId()),
                        LockerInputs.toStartInstant(row.getStartDate()),
                        row.getReturnedAt()
                ))
                .toList();
    }

    /** Whole 4-week terms between two dates. A remainder is refused, never prorated (M6-D03). */
    static int countTerms(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);

        if (days <= 0 || days % DAYS_PER_TERM != 0) {
            throw createTermInvalid();
        }

        return (int) (days / DAYS_PER_TERM);
    }

    /** terms × rate from the locker as locked now. A rate may be up to 2^53 - 1 and terms reach 100,000, so this can overflow. */
    static long calculateAmount(long rate, int terms) {
        if (rate > ApiVersion.MAX / terms) {
            throw ApiException.validation("endDate", "결제 금액이 허용 범위를 넘습니다.");
        }

        return rate * terms;
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<LockerAssignmentView> replay(
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

        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            idempotencyStore.discard(prior.get());

            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), LockerAssignmentView.class));
    }

    private void completeRequest(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            int status,
            LockerAssignmentView result
    ) {
        String responseJson = objectMapper.writeValueAsString(result);
        Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
        idempotencyStore.complete(actor, operation, scopeKey, key, fingerprint, status, responseJson, expiresAt);
    }

    /** A locker's state is computed from its assignments, so both lists are stale after any of these commands. */
    private void announce(long branch, LockerAssignment assignment) {
    }

    /** The one mapping behind every response, so the list and the detail cannot disagree. */
    private List<LockerAssignmentView> toViews(List<LockerAssignment> rows, AuthorizationState state, LocalDate today) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> lockerIds = rows.stream()
                .map(LockerAssignment::getLockerId)
                .distinct()
                .toList();
        Map<Long, Locker> byId = new HashMap<>();
        lockerRepository.findAllById(lockerIds).forEach(locker -> byId.put(locker.getId(), locker));
        List<Long> customerIds = rows.stream()
                .map(LockerAssignment::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> people = customerService.summaries(customerIds);
        List<Long> actorIds = Stream.concat(
                        rows.stream().map(LockerAssignment::getAssignedBy),
                        rows.stream().map(LockerAssignment::getLastChangedBy)
                )
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);
        // Only the rows the reader may bill are read at all, so a missing entry means "show nothing".
        Map<Long, List<LockerChargeView>> billed = new HashMap<>();
        List<Long> visible = rows.stream()
                .filter(row -> canSeeCharges(state, byId.get(row.getLockerId()).getBranchId()))
                .map(LockerAssignment::getId)
                .toList();
        visible.forEach(id -> billed.put(id, new ArrayList<>()));

        if (!visible.isEmpty()) {
            lockerAssignmentChargeRepository.findByAssignmentIdInOrderByIdAsc(visible)
                    .forEach(charge -> billed.get(charge.getAssignmentId()).add(toChargeView(charge)));
        }

        return rows.stream()
                .map(row -> toView(row, byId.get(row.getLockerId()), people, names, billed, today))
                .toList();
    }

    /** Amounts and payment references follow the locker's own branch, row by row (contract "결제 정보 노출"). */
    private boolean canSeeCharges(AuthorizationState state, long branch) {
        return authorizationService.permits(state, branch, BranchAction.READ_PAYMENTS)
                || authorizationService.permits(state, branch, BranchAction.WRITE_PAYMENT_HISTORY);
    }

    private static LockerAssignmentView toView(
            LockerAssignment row,
            Locker locker,
            Map<Long, CustomerSummary> people,
            Map<Long, String> names,
            Map<Long, List<LockerChargeView>> billed,
            LocalDate today
    ) {
        return new LockerAssignmentView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getStatus().label(),
                Long.toString(locker.getBranchId()),
                new LockerRef(locker.getId().toString(), locker.getLockerNo()),
                people.get(row.getCustomerId()),
                row.getStartDate(),
                row.getEndDate(),
                row.overdue(today),
                row.getReturnedAt(),
                toActorSummary(row.getAssignedBy(), names),
                toActorSummary(row.getLastChangedBy(), names),
                row.getReason(),
                billed.get(row.getId()),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private static LockerChargeView toChargeView(LockerAssignmentCharge charge) {
        return new LockerChargeView(
                charge.getId().toString(),
                charge.getKind().label(),
                charge.getStartDate(),
                charge.getEndDate(),
                charge.getTerms(),
                charge.getRatePer4WeeksSnapshotKrw(),
                charge.getAmountKrw(),
                charge.getStatus().label(),
                Long.toString(charge.getPaymentId()),
                charge.getCreatedAt(),
                charge.getAppliedAt()
        );
    }

    private static Map<String, Object> toSnapshot(LockerAssignment assignment, LockerAssignmentCharge charge) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", assignment.getStatus().name());
        values.put("lockerId", Long.toString(assignment.getLockerId()));
        values.put("customerId", Long.toString(assignment.getCustomerId()));
        values.put("paymentId", Long.toString(charge.getPaymentId()));
        values.put("startDate", assignment.getStartDate().toString());
        values.put("endDate", assignment.getEndDate().toString());
        values.put("chargeStatus", charge.getStatus().name());
        values.put("chargedFrom", charge.getStartDate().toString());
        values.put("chargedTo", charge.getEndDate().toString());
        values.put("terms", charge.getTerms());
        values.put("ratePer4WeeksKrw", charge.getRatePer4WeeksSnapshotKrw());
        values.put("amountKrw", charge.getAmountKrw());

        return values;
    }

    /** An assignment returned before its start was never used. */
    private static boolean wasUsed(LockerAssignment row) {
        return row.getReturnedAt() == null
                || !LockerInputs.toBusinessDate(row.getReturnedAt()).isBefore(row.getStartDate());
    }

    private static void requireOperating(AuthorizationState state, long branch) {
        if (!ACTIVE_BRANCH.equals(state.branches().get(branch))) {
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 배정하거나 연장할 수 없습니다.");
        }
    }

    private static void requireInService(Locker locker) {
        if (locker.getStatus() == LockerState.BLOCKED) {
            throw ApiException.unprocessable("LOCKER_NOT_AVAILABLE", "운영 중단된 사물함은 배정하거나 연장할 수 없습니다.");
        }
    }

    private static ApiException createTermInvalid() {
        return ApiException.unprocessable("LOCKER_TERM_INVALID", "기간은 오늘 이후에 시작하고 28일의 배수여야 합니다.");
    }

    private static ActorSummary toActorSummary(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    private static ResponseStatusException createNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String toReloadPath(long id) {
        return "/api/locker-assignments/" + id;
    }
}
