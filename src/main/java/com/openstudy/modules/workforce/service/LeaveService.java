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
import com.openstudy.modules.workforce.entity.Shift;
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
    private static final List<LeaveRequest.Status> OPEN = List.of(
            LeaveRequest.Status.REQUESTED,
            LeaveRequest.Status.APPROVED
    );
    private static final String ALL_BRANCHES_SCOPE = "all";
    private static final String ACTIVE = "ACTIVE";
    private static final String ALREADY_DECIDED = "이미 처리된 휴가 신청입니다.";
    private static final String APPROVAL_CANCELLATION_LABEL = "승인 취소";
    private static final String REQUEST_CANCELLATION_LABEL = "신청 취소";
    private static final String GRANT_LOCATION = "/api/leave-grants/";
    private static final String REQUEST_LOCATION = "/api/leave-requests/";
    private static final String UNKNOWN_NAME = "";
    private static final String YEAR_PATTERN = "[0-9]{4}";
    private static final long MIN_GRANT_YEAR = 2000;
    private static final long MAX_GRANT_YEAR = 2100;
    private static final int REASON_MAX_LENGTH = 500;
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final BigDecimal ZERO_DAYS = BigDecimal.ZERO.setScale(1);

    private final LeaveGrantRepository leaveGrantRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveAllocationRepository leaveAllocationRepository;
    private final LeaveBalanceEventRepository leaveBalanceEventRepository;
    private final LeaveSearchRepository leaveSearchRepository;
    private final ShiftRepository shiftRepository;
    private final RecordScope recordScope;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private interface Decision {

        void apply(AuthorizationState state, LeaveRequest request);
    }

    // ---- grants ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<LeaveGrantView> listGrants(
            long actor,
            String scope,
            ListQuery query,
            String employeeId,
            String mine,
            String grantYear,
            String includeExpired
    ) {
        Long employee = employeeId == null ? null : ShiftInputs.parseId(employeeId, "employeeId");
        boolean own = ShiftInputs.parseFlag(mine, "mine");
        Integer year = grantYear == null ? null : parseYear(grantYear);
        boolean expired = ShiftInputs.parseFlag(includeExpired, "includeExpired");
        AuthorizationState state = authorizationService.current(actor);
        Optional<LeaveSearchRepository.Filter> filter = createFilter(state, scope, employee, own);

        if (filter.isEmpty()) {
            return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
        }

        LeaveSearchRepository.Page<LeaveGrant> page = leaveSearchRepository.findGrants(
                filter.get(),
                year,
                expired ? null : findToday(),
                query.offset(),
                query.pageSize()
        );

        return new PageResponse<>(
                toGrantViews(page.rows(), state),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    @Transactional(readOnly = true)
    public LeaveGrantDetailView getGrant(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        LeaveGrant grant = leaveGrantRepository.findById(id)
                .filter(found -> canSee(state, found))
                .orElseThrow(LeaveService::createNotFoundError);

        return toDetail(grant, state);
    }

    /** 403, malformed input, the grant rules, a stopped branch, then the employee (workforce-requests-api.md "부여"). */
    public LeaveGrantView createGrant(long actor, String key, Map<String, Object> body) {
        StrictInput input = new StrictInput(body);
        long branch = input.id("originBranchId");
        AuthorizationState reader = authorizationService.current(actor);

        if (!recordScope.canManage(reader, branch)) {
            throw AuthorizationService.forbidden();
        }

        input.only("employeeId", "originBranchId", "grantYear", "validFrom", "validThrough", "grantedDays", "reason");
        long employee = input.id("employeeId");
        long year = LeaveInputs.parseInteger(body, "grantYear");
        LocalDate from = LeaveInputs.parseDate(body, "validFrom");
        LocalDate through = LeaveInputs.parseDate(body, "validThrough");
        BigDecimal days = LeaveInputs.parseDays(body, "grantedDays");
        String reason = input.text("reason", REASON_MAX_LENGTH);
        ShiftInputs.validateKey(key);

        if (year < MIN_GRANT_YEAR || year > MAX_GRANT_YEAR) {
            throw LeaveInputs.createGrantError("grantYear", "부여 연도는 2000~2100 사이여야 합니다.");
        }

        if (through.isBefore(from)) {
            throw LeaveInputs.createGrantError("validThrough", "적용 종료일은 시작일보다 앞설 수 없습니다.");
        }

        boolean invalidDays = !LeaveInputs.isHalfDays(days)
                || days.compareTo(LeaveInputs.HALF) < 0
                || days.compareTo(LeaveInputs.MAX_DAYS) > 0;

        if (invalidDays) {
            throw LeaveInputs.createGrantError("grantedDays", "부여 일수는 0.5일 단위로 0.5~366일이어야 합니다.");
        }

        BigDecimal stored = LeaveInputs.toStored(days);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintValues = Arrays.asList(
                employee,
                branch,
                year,
                from.toString(),
                through.toString(),
                stored.toPlainString(),
                reason
        );
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(fingerprintValues));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_SHIFTS,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CREATE_GRANT, scopeKey, key));
                    shiftRepository.lockEmployee(employee);
                },
                state -> {
                    Optional<LeaveGrantView> replayed = findReplay(prior.get(), fingerprint, LeaveGrantView.class);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    requireOperating(state, branch, "운영 중지된 지점에서는 휴가를 부여할 수 없습니다.");

                    if (shiftRepository.findActiveMember(employee, branch).isEmpty()) {
                        throw ApiException.unprocessable(
                                "INVALID_LEAVE_EMPLOYEE",
                                "원부여 지점의 활성 소속 직원에게만 휴가를 부여할 수 있습니다."
                        );
                    }

                    LeaveGrant grant = leaveGrantRepository.saveAndFlush(
                            new LeaveGrant(employee, branch, (int) year, from, through, stored, reason, actor)
                    );
                    leaveBalanceEventRepository.saveAndFlush(new LeaveBalanceEvent(
                            grant.getId(),
                            null,
                            LeaveBalanceEvent.Type.GRANT,
                            stored,
                            reason,
                            actor,
                            "grant:" + grant.getId()
                    ));
                    LeaveGrantView result = toGrantViews(List.of(grant), state).getFirst();
                    idempotencyStore.complete(
                            actor,
                            CREATE_GRANT,
                            scopeKey,
                            key,
                            fingerprint,
                            CREATED_STATUS,
                            objectMapper.writeValueAsString(result),
                            clock.instant().plus(IDEMPOTENCY_TTL)
                    );

                    return result;
                }
        );
    }

    /** remaining_days only, with an ADJUST row; also at a stopped branch, since it corrects a record. */
    public LeaveGrantDetailView adjustGrant(long actor, long id, Map<String, Object> body) {
        AuthorizationState reader = authorizationService.current(actor);
        LeaveGrant found = leaveGrantRepository.findById(id)
                .filter(grant -> canSee(reader, grant))
                .orElseThrow(LeaveService::createNotFoundError);

        if (!recordScope.canManage(reader, found.getOriginBranchId())) {
            throw AuthorizationService.forbidden();
        }

        StrictInput input = new StrictInput(body).only("version", "deltaDays", "reason");
        long version = input.version("version");
        BigDecimal delta = LeaveInputs.parseDays(body, "deltaDays");
        String reason = input.text("reason", REASON_MAX_LENGTH);
        boolean invalidDelta = delta.signum() == 0
                || !LeaveInputs.isHalfDays(delta)
                || delta.abs().compareTo(LeaveInputs.MAX_DAYS) > 0;

        if (invalidDelta) {
            throw LeaveInputs.createGrantError("deltaDays", "보정 일수는 0이 아닌 0.5일 단위로 -366~366일이어야 합니다.");
        }

        BigDecimal stored = LeaveInputs.toStored(delta);
        AtomicReference<Optional<LeaveGrant>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    shiftRepository.lockEmployee(found.getEmployeeId());
                    locked.set(leaveGrantRepository.findLocked(id));
                },
                state -> {
                    LeaveGrant grant = locked.get()
                            .filter(row -> canSee(state, row))
                            .orElseThrow(LeaveService::createNotFoundError);

                    if (!recordScope.canManage(state, grant.getOriginBranchId())) {
                        throw AuthorizationService.forbidden();
                    }

                    ApiVersion.requireCurrent(version, grant.getVersion(), GRANT_LOCATION + id);

                    if (grant.getRemainingDays().add(stored).signum() < 0) {
                        throw WorkforceRequestRefusal.balance(stored.negate(), grant.getRemainingDays());
                    }

                    grant.move(stored);
                    LeaveGrant saved = leaveGrantRepository.saveAndFlush(grant);
                    leaveBalanceEventRepository.saveAndFlush(new LeaveBalanceEvent(
                            id,
                            null,
                            LeaveBalanceEvent.Type.ADJUST,
                            stored,
                            reason,
                            actor,
                            "adjust:" + id + ":" + saved.getVersion()
                    ));

                    return toDetail(saved, state);
                }
        );
    }

    /** The requester's own balance, whatever branch granted it (M12-D02). */
    @Transactional(readOnly = true)
    public LeaveBalanceView myBalance(long actor) {
        authorizationService.current(actor);

        return findBalance(actor);
    }

    /** OWNER, or the MANAGER of a branch where the employee is an active member; otherwise 404, as if there were none. */
    @Transactional(readOnly = true)
    public LeaveBalanceView employeeBalance(long actor, long employee) {
        AuthorizationState state = authorizationService.current(actor);
        boolean allowed = state.owner()
                ? !employeeAccountService.names(Set.of(employee)).isEmpty()
                : leaveGrantRepository.findActiveBranches(employee)
                        .stream()
                        .anyMatch(branch -> recordScope.canManage(state, branch.longValue()));

        if (!allowed) {
            throw createNotFoundError();
        }

        return findBalance(employee);
    }

    // ---- requests -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestView> listRequests(
            long actor,
            String scope,
            ListQuery query,
            String employeeId,
            String mine,
            String status,
            String from,
            String to
    ) {
        Long employee = employeeId == null ? null : ShiftInputs.parseId(employeeId, "employeeId");
        boolean own = ShiftInputs.parseFlag(mine, "mine");
        LeaveRequest.Status filterStatus = status == null ? null : LeaveInputs.parseStatus(status);
        LocalDate first = from == null ? null : ShiftInputs.parseDate(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.parseDate(to, "to");

        if (first != null && last != null && last.isBefore(first)) {
            throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        }

        AuthorizationState state = authorizationService.current(actor);
        Optional<LeaveSearchRepository.Filter> filter = createFilter(state, scope, employee, own);

        if (filter.isEmpty()) {
            return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
        }

        LeaveSearchRepository.Page<LeaveRequest> page = leaveSearchRepository.findRequests(
                filter.get(),
                filterStatus,
                first,
                last,
                query.offset(),
                query.pageSize()
        );

        return new PageResponse<>(
                toRequestViews(page.rows(), state),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    @Transactional(readOnly = true)
    public LeaveRequestView getRequest(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);

        return toRequestViews(List.of(findVisibleRequest(state, id)), state).getFirst();
    }

    /**
     * Malformed input, a branch that is not the requester's active one, the period rules, a stopped branch, an overlap,
     * then the balance. The balance check only informs: the days are taken when it is approved (M12-D06).
     */
    public LeaveRequestView createRequest(long actor, String key, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("branchId", "startsOn", "endsOn", "requestedDays", "reason");
        long branch = input.id("branchId");
        LocalDate startsOn = LeaveInputs.parseDate(body, "startsOn");
        LocalDate endsOn = LeaveInputs.parseDate(body, "endsOn");
        BigDecimal days = LeaveInputs.parseDays(body, "requestedDays");
        String reason = input.text("reason", REASON_MAX_LENGTH);

        if (!isMember(authorizationService.current(actor), branch)) {
            throw AuthorizationService.forbidden();
        }

        LeaveInputs.requirePeriod(startsOn, endsOn, days);
        ShiftInputs.validateKey(key);
        BigDecimal stored = LeaveInputs.toStored(days);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintValues = Arrays.asList(
                branch,
                startsOn.toString(),
                endsOn.toString(),
                stored.toPlainString(),
                reason
        );
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(fingerprintValues));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CREATE_REQUEST, scopeKey, key));
                    shiftRepository.lockEmployee(actor);
                },
                state -> {
                    Optional<LeaveRequestView> replayed = findReplay(prior.get(), fingerprint, LeaveRequestView.class);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    if (!isMember(state, branch)) {
                        throw AuthorizationService.forbidden();
                    }

                    requireOperating(state, branch, "운영 중지된 지점으로는 휴가를 신청할 수 없습니다.");
                    List<LeaveRequest> overlapping = leaveRequestRepository.findOverlapping(
                            actor,
                            OPEN,
                            startsOn,
                            endsOn,
                            0
                    );

                    if (!overlapping.isEmpty()) {
                        List<String> overlappingIds = overlapping.stream()
                                .map(row -> row.getId().toString())
                                .toList();

                        throw WorkforceRequestRefusal.overlap(overlappingIds);
                    }

                    BigDecimal available = sumRemaining(leaveGrantRepository.findAvailable(actor, startsOn, endsOn));

                    if (available.compareTo(stored) < 0) {
                        throw WorkforceRequestRefusal.balance(stored, available);
                    }

                    LeaveRequest request = leaveRequestRepository.saveAndFlush(
                            new LeaveRequest(actor, branch, startsOn, endsOn, stored, reason)
                    );
                    LeaveRequestView result = toRequestViews(List.of(request), state).getFirst();
                    idempotencyStore.complete(
                            actor,
                            CREATE_REQUEST,
                            scopeKey,
                            key,
                            fingerprint,
                            CREATED_STATUS,
                            objectMapper.writeValueAsString(result),
                            clock.instant().plus(IDEMPOTENCY_TTL)
                    );

                    return result;
                }
        );
    }

    /**
     * Another approved leave, a scheduled shift in the period (M12-D03), then the balance; the days come from the
     * grants that cover the period, earliest expiry first (M12-D06), each with an allocation and a USE row (M12-D07).
     */
    public LeaveRequestView approve(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = findDecidable(actor, id);

        LeaveRequest.Status from = LeaveRequest.Status.REQUESTED;

        return runDecision(actor, id, employee, version, from, ALREADY_DECIDED, (state, request) -> {
            List<LeaveRequest> other = leaveRequestRepository.findOverlapping(
                    employee,
                    List.of(LeaveRequest.Status.APPROVED),
                    request.getStartsOn(),
                    request.getEndsOn(),
                    id
            );

            if (!other.isEmpty()) {
                List<String> visibleIds = other.stream()
                        .filter(row -> canSee(state, row))
                        .map(row -> row.getId().toString())
                        .toList();

                throw WorkforceRequestRefusal.overlap(visibleIds);
            }

            Instant start = ShiftInputs.toStartOf(request.getStartsOn());
            Instant end = ShiftInputs.toStartOf(request.getEndsOn().plusDays(1));
            List<Shift> taken = shiftRepository.findOverlapping(employee, ShiftStatus.SCHEDULED, start, end);

            if (!taken.isEmpty()) {
                Set<Long> readable = recordScope.findReadable(state);

                throw WorkforceRequestRefusal.shifts(ShiftConflicts.toConflicts(
                        start,
                        end,
                        taken,
                        shift -> recordScope.canSee(state, readable, shift.getBranchId(), shift.getEmployeeId()),
                        List.of()
                ));
            }

            List<LeaveGrant> usable = leaveGrantRepository.findUsable(
                    employee,
                    request.getStartsOn(),
                    request.getEndsOn()
            );
            BigDecimal available = sumRemaining(usable);

            if (available.compareTo(request.getRequestedDays()) < 0) {
                throw WorkforceRequestRefusal.balance(request.getRequestedDays(), available);
            }

            BigDecimal left = request.getRequestedDays();

            for (LeaveGrant grant : usable) {
                if (left.signum() == 0) {
                    break;
                }

                BigDecimal take = grant.getRemainingDays().min(left);
                grant.move(take.negate());
                leaveGrantRepository.save(grant);
                leaveAllocationRepository.save(new LeaveAllocation(id, grant.getId(), take));
                LeaveBalanceEvent event = new LeaveBalanceEvent(
                        grant.getId(),
                        (Long) id,
                        LeaveBalanceEvent.Type.USE,
                        take.negate(),
                        null,
                        actor,
                        "leave:" + id + ":use:" + grant.getId()
                );
                recordBalanceEvent(event, request);
                left = left.subtract(take);
            }

            request.approve(actor, clock.instant());
        });
    }

    /** The balance does not change. */
    public LeaveRequestView reject(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = findDecidable(actor, id);

        return runDecision(
                actor,
                id,
                employee,
                version,
                LeaveRequest.Status.REQUESTED,
                ALREADY_DECIDED,
                (state, request) -> request.reject(actor, clock.instant())
        );
    }

    /** The requester withdraws a request that nobody has decided; also after their membership ended (tidying up). */
    public LeaveRequestView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        AuthorizationState reader = authorizationService.current(actor);
        LeaveRequest found = findVisibleRequest(reader, id);

        if (found.getEmployeeId() != actor) {
            throw AuthorizationService.forbidden();
        }

        AtomicReference<Optional<LeaveRequest>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    shiftRepository.lockEmployee(actor);
                    locked.set(leaveRequestRepository.findLocked(id));
                },
                state -> {
                    LeaveRequest request = locked.get()
                            .filter(row -> canSee(state, row))
                            .orElseThrow(LeaveService::createNotFoundError);

                    if (request.getEmployeeId() != actor) {
                        throw AuthorizationService.forbidden();
                    }

                    requireStatus(request, LeaveRequest.Status.REQUESTED, "승인 전 신청만 취소할 수 있습니다.");
                    ApiVersion.requireCurrent(version, request.getVersion(), toReloadUrl(id));
                    request.withdraw(actor, clock.instant());
                    LeaveRequest saved = leaveRequestRepository.saveAndFlush(request);

                    return toRequestViews(List.of(saved), state).getFirst();
                }
        );
    }

    /**
     * Before the first day only (2026-11-09 user decision). Each allocation goes back to its own grant, expired or not,
     * with a RESTORE row; the approver stays in decided_by.
     */
    public LeaveRequestView cancelApproval(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        long employee = findDecidable(actor, id);
        String wrongState = "승인된 휴가만 승인 취소할 수 있습니다.";

        return runDecision(actor, id, employee, version, LeaveRequest.Status.APPROVED, wrongState, (state, request) -> {
            if (!findToday().isBefore(request.getStartsOn())) {
                throw ApiException.conflict("LEAVE_ALREADY_STARTED", "시작일이 지난 휴가는 승인을 취소할 수 없습니다.");
            }

            Instant now = clock.instant();
            List<LeaveAllocation> taken = leaveAllocationRepository.findLockedFor(id)
                    .stream()
                    .filter(allocation -> allocation.getRestoredAt() == null)
                    .toList();
            List<Long> grantIds = taken.stream()
                    .map(LeaveAllocation::getLeaveGrantId)
                    .toList();
            Map<Long, LeaveGrant> locked = leaveGrantRepository.findAllLocked(grantIds)
                    .stream()
                    .collect(Collectors.toMap(LeaveGrant::getId, grant -> grant));

            for (LeaveAllocation allocation : taken) {
                LeaveGrant grant = locked.get(allocation.getLeaveGrantId());
                grant.move(allocation.getAllocatedDays());
                leaveGrantRepository.save(grant);
                allocation.restore(now);
                leaveAllocationRepository.save(allocation);
                LeaveBalanceEvent event = new LeaveBalanceEvent(
                        grant.getId(),
                        (Long) id,
                        LeaveBalanceEvent.Type.RESTORE,
                        allocation.getAllocatedDays(),
                        null,
                        actor,
                        "leave:" + id + ":restore:" + grant.getId()
                );
                recordBalanceEvent(event, request);
            }

            request.cancelApproval();
        });
    }

    /** Before any lock: 404 for a request the reader cannot see, 403 unless they manage its branch, 403 for their own. */
    private long findDecidable(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        LeaveRequest request = findVisibleRequest(state, id);
        requireDecider(state, request);

        return request.getEmployeeId();
    }

    /**
     * Shared frame of approve, reject and cancel-approval: the guard, the requester's row, the request row, the same 404
     * and 403s on the state read under lock, then the status before the version (a repeated command is told what
     * happened), then the command.
     */
    private LeaveRequestView runDecision(
            long actor,
            long id,
            long employee,
            long version,
            LeaveRequest.Status from,
            String wrongState,
            Decision decision
    ) {
        AtomicReference<Optional<LeaveRequest>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    shiftRepository.lockEmployee(employee);
                    locked.set(leaveRequestRepository.findLocked(id));
                },
                state -> {
                    LeaveRequest request = locked.get()
                            .filter(row -> canSee(state, row))
                            .orElseThrow(LeaveService::createNotFoundError);
                    requireDecider(state, request);
                    requireStatus(request, from, wrongState);
                    ApiVersion.requireCurrent(version, request.getVersion(), toReloadUrl(id));
                    decision.apply(state, request);
                    LeaveRequest saved = leaveRequestRepository.saveAndFlush(request);

                    return toRequestViews(List.of(saved), state).getFirst();
                }
        );
    }

    private void requireDecider(AuthorizationState state, LeaveRequest request) {
        if (!recordScope.canManage(state, request.getBranchId())) {
            throw AuthorizationService.forbidden();
        }

        if (request.getEmployeeId() == state.employeeId()) {
            throw WorkforceRequestRefusal.selfDecision();
        }
    }

    private static void requireStatus(LeaveRequest request, LeaveRequest.Status status, String message) {
        if (request.getStatus() != status) {
            throw WorkforceRequestRefusal.invalidState(
                    message,
                    ApiVersion.fromDatabase(request.getVersion()),
                    toReloadUrl(request.getId())
            );
        }
    }

    /** The unique event_key is the last guard against a second use or restore of one request (M12-D07). */
    private void recordBalanceEvent(LeaveBalanceEvent event, LeaveRequest request) {
        try {
            leaveBalanceEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException exception) {
            throw WorkforceRequestRefusal.invalidState(
                    "이미 반영된 휴가입니다.",
                    ApiVersion.fromDatabase(request.getVersion()),
                    toReloadUrl(request.getId())
            );
        }
    }

    // ---- reading --------------------------------------------------------------------------------------------

    /**
     * The reader condition of the lists: the managed branches of the scope, and the reader's own rows, at every branch for
     * scope=all or at the scope's branch. mine and an employee filter only narrow it; naming another employee with mine
     * finds nothing.
     */
    private Optional<LeaveSearchRepository.Filter> createFilter(
            AuthorizationState state,
            String scope,
            Long employee,
            boolean own
    ) {
        Set<Long> readable = authorizationService.scope(state, scope, BranchAction.READ);
        Set<Long> managed = readable.stream()
                .filter(branch -> recordScope.canManage(state, branch))
                .collect(Collectors.toSet());
        Set<Long> ownBranches = scope == null || ALL_BRANCHES_SCOPE.equals(scope) ? null : readable;
        Long person = employee;

        if (own) {
            if (employee != null && employee != state.employeeId()) {
                return Optional.empty();
            }

            person = state.employeeId();
            managed = Set.of();
        }

        return Optional.of(new LeaveSearchRepository.Filter(managed, state.employeeId(), ownBranches, person));
    }

    private LeaveRequest findVisibleRequest(AuthorizationState state, long id) {
        return leaveRequestRepository.findById(id)
                .filter(request -> canSee(state, request))
                .orElseThrow(LeaveService::createNotFoundError);
    }

    /** OWNER, the MANAGER of the deciding branch, or the employee: leave is per employee, so whatever their membership now. */
    private boolean canSee(AuthorizationState state, LeaveRequest request) {
        return request.getEmployeeId() == state.employeeId() || recordScope.canManage(state, request.getBranchId());
    }

    private boolean canSee(AuthorizationState state, LeaveGrant grant) {
        return grant.getEmployeeId() == state.employeeId() || recordScope.canManage(state, grant.getOriginBranchId());
    }

    /** An active membership row at the branch; an OWNER without one cannot request leave. */
    private static boolean isMember(AuthorizationState state, long branch) {
        AuthorizationState.Membership membership = state.memberships().get(branch);

        return ACTIVE.equals(state.status()) && membership != null && ACTIVE.equals(membership.status());
    }

    private static void requireOperating(AuthorizationState state, long branch, String message) {
        if (!ACTIVE.equals(state.branches().get(branch))) {
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", message);
        }
    }

    private LeaveBalanceView findBalance(long employee) {
        List<LeaveGrant> rows = leaveGrantRepository.findByEmployeeIdOrderByValidThroughAscIdAsc(employee);
        LocalDate today = findToday();
        Map<Long, String> names = employeeAccountService.names(Set.of(employee));
        BigDecimal total = rows.stream()
                .filter(grant -> !grant.getValidThrough().isBefore(today))
                .map(LeaveGrant::getRemainingDays)
                .reduce(ZERO_DAYS, BigDecimal::add);
        List<LeaveBalanceGrantView> grants = rows.stream()
                .map(grant -> new LeaveBalanceGrantView(
                        grant.getId().toString(),
                        Long.toString(grant.getOriginBranchId()),
                        grant.getGrantYear(),
                        grant.getValidFrom(),
                        grant.getValidThrough(),
                        grant.getGrantedDays(),
                        grant.getRemainingDays(),
                        grant.getValidThrough().isBefore(today)
                ))
                .toList();

        return new LeaveBalanceView(toActor(employee, names), clock.instant(), total, grants);
    }

    private List<LeaveGrantView> toGrantViews(List<LeaveGrant> rows, AuthorizationState state) {
        Set<Long> people = new LinkedHashSet<>();
        rows.forEach(grant -> {
            people.add(grant.getEmployeeId());
            people.add(grant.getGrantedBy());
        });
        Map<Long, String> names = employeeAccountService.names(people);
        LocalDate today = findToday();

        return rows.stream()
                .map(grant -> new LeaveGrantView(
                        grant.getId().toString(),
                        ApiVersion.fromDatabase(grant.getVersion()),
                        toActor(grant.getEmployeeId(), names),
                        Long.toString(grant.getOriginBranchId()),
                        grant.getGrantYear(),
                        grant.getValidFrom(),
                        grant.getValidThrough(),
                        grant.getGrantedDays(),
                        grant.getRemainingDays(),
                        grant.getValidThrough().isBefore(today),
                        grant.getReason(),
                        toActor(grant.getGrantedBy(), names),
                        grant.getCreatedAt(),
                        recordScope.canManage(state, grant.getOriginBranchId())
                ))
                .toList();
    }

    private LeaveGrantDetailView toDetail(LeaveGrant grant, AuthorizationState state) {
        LeaveGrantView view = toGrantViews(List.of(grant), state).getFirst();
        List<LeaveBalanceEvent> events = leaveBalanceEventRepository.findByLeaveGrantIdOrderByIdAsc(grant.getId());
        List<Long> actorIds = events.stream()
                .map(LeaveBalanceEvent::getActorEmployeeId)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);
        List<LeaveBalanceEventView> eventViews = events.stream()
                .map(event -> new LeaveBalanceEventView(
                        event.getEventType().label,
                        event.getDeltaDays(),
                        event.getLeaveRequestId() == null ? null : event.getLeaveRequestId().toString(),
                        event.getReason(),
                        toActor(event.getActorEmployeeId(), names),
                        event.getCreatedAt()
                ))
                .toList();

        return new LeaveGrantDetailView(
                view.id(),
                view.version(),
                view.employee(),
                view.originBranchId(),
                view.grantYear(),
                view.validFrom(),
                view.validThrough(),
                view.grantedDays(),
                view.remainingDays(),
                view.expired(),
                view.reason(),
                view.grantedBy(),
                view.createdAt(),
                view.canAdjust(),
                eventViews
        );
    }

    /**
     * The one mapping behind every request response. A cancelled request with allocations was approved first: its
     * decision is the approval and its cancellation comes from the RESTORE rows; without allocations it was withdrawn
     * before any decision, by the requester recorded in decided_*.
     */
    private List<LeaveRequestView> toRequestViews(List<LeaveRequest> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> ids = rows.stream()
                .map(LeaveRequest::getId)
                .toList();
        Map<Long, List<LeaveAllocation>> allocated = new HashMap<>();
        leaveAllocationRepository.findByLeaveRequestIdInOrderByIdAsc(ids)
                .forEach(row -> allocated.computeIfAbsent(row.getLeaveRequestId(), key -> new ArrayList<>()).add(row));
        Map<Long, LeaveBalanceEvent> restored = new HashMap<>();
        leaveBalanceEventRepository.findByLeaveRequestIdInAndEventTypeOrderByIdAsc(ids, LeaveBalanceEvent.Type.RESTORE)
                .forEach(row -> restored.putIfAbsent(row.getLeaveRequestId(), row));
        Set<Long> people = new LinkedHashSet<>();
        rows.forEach(row -> {
            people.add(row.getEmployeeId());

            if (row.getDecidedBy() != null) {
                people.add(row.getDecidedBy());
            }
        });
        restored.values().forEach(row -> people.add(row.getActorEmployeeId()));
        Map<Long, String> names = employeeAccountService.names(people);
        LocalDate today = findToday();

        return rows.stream()
                .map(row -> toRequestView(
                        row,
                        allocated.getOrDefault(row.getId(), List.of()),
                        restored.get(row.getId()),
                        names,
                        state,
                        today
                ))
                .toList();
    }

    private LeaveRequestView toRequestView(
            LeaveRequest row,
            List<LeaveAllocation> taken,
            LeaveBalanceEvent restore,
            Map<Long, String> names,
            AuthorizationState state,
            LocalDate today
    ) {
        boolean approvedOnce = !taken.isEmpty();
        LeaveRequest.Status status = row.getStatus();
        RequestDecision decision = null;
        RequestCancellation cancellation = null;
        boolean decided = status == LeaveRequest.Status.APPROVED
                || status == LeaveRequest.Status.REJECTED
                || approvedOnce;

        if (row.getDecidedBy() != null && decided) {
            decision = new RequestDecision(toActor(row.getDecidedBy(), names), row.getDecidedAt());
        }

        if (status == LeaveRequest.Status.CANCELLED) {
            cancellation = approvedOnce && restore != null
                    ? new RequestCancellation(
                            APPROVAL_CANCELLATION_LABEL,
                            toActor(restore.getActorEmployeeId(), names),
                            restore.getCreatedAt()
                    )
                    : new RequestCancellation(
                            REQUEST_CANCELLATION_LABEL,
                            toActor(row.getDecidedBy(), names),
                            row.getDecidedAt()
                    );
        }

        boolean decider = recordScope.canManage(state, row.getBranchId())
                && row.getEmployeeId() != state.employeeId();
        boolean requested = status == LeaveRequest.Status.REQUESTED;
        boolean ownRequest = row.getEmployeeId() == state.employeeId();
        boolean approvalCancellable = status == LeaveRequest.Status.APPROVED
                && decider
                && today.isBefore(row.getStartsOn());
        List<LeaveAllocationView> allocations = taken.stream()
                .map(allocation -> new LeaveAllocationView(
                        Long.toString(allocation.getLeaveGrantId()),
                        allocation.getAllocatedDays(),
                        allocation.getRestoredAt() != null
                ))
                .toList();

        return new LeaveRequestView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                status.label,
                toActor(row.getEmployeeId(), names),
                Long.toString(row.getBranchId()),
                row.getStartsOn(),
                row.getEndsOn(),
                row.getRequestedDays(),
                row.getReason(),
                decision,
                cancellation,
                allocations,
                row.getCreatedAt(),
                requested && decider,
                requested && decider,
                requested && ownRequest,
                approvalCancellable
        );
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private <T> Optional<T> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint, Class<T> type) {
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), type));
    }

    private static BigDecimal sumRemaining(List<LeaveGrant> rows) {
        return rows.stream()
                .map(LeaveGrant::getRemainingDays)
                .reduce(ZERO_DAYS, BigDecimal::add);
    }

    private LocalDate findToday() {
        return ShiftInputs.toDay(clock.instant());
    }

    private static int parseYear(String value) {
        try {
            if (!value.matches(YEAR_PATTERN)) {
                throw new NumberFormatException();
            }

            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation("grantYear", "연도를 입력해 주세요.");
        }
    }

    private static String toReloadUrl(long id) {
        return REQUEST_LOCATION + id;
    }

    private static ActorSummary toActor(Long id, Map<Long, String> names) {
        if (id == null) {
            return null;
        }

        return new ActorSummary(id.toString(), Objects.requireNonNullElse(names.get(id), UNKNOWN_NAME));
    }

    private static ResponseStatusException createNotFoundError() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
