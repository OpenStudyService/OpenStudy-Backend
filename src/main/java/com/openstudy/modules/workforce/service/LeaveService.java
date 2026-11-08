package com.openstudy.modules.workforce.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

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
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workforce.dto.response.ActorSummary;
import com.openstudy.modules.workforce.dto.response.LeaveAllocationView;
import com.openstudy.modules.workforce.dto.response.LeaveBalanceEventView;
import com.openstudy.modules.workforce.dto.response.LeaveBalanceGrantView;
import com.openstudy.modules.workforce.dto.response.LeaveBalanceView;
import com.openstudy.modules.workforce.dto.response.LeaveGrantDetailView;
import com.openstudy.modules.workforce.dto.response.LeaveGrantView;
import com.openstudy.modules.workforce.dto.response.LeaveRequestView;
import com.openstudy.modules.workforce.dto.response.RequestCancellation;
import com.openstudy.modules.workforce.dto.response.RequestDecision;
import com.openstudy.modules.workforce.entity.LeaveAllocation;
import com.openstudy.modules.workforce.entity.LeaveBalanceEvent;
import com.openstudy.modules.workforce.entity.LeaveGrant;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.ShiftStatus;
import com.openstudy.modules.workforce.repository.LeaveAllocationRepository;
import com.openstudy.modules.workforce.repository.LeaveBalanceEventRepository;
import com.openstudy.modules.workforce.repository.LeaveGrantRepository;
import com.openstudy.modules.workforce.repository.LeaveRequestRepository;
import com.openstudy.modules.workforce.repository.LeaveSearchRepository;
import com.openstudy.modules.workforce.repository.ShiftRepository;

/**
 * Leave grants, balances and requests (#108, FR-LEAVE-01~06, ADR-M12-001 M12-D02·D03·D06·D07,
 * workforce-requests-api.md). A grant and a request are decided by the MANAGER of their branch (origin branch, request
 * branch) or the OWNER, never by the requester of their own request (2026-11-09 user decision); the balance is per
 * employee. Every balance change writes a leave_balance_events row whose unique event_key keeps a use or a restore of
 * one request to one application (M12-D07).
 * <p>Writes run inside the foundation guard of {@link AuthorizedCommands} and then take the employee's row FOR UPDATE,
 * like shift writes: a shift registration and a leave approval for one employee cannot both pass their overlap checks
 * (M12-D03). Grants are locked in id order or expiry order after the request row.</p>
 */
@Service
@RequiredArgsConstructor
public class LeaveService {
    private static final String CREATE_GRANT = "leave-grants:create";
    private static final String CREATE_REQUEST = "leave-requests:create";
    private static final List<LeaveRequest.Status> OPEN = List.of(LeaveRequest.Status.REQUESTED, LeaveRequest.Status.APPROVED);
    private final LeaveGrantRepository grants;
    private final LeaveRequestRepository requests;
    private final LeaveAllocationRepository allocations;
    private final LeaveBalanceEventRepository ledger;
    private final LeaveSearchRepository search;
    private final ShiftRepository shifts;
    private final RecordScope readers;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore keys;
    private final ObjectMapper json;
    private final Clock clock;

    // ---- grants ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<LeaveGrantView> listGrants(long actor, String scope, ListQuery query, String employeeId, String mine,
            String grantYear, String includeExpired) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        Integer year = grantYear == null ? null : year(grantYear);
        boolean expired = ShiftInputs.flag(includeExpired, "includeExpired");
        var state = policy.current(actor);
        var filter = filter(state, scope, employee, own);
        if (filter.isEmpty()) return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
        var page = search.grants(filter.get(), year, expired ? null : today(), query.offset(), query.pageSize());
        return new PageResponse<>(grantViews(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public LeaveGrantDetailView getGrant(long actor, long id) {
        var state = policy.current(actor);
        return detail(grants.findById(id).filter(grant -> sees(state, grant)).orElseThrow(LeaveService::notFound), state);
    }

    /** 403, malformed input, the grant rules, a stopped branch, then the employee (workforce-requests-api.md "부여"). */
    public LeaveGrantView createGrant(long actor, String key, Map<String, Object> body) {
        var input = new StrictInput(body);
        long branch = input.id("originBranchId");
        var reader = policy.current(actor);
        if (!readers.manages(reader, branch)) throw AuthorizationService.forbidden();
        input.only("employeeId", "originBranchId", "grantYear", "validFrom", "validThrough", "grantedDays", "reason");
        long employee = input.id("employeeId");
        long year = LeaveInputs.integer(body, "grantYear");
        LocalDate from = LeaveInputs.date(body, "validFrom");
        LocalDate through = LeaveInputs.date(body, "validThrough");
        BigDecimal days = LeaveInputs.days(body, "grantedDays");
        String reason = input.text("reason", 500);
        ShiftInputs.validKey(key);
        if (year < 2000 || year > 2100) throw LeaveInputs.grant("grantYear", "부여 연도는 2000~2100 사이여야 합니다.");
        if (through.isBefore(from)) throw LeaveInputs.grant("validThrough", "적용 종료일은 시작일보다 앞설 수 없습니다.");
        if (!LeaveInputs.halfDays(days) || days.compareTo(LeaveInputs.HALF) < 0 || days.compareTo(LeaveInputs.MAX_DAYS) > 0)
            throw LeaveInputs.grant("grantedDays", "부여 일수는 0.5일 단위로 0.5~366일이어야 합니다.");
        BigDecimal stored = LeaveInputs.stored(days);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(Arrays.asList(employee, branch, year, from.toString(),
                through.toString(), stored.toPlainString(), reason)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_SHIFTS, () -> {
            prior.set(keys.lock(actor, CREATE_GRANT, scopeKey, key));
            shifts.lockEmployee(employee);
        }, state -> {
            var replayed = replay(prior.get(), fingerprint, LeaveGrantView.class);
            if (replayed.isPresent()) return replayed.get();
            requireOperating(state, branch, "운영 중지된 지점에서는 휴가를 부여할 수 없습니다.");
            if (shifts.activeMember(employee, branch).isEmpty())
                throw ApiException.unprocessable("INVALID_LEAVE_EMPLOYEE", "원부여 지점의 활성 소속 직원에게만 휴가를 부여할 수 있습니다.");
            var grant = grants.saveAndFlush(new LeaveGrant(employee, branch, (int) year, from, through, stored, reason, actor));
            ledger.saveAndFlush(new LeaveBalanceEvent(grant.getId(), null, LeaveBalanceEvent.Type.GRANT, stored, reason, actor,
                    "grant:" + grant.getId()));
            var result = grantViews(List.of(grant), state).getFirst();
            keys.complete(actor, CREATE_GRANT, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** remaining_days only, with an ADJUST row; also at a stopped branch, since it corrects a record. */
    public LeaveGrantDetailView adjustGrant(long actor, long id, Map<String, Object> body) {
        var reader = policy.current(actor);
        var found = grants.findById(id).filter(grant -> sees(reader, grant)).orElseThrow(LeaveService::notFound);
        if (!readers.manages(reader, found.getOriginBranchId())) throw AuthorizationService.forbidden();
        var input = new StrictInput(body).only("version", "deltaDays", "reason");
        long version = input.version("version");
        BigDecimal delta = LeaveInputs.days(body, "deltaDays");
        String reason = input.text("reason", 500);
        if (delta.signum() == 0 || !LeaveInputs.halfDays(delta) || delta.abs().compareTo(LeaveInputs.MAX_DAYS) > 0)
            throw LeaveInputs.grant("deltaDays", "보정 일수는 0이 아닌 0.5일 단위로 -366~366일이어야 합니다.");
        BigDecimal stored = LeaveInputs.stored(delta);
        var locked = new AtomicReference<Optional<LeaveGrant>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            shifts.lockEmployee(found.getEmployeeId());
            locked.set(grants.locked(id));
        }, state -> {
            var grant = locked.get().filter(row -> sees(state, row)).orElseThrow(LeaveService::notFound);
            if (!readers.manages(state, grant.getOriginBranchId())) throw AuthorizationService.forbidden();
            ApiVersion.requireCurrent(version, grant.getVersion(), "/api/leave-grants/" + id);
            if (grant.getRemainingDays().add(stored).signum() < 0) throw WorkforceRequestRefusal.balance(stored.negate(), grant.getRemainingDays());
            grant.move(stored);
            var saved = grants.saveAndFlush(grant);
            ledger.saveAndFlush(new LeaveBalanceEvent(id, null, LeaveBalanceEvent.Type.ADJUST, stored, reason, actor,
                    "adjust:" + id + ":" + saved.getVersion()));
            return detail(saved, state);
        });
    }

    /** The requester's own balance, whatever branch granted it (M12-D02). */
    @Transactional(readOnly = true)
    public LeaveBalanceView myBalance(long actor) {
        policy.current(actor);
        return balance(actor);
    }

    /** OWNER, or the MANAGER of a branch where the employee is an active member; otherwise 404, as if there were none. */
    @Transactional(readOnly = true)
    public LeaveBalanceView employeeBalance(long actor, long employee) {
        var state = policy.current(actor);
        boolean allowed = state.owner() ? !employees.names(Set.of(employee)).isEmpty()
                : grants.activeBranches(employee).stream().anyMatch(branch -> readers.manages(state, branch.longValue()));
        if (!allowed) throw notFound();
        return balance(employee);
    }

    // ---- requests -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestView> listRequests(long actor, String scope, ListQuery query, String employeeId, String mine,
            String status, String from, String to) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        LeaveRequest.Status filterStatus = status == null ? null : LeaveInputs.status(status);
        LocalDate first = from == null ? null : ShiftInputs.date(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.date(to, "to");
        if (first != null && last != null && last.isBefore(first)) throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        var state = policy.current(actor);
        var filter = filter(state, scope, employee, own);
        if (filter.isEmpty()) return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
        var page = search.requests(filter.get(), filterStatus, first, last, query.offset(), query.pageSize());
        return new PageResponse<>(requestViews(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public LeaveRequestView getRequest(long actor, long id) {
        var state = policy.current(actor);
        return requestViews(List.of(visibleRequest(state, id)), state).getFirst();
    }

    /**
     * Malformed input, a branch that is not the requester's active one, the period rules, a stopped branch, an overlap,
     * then the balance. The balance check only informs: the days are taken when it is approved (M12-D06).
     */
    public LeaveRequestView createRequest(long actor, String key, Map<String, Object> body) {
        var input = new StrictInput(body).only("branchId", "startsOn", "endsOn", "requestedDays", "reason");
        long branch = input.id("branchId");
        LocalDate startsOn = LeaveInputs.date(body, "startsOn");
        LocalDate endsOn = LeaveInputs.date(body, "endsOn");
        BigDecimal days = LeaveInputs.days(body, "requestedDays");
        String reason = input.text("reason", 500);
        if (!member(policy.current(actor), branch)) throw AuthorizationService.forbidden();
        LeaveInputs.requirePeriod(startsOn, endsOn, days);
        ShiftInputs.validKey(key);
        BigDecimal stored = LeaveInputs.stored(days);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(Arrays.asList(branch, startsOn.toString(), endsOn.toString(),
                stored.toPlainString(), reason)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            prior.set(keys.lock(actor, CREATE_REQUEST, scopeKey, key));
            shifts.lockEmployee(actor);
        }, state -> {
            var replayed = replay(prior.get(), fingerprint, LeaveRequestView.class);
            if (replayed.isPresent()) return replayed.get();
            if (!member(state, branch)) throw AuthorizationService.forbidden();
            requireOperating(state, branch, "운영 중지된 지점으로는 휴가를 신청할 수 없습니다.");
            var overlapping = requests.overlapping(actor, OPEN, startsOn, endsOn, 0);
            if (!overlapping.isEmpty()) throw WorkforceRequestRefusal.overlap(overlapping.stream().map(row -> row.getId().toString()).toList());
            var available = total(grants.available(actor, startsOn, endsOn));
            if (available.compareTo(stored) < 0) throw WorkforceRequestRefusal.balance(stored, available);
            var request = requests.saveAndFlush(new LeaveRequest(actor, branch, startsOn, endsOn, stored, reason));
            var result = requestViews(List.of(request), state).getFirst();
            keys.complete(actor, CREATE_REQUEST, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /**
     * Another approved leave, a scheduled shift in the period (M12-D03), then the balance; the days come from the
     * grants that cover the period, earliest expiry first (M12-D06), each with an allocation and a USE row (M12-D07).
     */
    public LeaveRequestView approve(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = decidable(actor, id);
        return decide(actor, id, employee, version, LeaveRequest.Status.REQUESTED, "이미 처리된 휴가 신청입니다.", (state, request) -> {
            var other = requests.overlapping(employee, List.of(LeaveRequest.Status.APPROVED), request.getStartsOn(), request.getEndsOn(), id);
            if (!other.isEmpty())
                throw WorkforceRequestRefusal.overlap(other.stream().filter(row -> sees(state, row)).map(row -> row.getId().toString()).toList());
            Instant start = ShiftInputs.startOf(request.getStartsOn());
            Instant end = ShiftInputs.startOf(request.getEndsOn().plusDays(1));
            var taken = shifts.overlapping(employee, ShiftStatus.SCHEDULED, start, end);
            if (!taken.isEmpty()) {
                var readable = readers.readable(state);
                throw WorkforceRequestRefusal.shifts(ShiftConflicts.of(start, end, taken,
                        shift -> readers.sees(state, readable, shift.getBranchId(), shift.getEmployeeId()), List.of()));
            }
            var usable = grants.usable(employee, request.getStartsOn(), request.getEndsOn());
            var available = total(usable);
            if (available.compareTo(request.getRequestedDays()) < 0) throw WorkforceRequestRefusal.balance(request.getRequestedDays(), available);
            BigDecimal left = request.getRequestedDays();
            for (var grant : usable) {
                if (left.signum() == 0) break;
                BigDecimal take = grant.getRemainingDays().min(left);
                grant.move(take.negate());
                grants.save(grant);
                allocations.save(new LeaveAllocation(id, grant.getId(), take));
                record(new LeaveBalanceEvent(grant.getId(), (Long) id, LeaveBalanceEvent.Type.USE, take.negate(), null, actor,
                        "leave:" + id + ":use:" + grant.getId()), request);
                left = left.subtract(take);
            }
            request.approve(actor, clock.instant());
        });
    }

    /** The balance does not change. */
    public LeaveRequestView reject(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = decidable(actor, id);
        return decide(actor, id, employee, version, LeaveRequest.Status.REQUESTED, "이미 처리된 휴가 신청입니다.",
                (state, request) -> request.reject(actor, clock.instant()));
    }

    /** The requester withdraws a request that nobody has decided; also after their membership ended (tidying up). */
    public LeaveRequestView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var reader = policy.current(actor);
        var found = visibleRequest(reader, id);
        if (found.getEmployeeId() != actor) throw AuthorizationService.forbidden();
        var locked = new AtomicReference<Optional<LeaveRequest>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            shifts.lockEmployee(actor);
            locked.set(requests.locked(id));
        }, state -> {
            var request = locked.get().filter(row -> sees(state, row)).orElseThrow(LeaveService::notFound);
            if (request.getEmployeeId() != actor) throw AuthorizationService.forbidden();
            requireStatus(request, LeaveRequest.Status.REQUESTED, "승인 전 신청만 취소할 수 있습니다.");
            ApiVersion.requireCurrent(version, request.getVersion(), reload(id));
            request.withdraw(actor, clock.instant());
            return requestViews(List.of(requests.saveAndFlush(request)), state).getFirst();
        });
    }

    /**
     * Before the first day only (2026-11-09 user decision). Each allocation goes back to its own grant, expired or not,
     * with a RESTORE row; the approver stays in decided_by.
     */
    public LeaveRequestView cancelApproval(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = decidable(actor, id);
        return decide(actor, id, employee, version, LeaveRequest.Status.APPROVED, "승인된 휴가만 승인 취소할 수 있습니다.", (state, request) -> {
            if (!today().isBefore(request.getStartsOn()))
                throw ApiException.conflict("LEAVE_ALREADY_STARTED", "시작일이 지난 휴가는 승인을 취소할 수 없습니다.");
            Instant now = clock.instant();
            var taken = allocations.lockedFor(id).stream().filter(allocation -> allocation.getRestoredAt() == null).toList();
            var locked = grants.lockedAll(taken.stream().map(LeaveAllocation::getLeaveGrantId).toList()).stream()
                    .collect(Collectors.toMap(LeaveGrant::getId, grant -> grant));
            for (var allocation : taken) {
                var grant = locked.get(allocation.getLeaveGrantId());
                grant.move(allocation.getAllocatedDays());
                grants.save(grant);
                allocation.restore(now);
                allocations.save(allocation);
                record(new LeaveBalanceEvent(grant.getId(), (Long) id, LeaveBalanceEvent.Type.RESTORE, allocation.getAllocatedDays(), null,
                        actor, "leave:" + id + ":restore:" + grant.getId()), request);
            }
            request.cancelApproval();
        });
    }

    private interface Decision { void apply(AuthorizationState state, LeaveRequest request); }

    /** Before any lock: 404 for a request the reader cannot see, 403 unless they manage its branch, 403 for their own. */
    private long decidable(long actor, long id) {
        var state = policy.current(actor);
        var request = visibleRequest(state, id);
        requireDecider(state, request);
        return request.getEmployeeId();
    }

    /**
     * Shared frame of approve, reject and cancel-approval: the guard, the requester's row, the request row, the same 404
     * and 403s on the state read under lock, then the status before the version (a repeated command is told what
     * happened), then the command.
     */
    private LeaveRequestView decide(long actor, long id, long employee, long version, LeaveRequest.Status from, String wrongState,
            Decision decision) {
        var locked = new AtomicReference<Optional<LeaveRequest>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            shifts.lockEmployee(employee);
            locked.set(requests.locked(id));
        }, state -> {
            var request = locked.get().filter(row -> sees(state, row)).orElseThrow(LeaveService::notFound);
            requireDecider(state, request);
            requireStatus(request, from, wrongState);
            ApiVersion.requireCurrent(version, request.getVersion(), reload(id));
            decision.apply(state, request);
            return requestViews(List.of(requests.saveAndFlush(request)), state).getFirst();
        });
    }

    private void requireDecider(AuthorizationState state, LeaveRequest request) {
        if (!readers.manages(state, request.getBranchId())) throw AuthorizationService.forbidden();
        if (request.getEmployeeId() == state.employeeId()) throw WorkforceRequestRefusal.selfDecision();
    }

    private static void requireStatus(LeaveRequest request, LeaveRequest.Status status, String message) {
        if (request.getStatus() != status)
            throw WorkforceRequestRefusal.invalidState(message, ApiVersion.fromDatabase(request.getVersion()), reload(request.getId()));
    }

    /** The unique event_key is the last guard against a second use or restore of one request (M12-D07). */
    private void record(LeaveBalanceEvent event, LeaveRequest request) {
        try {
            ledger.saveAndFlush(event);
        } catch (DataIntegrityViolationException exception) {
            throw WorkforceRequestRefusal.invalidState("이미 반영된 휴가입니다.", ApiVersion.fromDatabase(request.getVersion()), reload(request.getId()));
        }
    }

    // ---- reading --------------------------------------------------------------------------------------------

    /**
     * The reader condition of the lists: the managed branches of the scope, and the reader's own rows, at every branch for
     * scope=all or at the scope's branch. mine and an employee filter only narrow it; naming another employee with mine
     * finds nothing.
     */
    private Optional<LeaveSearchRepository.Filter> filter(AuthorizationState state, String scope, Long employee, boolean own) {
        Set<Long> readable = policy.scope(state, scope, BranchAction.READ);
        Set<Long> managed = readable.stream().filter(branch -> readers.manages(state, branch)).collect(Collectors.toSet());
        Set<Long> ownBranches = scope == null || "all".equals(scope) ? null : readable;
        Long person = employee;
        if (own) {
            if (employee != null && employee != state.employeeId()) return Optional.empty();
            person = state.employeeId();
            managed = Set.of();
        }
        return Optional.of(new LeaveSearchRepository.Filter(managed, state.employeeId(), ownBranches, person));
    }

    private LeaveRequest visibleRequest(AuthorizationState state, long id) {
        return requests.findById(id).filter(request -> sees(state, request)).orElseThrow(LeaveService::notFound);
    }

    /** OWNER, the MANAGER of the deciding branch, or the employee: leave is per employee, so whatever their membership now. */
    private boolean sees(AuthorizationState state, LeaveRequest request) {
        return request.getEmployeeId() == state.employeeId() || readers.manages(state, request.getBranchId());
    }

    private boolean sees(AuthorizationState state, LeaveGrant grant) {
        return grant.getEmployeeId() == state.employeeId() || readers.manages(state, grant.getOriginBranchId());
    }

    /** An active membership row at the branch; an OWNER without one cannot request leave. */
    private static boolean member(AuthorizationState state, long branch) {
        var membership = state.memberships().get(branch);
        return "ACTIVE".equals(state.status()) && membership != null && "ACTIVE".equals(membership.status());
    }

    private static void requireOperating(AuthorizationState state, long branch, String message) {
        if (!"ACTIVE".equals(state.branches().get(branch))) throw ApiException.conflict("BRANCH_NOT_ACTIVE", message);
    }

    private LeaveBalanceView balance(long employee) {
        var rows = grants.findByEmployeeIdOrderByValidThroughAscIdAsc(employee);
        LocalDate today = today();
        var names = employees.names(Set.of(employee));
        BigDecimal total = rows.stream().filter(grant -> !grant.getValidThrough().isBefore(today)).map(LeaveGrant::getRemainingDays)
                .reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
        return new LeaveBalanceView(actor(employee, names), clock.instant(), total, rows.stream().map(grant -> new LeaveBalanceGrantView(
                grant.getId().toString(), Long.toString(grant.getOriginBranchId()), grant.getGrantYear(), grant.getValidFrom(),
                grant.getValidThrough(), grant.getGrantedDays(), grant.getRemainingDays(), grant.getValidThrough().isBefore(today))).toList());
    }

    private List<LeaveGrantView> grantViews(List<LeaveGrant> rows, AuthorizationState state) {
        var people = new LinkedHashSet<Long>();
        rows.forEach(grant -> { people.add(grant.getEmployeeId()); people.add(grant.getGrantedBy()); });
        var names = employees.names(people);
        LocalDate today = today();
        return rows.stream().map(grant -> new LeaveGrantView(grant.getId().toString(), ApiVersion.fromDatabase(grant.getVersion()),
                actor(grant.getEmployeeId(), names), Long.toString(grant.getOriginBranchId()), grant.getGrantYear(), grant.getValidFrom(),
                grant.getValidThrough(), grant.getGrantedDays(), grant.getRemainingDays(), grant.getValidThrough().isBefore(today),
                grant.getReason(), actor(grant.getGrantedBy(), names), grant.getCreatedAt(),
                readers.manages(state, grant.getOriginBranchId()))).toList();
    }

    private LeaveGrantDetailView detail(LeaveGrant grant, AuthorizationState state) {
        var view = grantViews(List.of(grant), state).getFirst();
        var events = ledger.findByLeaveGrantIdOrderByIdAsc(grant.getId());
        var names = employees.names(events.stream().map(LeaveBalanceEvent::getActorEmployeeId).toList());
        return new LeaveGrantDetailView(view.id(), view.version(), view.employee(), view.originBranchId(), view.grantYear(),
                view.validFrom(), view.validThrough(), view.grantedDays(), view.remainingDays(), view.expired(), view.reason(),
                view.grantedBy(), view.createdAt(), view.canAdjust(), events.stream().map(event -> new LeaveBalanceEventView(
                        event.getEventType().label, event.getDeltaDays(),
                        event.getLeaveRequestId() == null ? null : event.getLeaveRequestId().toString(), event.getReason(),
                        actor(event.getActorEmployeeId(), names), event.getCreatedAt())).toList());
    }

    /**
     * The one mapping behind every request response. A cancelled request with allocations was approved first: its
     * decision is the approval and its cancellation comes from the RESTORE rows; without allocations it was withdrawn
     * before any decision, by the requester recorded in decided_*.
     */
    private List<LeaveRequestView> requestViews(List<LeaveRequest> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(LeaveRequest::getId).toList();
        Map<Long, List<LeaveAllocation>> allocated = new HashMap<>();
        allocations.findByLeaveRequestIdInOrderByIdAsc(ids).forEach(row -> allocated.computeIfAbsent(row.getLeaveRequestId(), k -> new ArrayList<>()).add(row));
        Map<Long, LeaveBalanceEvent> restored = new HashMap<>();
        ledger.findByLeaveRequestIdInAndEventTypeOrderByIdAsc(ids, LeaveBalanceEvent.Type.RESTORE).forEach(row -> restored.putIfAbsent(row.getLeaveRequestId(), row));
        var people = new LinkedHashSet<Long>();
        rows.forEach(row -> { people.add(row.getEmployeeId()); if (row.getDecidedBy() != null) people.add(row.getDecidedBy()); });
        restored.values().forEach(row -> people.add(row.getActorEmployeeId()));
        var names = employees.names(people);
        LocalDate today = today();
        return rows.stream().map(row -> {
            var taken = allocated.getOrDefault(row.getId(), List.of());
            boolean approvedOnce = !taken.isEmpty();
            var status = row.getStatus();
            RequestDecision decision = null;
            RequestCancellation cancellation = null;
            if (row.getDecidedBy() != null && (status == LeaveRequest.Status.APPROVED || status == LeaveRequest.Status.REJECTED || approvedOnce))
                decision = new RequestDecision(actor(row.getDecidedBy(), names), row.getDecidedAt());
            if (status == LeaveRequest.Status.CANCELLED) {
                var restore = restored.get(row.getId());
                cancellation = approvedOnce && restore != null
                        ? new RequestCancellation("승인 취소", actor(restore.getActorEmployeeId(), names), restore.getCreatedAt())
                        : new RequestCancellation("신청 취소", actor(row.getDecidedBy(), names), row.getDecidedAt());
            }
            boolean decider = readers.manages(state, row.getBranchId()) && row.getEmployeeId() != state.employeeId();
            boolean requested = status == LeaveRequest.Status.REQUESTED;
            return new LeaveRequestView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), status.label,
                    actor(row.getEmployeeId(), names), Long.toString(row.getBranchId()), row.getStartsOn(), row.getEndsOn(),
                    row.getRequestedDays(), row.getReason(), decision, cancellation,
                    taken.stream().map(allocation -> new LeaveAllocationView(Long.toString(allocation.getLeaveGrantId()),
                            allocation.getAllocatedDays(), allocation.getRestoredAt() != null)).toList(),
                    row.getCreatedAt(), requested && decider, requested && decider, requested && row.getEmployeeId() == state.employeeId(),
                    status == LeaveRequest.Status.APPROVED && decider && today.isBefore(row.getStartsOn()));
        }).toList();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private <T> Optional<T> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint, Class<T> type) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            keys.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), type));
    }

    private static BigDecimal total(List<LeaveGrant> rows) {
        return rows.stream().map(LeaveGrant::getRemainingDays).reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
    }

    private LocalDate today() { return ShiftInputs.day(clock.instant()); }

    private static int year(String value) {
        try {
            if (!value.matches("[0-9]{4}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation("grantYear", "연도를 입력해 주세요.");
        }
    }

    private static String reload(long id) { return "/api/leave-requests/" + id; }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), Objects.requireNonNullElse(names.get(id), ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
