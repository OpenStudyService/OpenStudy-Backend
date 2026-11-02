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
    private static final String LOCKERS = "lockers";
    private static final int DAYS_PER_TERM = 28;
    private final LockerAssignmentRepository assignments;
    private final LockerAssignmentSearchRepository search;
    private final LockerAssignmentChargeRepository charges;
    private final LockerRepository lockers;
    private final CustomerService customers;
    private final PaymentService payments;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    public LockerAssignmentView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "lockerId", "customerId", "startDate", "endDate", "method", "mockOutcome");
        long branch = input.id("branchId");
        long lockerId = input.id("lockerId");
        long customerId = input.id("customerId");
        LocalDate start = LockerInputs.date(input.text("startDate", 10), "startDate");
        LocalDate end = LockerInputs.date(input.text("endDate", 10), "endDate");
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome") || "APPROVED".equals(input.choice("mockOutcome", "APPROVED", "FAILED"));
        LockerInputs.validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = LockerInputs.digest(json.writeValueAsString(Arrays.asList(branch, lockerId, customerId, start.toString(),
                end.toString(), method, approved)));
        // WRITE_PAYMENT_HISTORY, not NEW_PAYMENT: a stopped branch must answer BRANCH_NOT_ACTIVE at its place in the table.
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY, () -> {}, state -> {
            var replayed = replay(actor, CREATE, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var locker = lockers.locked(lockerId).filter(found -> policy.permits(state, found.getBranchId(), BranchAction.READ))
                    .orElseThrow(LockerAssignmentService::notFound);
            if (locker.getBranchId() != branch)
                throw ApiException.unprocessable("LOCKER_BRANCH_MISMATCH", "요청한 지점의 사물함이 아닙니다.");
            requireOperating(state, branch);
            requireInService(locker);
            // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate today = LockerInputs.today(now);
            if (start.isBefore(today)) throw termInvalid();
            int terms = terms(start, end);
            customers.requirePurchasable(state, customerId);
            var holding = assignments.holding(lockerId);
            if (holding.stream().anyMatch(held -> held.overdue(today))) throw LockerConflicts.notReturned();
            if (holding.stream().anyMatch(held -> held.overlaps(start, end))) throw LockerConflicts.periodConflict();
            long rate = locker.getRatePer4WeeksKrw();
            long amount = amount(rate, terms);
            long paymentId = LockerConflicts.translating(() -> payments.chargeLocker(actor, customerId, branch, method, amount, approved,
                    LockerInputs.storedKey("LPAY-", actor, CREATE, scopeKey, key), now, requestId));
            var assignment = assignments.saveAndFlush(LockerAssignment.paid(lockerId, customerId, start, end, approved, actor));
            var charge = charges.saveAndFlush(new LockerAssignmentCharge(assignment.getId(), paymentId, LockerChargeKind.INITIAL,
                    start, end, terms, rate, amount, approved, now));
            audit.record(AuditEvent.change(actor, branch, ENTITY, assignment.getId(), assignment.getStatus().name(), null, null,
                    json.writeValueAsString(snapshot(assignment, charge)), requestId));
            announce(branch, assignment);
            var result = views(List.of(assignment), state, today).getFirst();
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /**
     * Continues from the current end date, whether that is ahead or already past: an overdue assignment pays for the days
     * it has held. The rate is today's, not the first charge's. A failed payment leaves a failed charge and the old date.
     */
    public LockerAssignmentView extend(long actor, long id, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "endDate", "method", "mockOutcome");
        long version = input.version("version");
        LocalDate end = LockerInputs.date(input.text("endDate", 10), "endDate");
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome") || "APPROVED".equals(input.choice("mockOutcome", "APPROVED", "FAILED"));
        LockerInputs.validKey(key);
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = LockerInputs.digest(json.writeValueAsString(Arrays.asList(id, version, end.toString(), method, approved)));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> assignments.branchOf(id).filter(selected::contains), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY);
            // Before the version check: the retry of an extension that went through carries the version it replaced.
            var replayed = replay(actor, EXTEND, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var assignment = assignments.locked(id).orElseThrow(LockerAssignmentService::notFound);
            ApiVersion.requireCurrent(version, assignment.getVersion(), reload(id));
            if (assignment.getStatus() != LockerAssignmentStatus.ACTIVE)
                throw ApiException.conflict("INVALID_STATE", "배정 상태만 연장할 수 있습니다.");
            requireOperating(state, branch);
            var locker = lockers.locked(assignment.getLockerId()).orElseThrow();
            requireInService(locker);
            LocalDate from = assignment.getEndDate();
            int terms = terms(from, end);
            if (assignments.holding(locker.getId()).stream()
                    .anyMatch(held -> !held.getId().equals(assignment.getId()) && held.overlaps(from, end)))
                throw LockerConflicts.periodConflict();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            long rate = locker.getRatePer4WeeksKrw();
            long amount = amount(rate, terms);
            long paymentId = LockerConflicts.translating(() -> payments.chargeLocker(actor, assignment.getCustomerId(), branch, method, amount,
                    approved, LockerInputs.storedKey("LPAY-", actor, EXTEND, scopeKey, key), now, requestId));
            var charge = charges.saveAndFlush(new LockerAssignmentCharge(id, paymentId, LockerChargeKind.EXTENSION, from, end, terms,
                    rate, amount, approved, now));
            if (approved) {
                assignment.extendTo(end, actor);
                assignments.saveAndFlush(assignment);
            }
            var before = Map.of("endDate", from.toString());
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, approved ? "EXTENDED" : "EXTENSION_FAILED", null,
                    json.writeValueAsString(before), json.writeValueAsString(snapshot(assignment, charge)), requestId));
            announce(branch, assignment);
            var result = views(List.of(assignment), state, LockerInputs.today(now)).getFirst();
            requests.complete(actor, EXTEND, scopeKey, key, fingerprint, 200, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /**
     * Any employee of the branch, also while the branch is stopped or the locker is out of service (FR-BR-08). Nothing is
     * refunded and the end date stays; the locker is free at once because a returned assignment holds no period.
     */
    public LockerAssignmentView returnLocker(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.has("reason") ? input.text("reason", 500) : null;
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> assignments.branchOf(id).filter(selected::contains), (state, branch) -> {
            var assignment = assignments.locked(id).orElseThrow(LockerAssignmentService::notFound);
            ApiVersion.requireCurrent(version, assignment.getVersion(), reload(id));
            if (assignment.getStatus() != LockerAssignmentStatus.ACTIVE)
                throw ApiException.conflict("INVALID_STATE", "배정 상태만 반납할 수 있습니다.");
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            assignment.returnLocker(actor, reason, now);
            assignments.saveAndFlush(assignment);
            var after = new LinkedHashMap<String, Object>();
            after.put("status", assignment.getStatus().name());
            after.put("returnedAt", now.toString());
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, assignment.getStatus().name(), reason,
                    json.writeValueAsString(Map.of("status", LockerAssignmentStatus.ACTIVE.name())), json.writeValueAsString(after), requestId));
            announce(branch, assignment);
            return views(List.of(assignment), state, LockerInputs.today(now)).getFirst();
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<LockerAssignmentView> list(long actor, String scope, ListQuery query, String lockerId, String customerId, String status) {
        Long locker = lockerId == null ? null : Long.valueOf(LockerInputs.id(lockerId, "lockerId"));
        Long customer = customerId == null ? null : Long.valueOf(LockerInputs.id(customerId, "customerId"));
        var filterStatus = status == null ? null : LockerInputs.assignmentStatus(status);
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var page = search.find(new LockerAssignmentSearchRepository.Filter(selected, locker, customer, filterStatus), query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state, LockerInputs.today(clock.instant())),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides assignments outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public LockerAssignmentView get(long actor, long id, String scope) {
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        if (assignments.branchOf(id).filter(selected::contains).isEmpty()) throw notFound();
        return views(List.of(assignments.findById(id).orElseThrow(LockerAssignmentService::notFound)), state,
                LockerInputs.today(clock.instant())).getFirst();
    }

    /**
     * One customer's assignments at these branches, for a caller that has gated the customer and the branches. Through the
     * same mapping as every other response, so a history cannot disagree with the ledger; amounts and payment references
     * stay subject to {@code state}, branch by branch.
     */
    @Transactional(readOnly = true)
    public List<LockerAssignmentView> ofCustomer(AuthorizationState state, long customerId, Set<Long> branches) {
        return views(search.ofCustomer(customerId, branches), state, LockerInputs.today(clock.instant()));
    }

    /**
     * The customer's latest locker use at any branch, reduced to place and time for the cross-branch summary (M6-D06). A
     * use runs from the start of its first day to the return; until then it has no end, even past the paid period, because
     * the things are still inside. An assignment returned before its start was never used.
     */
    @Transactional(readOnly = true)
    public List<CustomerLockerUsage> recentUsages(long customerId, int limit, Instant now) {
        var rows = assignments.heldBy(customerId, LockerInputs.today(now)).stream().filter(row -> row.getReturnedAt() == null
                || !LockerInputs.today(row.getReturnedAt()).isBefore(row.getStartDate())).limit(limit).toList();
        Map<Long, Long> branchOf = new HashMap<>();
        lockers.findAllById(rows.stream().map(LockerAssignment::getLockerId).distinct().toList())
                .forEach(locker -> branchOf.put(locker.getId(), locker.getBranchId()));
        return rows.stream().map(row -> new CustomerLockerUsage(branchOf.get(row.getLockerId()), LockerInputs.startOf(row.getStartDate()),
                row.getReturnedAt())).toList();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<LockerAssignmentView> replay(long actor, String operation, String scopeKey, String key, byte[] fingerprint) {
        var prior = requests.lock(actor, operation, scopeKey, key);
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), LockerAssignmentView.class));
    }

    private static void requireOperating(AuthorizationState state, long branch) {
        if (!"ACTIVE".equals(state.branches().get(branch)))
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 배정하거나 연장할 수 없습니다.");
    }

    private static void requireInService(Locker locker) {
        if (locker.getStatus() == LockerState.BLOCKED)
            throw ApiException.unprocessable("LOCKER_NOT_AVAILABLE", "운영 중단된 사물함은 배정하거나 연장할 수 없습니다.");
    }

    /** Whole 4-week terms between two dates. A remainder is refused, never prorated (M6-D03). */
    static int terms(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);
        if (days <= 0 || days % DAYS_PER_TERM != 0) throw termInvalid();
        return (int) (days / DAYS_PER_TERM);
    }

    private static ApiException termInvalid() {
        return ApiException.unprocessable("LOCKER_TERM_INVALID", "기간은 오늘 이후에 시작하고 28일의 배수여야 합니다.");
    }

    /** terms × rate from the locker as locked now. A rate may be up to 2^53 - 1 and terms reach 100,000, so this can overflow. */
    static long amount(long rate, int terms) {
        if (rate > ApiVersion.MAX / terms) throw ApiException.validation("endDate", "결제 금액이 허용 범위를 넘습니다.");
        return rate * terms;
    }

    /** A locker's state is computed from its assignments, so both lists are stale after any of these commands. */
    private void announce(long branch, LockerAssignment assignment) {
        outbox.invalidate(branch, ENTITY, assignment.getId());
        outbox.invalidate(branch, LOCKERS, assignment.getLockerId());
    }

    private static Map<String, Object> snapshot(LockerAssignment assignment, LockerAssignmentCharge charge) {
        var values = new LinkedHashMap<String, Object>();
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

    /** The one mapping behind every response, so the list and the detail cannot disagree. */
    private List<LockerAssignmentView> views(List<LockerAssignment> rows, AuthorizationState state, LocalDate today) {
        if (rows.isEmpty()) return List.of();
        Map<Long, Locker> byId = new HashMap<>();
        lockers.findAllById(rows.stream().map(LockerAssignment::getLockerId).distinct().toList()).forEach(locker -> byId.put(locker.getId(), locker));
        var people = customers.summaries(rows.stream().map(LockerAssignment::getCustomerId).toList());
        var names = employees.names(Stream.concat(rows.stream().map(LockerAssignment::getAssignedBy), rows.stream().map(LockerAssignment::getLastChangedBy))
                .filter(Objects::nonNull).toList());
        // Only the rows the reader may bill are read at all, so a missing entry means "show nothing".
        Map<Long, List<LockerChargeView>> billed = new HashMap<>();
        var visible = rows.stream().filter(row -> chargesVisible(state, byId.get(row.getLockerId()).getBranchId())).map(LockerAssignment::getId).toList();
        visible.forEach(id -> billed.put(id, new ArrayList<>()));
        if (!visible.isEmpty()) charges.findByAssignmentIdInOrderByIdAsc(visible).forEach(charge -> billed.get(charge.getAssignmentId()).add(view(charge)));
        return rows.stream().map(row -> {
            var locker = byId.get(row.getLockerId());
            return new LockerAssignmentView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), row.getStatus().label(),
                    Long.toString(locker.getBranchId()), new LockerRef(locker.getId().toString(), locker.getLockerNo()), people.get(row.getCustomerId()),
                    row.getStartDate(), row.getEndDate(), row.overdue(today), row.getReturnedAt(), actor(row.getAssignedBy(), names),
                    actor(row.getLastChangedBy(), names), row.getReason(), billed.get(row.getId()), row.getCreatedAt(), row.getUpdatedAt());
        }).toList();
    }

    /** Amounts and payment references follow the locker's own branch, row by row (contract "결제 정보 노출"). */
    private boolean chargesVisible(AuthorizationState state, long branch) {
        return policy.permits(state, branch, BranchAction.READ_PAYMENTS)
                || policy.permits(state, branch, BranchAction.WRITE_PAYMENT_HISTORY);
    }

    private static LockerChargeView view(LockerAssignmentCharge charge) {
        return new LockerChargeView(charge.getId().toString(), charge.getKind().label(), charge.getStartDate(), charge.getEndDate(), charge.getTerms(),
                charge.getRatePer4WeeksSnapshotKrw(), charge.getAmountKrw(), charge.getStatus().label(), Long.toString(charge.getPaymentId()),
                charge.getCreatedAt(), charge.getAppliedAt());
    }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    private static String reload(long id) { return "/api/locker-assignments/" + id; }
}
