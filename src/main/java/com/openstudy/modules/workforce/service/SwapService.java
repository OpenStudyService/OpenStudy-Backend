package com.openstudy.modules.workforce.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
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
import com.openstudy.modules.workforce.dto.response.RequestCancellation;
import com.openstudy.modules.workforce.dto.response.RequestDecision;
import com.openstudy.modules.workforce.dto.response.ShiftConflict;
import com.openstudy.modules.workforce.dto.response.ShiftSwapView;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.Shift;
import com.openstudy.modules.workforce.entity.ShiftEvent;
import com.openstudy.modules.workforce.entity.ShiftStatus;
import com.openstudy.modules.workforce.entity.ShiftSwap;
import com.openstudy.modules.workforce.repository.LeaveRequestRepository;
import com.openstudy.modules.workforce.repository.LeaveSearchRepository;
import com.openstudy.modules.workforce.repository.ShiftEventRepository;
import com.openstudy.modules.workforce.repository.ShiftRepository;
import com.openstudy.modules.workforce.repository.ShiftSwapRepository;

/**
 * Shift swaps (#109, FR-SWAP-01~04, ADR-M12-001 M12-D04·D05·D09, workforce-requests-api.md "대체근무"). A shift's own
 * employee asks to hand it to a colleague at the proposed time; the shift branch's MANAGER or the OWNER approves,
 * never the requester (2026-11-09 user decision). There is no consent step. An approval changes the shift's employee
 * and time together; its cancellation puts them back only while the shift is exactly as the approval left it.
 * <p>Both employees' rows are locked in id order after the foundation guard, then the swap row, then the shift row, so a
 * shift registration, a leave approval and a swap for the same people run one at a time (M8-D05, M12-D03). The
 * approval and its cancellation are shift_events SWAPPED rows whose after_json names the swap.</p>
 */
@Service
@RequiredArgsConstructor
public class SwapService {

    private static final String CREATE = "shift-swaps:create";
    private static final String APPROVED = "APPROVED";
    private static final String RESTORED = "RESTORED";
    private static final String ACTIVE = "ACTIVE";
    private static final String APPROVAL_CANCELLATION_LABEL = "승인 취소";
    private static final String REQUEST_CANCELLATION_LABEL = "신청 취소";
    private static final String ALREADY_DECIDED = "이미 처리된 대체근무 신청입니다.";
    private static final String SWAP_LOCATION = "/api/shift-swaps/";
    private static final String UNKNOWN_NAME = "";
    private static final int REASON_MAX_LENGTH = 500;
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final ShiftSwapRepository shiftSwapRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveSearchRepository leaveSearchRepository;
    private final RecordScope recordScope;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private record Context(AuthorizationState state, ShiftSwap swap, Shift shift) {
    }

    private interface Command {

        void apply(Context context);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShiftSwapView> list(
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
        Set<Long> readable = authorizationService.scope(state, scope, BranchAction.READ);
        Set<Long> managed = readable.stream()
                .filter(branch -> recordScope.canManage(state, branch))
                .collect(Collectors.toSet());
        Long person = employee;

        if (own) {
            if (employee != null && employee != actor) {
                return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
            }

            person = actor;
            managed = Set.of();
        }

        Instant start = first == null ? null : ShiftInputs.toStartOf(first);
        Instant end = last == null ? null : ShiftInputs.toStartOf(last.plusDays(1));
        LeaveSearchRepository.Page<ShiftSwap> page = leaveSearchRepository.findSwaps(
                readable,
                managed,
                actor,
                person,
                filterStatus,
                start,
                end,
                query.offset(),
                query.pageSize()
        );

        return new PageResponse<>(
                toViews(page.rows(), state),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    @Transactional(readOnly = true)
    public ShiftSwapView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);

        return toViews(List.of(findVisible(state, id)), state).getFirst();
    }

    /**
     * Only the shift's own employee, before it starts, for someone else who is an active member of the shift branch, with
     * no other undecided swap of the shift, and at a time the replacement has free (M8-D05·D06). Nothing changes on the
     * shift until a manager approves (M12-D04).
     */
    public ShiftSwapView create(long actor, long shiftId, String key, Map<String, Object> body) {
        AuthorizationState reader = authorizationService.current(actor);
        Set<Long> readable = recordScope.findReadable(reader);
        Shift found = shiftRepository.findById(shiftId)
                .filter(shift -> recordScope.canSee(reader, readable, shift.getBranchId(), shift.getEmployeeId()))
                .orElseThrow(SwapService::createNotFoundError);

        if (found.getEmployeeId() != actor) {
            throw AuthorizationService.forbidden();
        }

        StrictInput input = new StrictInput(body)
                .only("replacementEmployeeId", "proposedStartsAt", "proposedEndsAt", "reason");
        long replacement = input.id("replacementEmployeeId");
        Instant start = ShiftInputs.parseInstant(body.get("proposedStartsAt"), "proposedStartsAt");
        Instant end = ShiftInputs.parseInstant(body.get("proposedEndsAt"), "proposedEndsAt");
        String reason = input.text("reason", REASON_MAX_LENGTH);
        ShiftInputs.validateKey(key);
        ShiftInputs.requireValid(start, end, found.getPlannedBreakMinutes());
        String scopeKey = Long.toString(found.getBranchId());
        List<Object> fingerprintValues = Arrays.asList(shiftId, replacement, start.toString(), end.toString(), reason);
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(fingerprintValues));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<Shift>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CREATE, scopeKey, key));
                    lockEmployees(actor, replacement);
                    locked.set(shiftRepository.findLocked(shiftId));
                },
                state -> {
                    Optional<ShiftSwapView> replayed = findReplay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Set<Long> visibleNow = recordScope.findReadable(state);
                    Shift shift = locked.get()
                            .filter(row -> recordScope.canSee(
                                    state,
                                    visibleNow,
                                    row.getBranchId(),
                                    row.getEmployeeId()
                            ))
                            .orElseThrow(SwapService::createNotFoundError);

                    if (shift.getEmployeeId() != actor) {
                        throw AuthorizationService.forbidden();
                    }

                    ShiftInputs.requireValid(start, end, shift.getPlannedBreakMinutes());
                    requireReplacement(replacement, actor, shift.getBranchId());

                    if (shift.cancelled()) {
                        throw ApiException.conflict("INVALID_STATE", "취소된 근무는 대체근무를 신청할 수 없습니다.");
                    }

                    requireNotStarted(shift);
                    requireOperating(state, shift.getBranchId());
                    List<ShiftSwap> pending = shiftSwapRepository.findForShift(shiftId, LeaveRequest.Status.REQUESTED);

                    if (!pending.isEmpty()) {
                        throw WorkforceRequestRefusal.pendingSwap(pending.getFirst().getId());
                    }

                    requireFree(state, replacement, start, end, shiftId);
                    ShiftSwap swap = shiftSwapRepository.saveAndFlush(
                            new ShiftSwap(shiftId, actor, replacement, start, end, reason)
                    );
                    ShiftSwapView result = toViews(List.of(swap), state).getFirst();
                    idempotencyStore.complete(
                            actor,
                            CREATE,
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
     * The shift must still be exactly what the requester asked about and the manager saw: same employee, no change since
     * the request, and the version the manager sends. Then the replacement's membership and free time, and the shift's
     * employee and time change together with a SWAPPED row.
     */
    public ShiftSwapView approve(long actor, long id, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "shiftVersion");
        long version = input.version("version");
        long shiftVersion = input.version("shiftVersion");
        ShiftSwap swap = findDecidable(actor, id);

        return runCommand(actor, id, swap, version, LeaveRequest.Status.REQUESTED, ALREADY_DECIDED, true, context -> {
            ShiftSwap row = context.swap();
            Shift shift = context.shift();

            if (shift.cancelled()) {
                throw ApiException.conflict("INVALID_STATE", "취소된 근무는 대체할 수 없습니다.");
            }

            boolean shiftMoved = ApiVersion.fromDatabase(shift.getVersion()) != shiftVersion
                    || shift.getEmployeeId() != row.getRequestedBy()
                    || shiftEventRepository.existsByShiftIdAndCreatedAtAfter(shift.getId(), row.getCreatedAt());

            if (shiftMoved) {
                throw WorkforceRequestRefusal.shiftVersion(shift.getId(), ApiVersion.fromDatabase(shift.getVersion()));
            }

            requireNotStarted(shift);
            requireOperating(context.state(), shift.getBranchId());
            requireReplacement(row.getReplacementEmployeeId(), row.getRequestedBy(), shift.getBranchId());
            requireFree(
                    context.state(),
                    row.getReplacementEmployeeId(),
                    row.getProposedStartsAt(),
                    row.getProposedEndsAt(),
                    shift.getId()
            );
            Map<String, Object> before = ShiftService.toValues(shift);
            shift.reassign(row.getReplacementEmployeeId(), row.getProposedStartsAt(), row.getProposedEndsAt());
            recordSwapped(shiftRepository.saveAndFlush(shift), actor, before, row, APPROVED);
            row.approve(actor, clock.instant());
        });
    }

    /** The shift does not change. */
    public ShiftSwapView reject(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        ShiftSwap swap = findDecidable(actor, id);

        return runCommand(
                actor,
                id,
                swap,
                version,
                LeaveRequest.Status.REQUESTED,
                ALREADY_DECIDED,
                true,
                context -> context.swap().reject(actor, clock.instant())
        );
    }

    /** The requester withdraws an undecided request; also after the shift started or the membership ended (tidying up). */
    public ShiftSwapView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        ShiftSwap swap = shiftSwapRepository.findById(id).orElseThrow(SwapService::createNotFoundError);

        if (swap.getRequestedBy() != actor) {
            findVisible(authorizationService.current(actor), id);

            throw AuthorizationService.forbidden();
        }

        return runCommand(
                actor,
                id,
                swap,
                version,
                LeaveRequest.Status.REQUESTED,
                "승인 전 신청만 취소할 수 있습니다.",
                false,
                context -> context.swap().withdraw(actor, clock.instant())
        );
    }

    /**
     * M12-D09: only while the shift is exactly as the approval left it (its version then) and has not started, and only
     * if the original employee is still a member and free at the original time. The restore is a SWAPPED row too.
     */
    public ShiftSwapView cancelApproval(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        ShiftSwap swap = findDecidable(actor, id);
        String wrongState = "승인된 대체근무만 승인 취소할 수 있습니다.";

        return runCommand(actor, id, swap, version, LeaveRequest.Status.APPROVED, wrongState, true, context -> {
            ShiftSwap row = context.swap();
            Shift shift = context.shift();
            ShiftEvent approval = findMarker(shift.getId(), row.getId(), APPROVED)
                    .orElseThrow(() -> new IllegalStateException("Approved swap without its SWAPPED row"));
            Map<String, Object> after = readMap(approval.getAfterJson());
            Map<String, Object> before = readMap(approval.getBeforeJson());
            long approvedVersion = ((Number) after.get("version")).longValue();
            boolean changedSinceApproval = shift.cancelled()
                    || ApiVersion.fromDatabase(shift.getVersion()) != approvedVersion;

            if (changedSinceApproval) {
                throw WorkforceRequestRefusal.restore(
                        shift.getId(),
                        ApiVersion.fromDatabase(shift.getVersion()),
                        approvedVersion
                );
            }

            requireNotStarted(shift);
            long original = Long.parseLong((String) before.get("employeeId"));
            Instant start = Instant.parse((String) before.get("startsAt"));
            Instant end = Instant.parse((String) before.get("endsAt"));

            if (shiftRepository.findActiveMember(original, shift.getBranchId()).isEmpty()) {
                throw ApiException.unprocessable(
                        "INVALID_SHIFT_EMPLOYEE",
                        "원래 담당자가 더 이상 근무 지점의 활성 소속 직원이 아닙니다."
                );
            }

            requireFree(context.state(), original, start, end, shift.getId());
            Map<String, Object> previous = ShiftService.toValues(shift);
            shift.reassign(original, start, end);
            recordSwapped(shiftRepository.saveAndFlush(shift), actor, previous, row, RESTORED);
            row.cancelApproval();
        });
    }

    /** Before any lock: 404 for a swap the reader cannot see, 403 unless they manage the shift branch, 403 for their own. */
    private ShiftSwap findDecidable(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        ShiftSwap swap = findVisible(state, id);
        requireDecider(state, swap, findBranchOf(swap));

        return swap;
    }

    /**
     * Shared frame: the guard, both employees' rows in id order, the swap row, the shift row; the same 404 and 403s on the
     * state read under lock; then the status before the version, so a repeated command is told what happened.
     */
    private ShiftSwapView runCommand(
            long actor,
            long id,
            ShiftSwap known,
            long version,
            LeaveRequest.Status from,
            String wrongState,
            boolean decider,
            Command command
    ) {
        AtomicReference<Optional<ShiftSwap>> lockedSwap = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<Shift>> lockedShift = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    lockEmployees(known.getRequestedBy(), known.getReplacementEmployeeId());
                    lockedSwap.set(shiftSwapRepository.findLocked(id));
                    lockedShift.set(shiftRepository.findLocked(known.getOriginalShiftId()));
                },
                state -> {
                    ShiftSwap swap = lockedSwap.get().orElseThrow(SwapService::createNotFoundError);
                    Shift shift = lockedShift.get().orElseThrow(SwapService::createNotFoundError);

                    if (decider) {
                        if (!canSee(state, recordScope.findReadable(state), swap, shift)) {
                            throw createNotFoundError();
                        }

                        requireDecider(state, swap, shift.getBranchId());
                    } else if (swap.getRequestedBy() != actor) {
                        throw AuthorizationService.forbidden();
                    }

                    if (swap.getStatus() != from) {
                        throw WorkforceRequestRefusal.invalidState(
                                wrongState,
                                ApiVersion.fromDatabase(swap.getVersion()),
                                toReloadUrl(id)
                        );
                    }

                    ApiVersion.requireCurrent(version, swap.getVersion(), toReloadUrl(id));
                    command.apply(new Context(state, swap, shift));
                    ShiftSwap saved = shiftSwapRepository.saveAndFlush(swap);

                    return toViews(List.of(saved), state).getFirst();
                }
        );
    }

    private void requireDecider(AuthorizationState state, ShiftSwap swap, long branch) {
        if (!recordScope.canManage(state, branch)) {
            throw AuthorizationService.forbidden();
        }

        if (swap.getRequestedBy() == state.employeeId()) {
            throw WorkforceRequestRefusal.selfDecision();
        }
    }

    private void requireReplacement(long replacement, long requester, long branch) {
        boolean invalid = replacement == requester
                || shiftRepository.findActiveMember(replacement, branch).isEmpty();

        if (invalid) {
            throw ApiException.unprocessable(
                    "INVALID_SHIFT_EMPLOYEE",
                    "근무 지점의 활성 소속인 다른 직원만 대체할 수 있습니다."
            );
        }
    }

    private void requireNotStarted(Shift shift) {
        if (!shift.getStartsAt().isAfter(clock.instant())) {
            throw ApiException.conflict("SHIFT_ALREADY_STARTED", "시작한 근무는 대체근무로 바꾸거나 되돌릴 수 없습니다.");
        }
    }

    private static void requireOperating(AuthorizationState state, long branch) {
        if (!ACTIVE.equals(state.branches().get(branch))) {
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점의 근무는 대체할 수 없습니다.");
        }
    }

    /**
     * The same check as a shift registration (M8-D05·D06): every scheduled shift of the employee at every branch and every
     * approved leave day, this shift left out; a shift the requester cannot see is only an overlap.
     */
    private void requireFree(AuthorizationState state, long employee, Instant start, Instant end, long self) {
        List<Shift> taken = shiftRepository.findOverlapping(employee, ShiftStatus.SCHEDULED, start, end)
                .stream()
                .filter(shift -> shift.getId() != self)
                .toList();
        List<LeaveRequest> away = leaveRequestRepository.findTouching(
                employee,
                LeaveRequest.Status.APPROVED,
                ShiftInputs.toDay(start),
                ShiftInputs.toDay(end.minusNanos(1))
        );

        if (taken.isEmpty() && away.isEmpty()) {
            return;
        }

        Set<Long> readable = recordScope.findReadable(state);
        List<ShiftConflict> conflicts = ShiftConflicts.toConflicts(
                start,
                end,
                taken,
                shift -> recordScope.canSee(state, readable, shift.getBranchId(), shift.getEmployeeId()),
                away
        );

        if (!conflicts.isEmpty()) {
            throw WorkforceRequestRefusal.shiftConflict(conflicts);
        }
    }

    private void lockEmployees(long one, long other) {
        shiftRepository.lockEmployee(Math.min(one, other));

        if (one != other) {
            shiftRepository.lockEmployee(Math.max(one, other));
        }
    }

    /** One SWAPPED row: the shift before and after, and in after_json which swap did it and whether it applied or restored it. */
    private void recordSwapped(Shift shift, long actor, Map<String, Object> before, ShiftSwap swap, String outcome) {
        Map<String, Object> after = new LinkedHashMap<>(ShiftService.toValues(shift));
        after.put("swapId", swap.getId().toString());
        after.put("swap", outcome);
        ShiftEvent event = new ShiftEvent(
                shift.getId(),
                actor,
                ShiftEvent.Type.SWAPPED,
                objectMapper.writeValueAsString(before),
                objectMapper.writeValueAsString(after),
                swap.getReason()
        );
        shiftEventRepository.save(event);
    }

    private Optional<ShiftEvent> findMarker(long shift, long swap, String outcome) {
        return findMarkers(List.of(shift))
                .getOrDefault(toMarkerKey(swap, outcome), List.of())
                .stream()
                .reduce((first, second) -> second);
    }

    /** SWAPPED rows of these shifts by "swapId:outcome". */
    private Map<String, List<ShiftEvent>> findMarkers(List<Long> shiftIds) {
        Map<String, List<ShiftEvent>> result = new HashMap<>();
        List<ShiftEvent> events = shiftEventRepository.findByShiftIdInAndEventTypeOrderByIdAsc(
                shiftIds,
                ShiftEvent.Type.SWAPPED
        );

        for (ShiftEvent event : events) {
            Map<String, Object> after = readMap(event.getAfterJson());

            if (after.get("swapId") instanceof String swap && after.get("swap") instanceof String outcome) {
                result.computeIfAbsent(swap + ":" + outcome, ignored -> new ArrayList<>()).add(event);
            }
        }

        return result;
    }

    private static String toMarkerKey(long swap, String outcome) {
        return swap + ":" + outcome;
    }

    private ShiftSwap findVisible(AuthorizationState state, long id) {
        ShiftSwap swap = shiftSwapRepository.findById(id).orElseThrow(SwapService::createNotFoundError);
        Shift shift = shiftRepository.findById(swap.getOriginalShiftId()).orElseThrow(SwapService::createNotFoundError);

        if (!canSee(state, recordScope.findReadable(state), swap, shift)) {
            throw createNotFoundError();
        }

        return swap;
    }

    /** M8-D02 by the shift branch: a managed branch shows every swap, any other readable branch those the reader is part of. */
    private boolean canSee(AuthorizationState state, Set<Long> readable, ShiftSwap swap, Shift shift) {
        if (!readable.contains(shift.getBranchId())) {
            return false;
        }

        return recordScope.canManage(state, shift.getBranchId())
                || swap.getRequestedBy() == state.employeeId()
                || swap.getReplacementEmployeeId() == state.employeeId();
    }

    private long findBranchOf(ShiftSwap swap) {
        return shiftRepository.findById(swap.getOriginalShiftId())
                .map(Shift::getBranchId)
                .orElseThrow(SwapService::createNotFoundError);
    }

    /** The one mapping behind every swap response. */
    private List<ShiftSwapView> toViews(List<ShiftSwap> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> shiftIds = rows.stream()
                .map(ShiftSwap::getOriginalShiftId)
                .distinct()
                .toList();
        Map<Long, Shift> byId = shiftRepository.findAllById(shiftIds)
                .stream()
                .collect(Collectors.toMap(Shift::getId, Function.identity()));
        Map<String, List<ShiftEvent>> marks = findMarkers(shiftIds);
        Set<Long> people = new LinkedHashSet<>();
        rows.forEach(row -> {
            people.add(row.getRequestedBy());
            people.add(row.getReplacementEmployeeId());

            if (row.getDecidedBy() != null) {
                people.add(row.getDecidedBy());
            }
        });
        marks.values().forEach(events -> events.forEach(event -> people.add(event.getActorEmployeeId())));
        Map<Long, String> names = employeeAccountService.names(people);
        Instant now = clock.instant();

        return rows.stream()
                .map(row -> toView(row, byId.get(row.getOriginalShiftId()), marks, names, state, now))
                .toList();
    }

    private ShiftSwapView toView(
            ShiftSwap row,
            Shift shift,
            Map<String, List<ShiftEvent>> marks,
            Map<Long, String> names,
            AuthorizationState state,
            Instant now
    ) {
        ShiftEvent approval = findLast(marks.get(toMarkerKey(row.getId(), APPROVED)));
        ShiftEvent restore = findLast(marks.get(toMarkerKey(row.getId(), RESTORED)));
        Instant originalStart = shift.getStartsAt();
        Instant originalEnd = shift.getEndsAt();

        if (approval != null) {
            Map<String, Object> before = readMap(approval.getBeforeJson());
            originalStart = Instant.parse((String) before.get("startsAt"));
            originalEnd = Instant.parse((String) before.get("endsAt"));
        }

        LeaveRequest.Status status = row.getStatus();
        boolean decided = status == LeaveRequest.Status.APPROVED
                || status == LeaveRequest.Status.REJECTED
                || approval != null;
        RequestDecision decision = row.getDecidedBy() != null && decided
                ? new RequestDecision(toActor(row.getDecidedBy(), names), row.getDecidedAt())
                : null;
        RequestCancellation cancellation = null;

        if (status == LeaveRequest.Status.CANCELLED) {
            cancellation = restore != null
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

        boolean decider = recordScope.canManage(state, shift.getBranchId())
                && row.getRequestedBy() != state.employeeId();
        boolean requested = status == LeaveRequest.Status.REQUESTED;
        boolean ownRequest = row.getRequestedBy() == state.employeeId();
        boolean approvalCancellable = status == LeaveRequest.Status.APPROVED
                && decider
                && shift.getStartsAt().isAfter(now);

        return new ShiftSwapView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                status.label,
                shift.getId().toString(),
                Long.toString(shift.getBranchId()),
                toActor(row.getRequestedBy(), names),
                toActor(row.getReplacementEmployeeId(), names),
                originalStart,
                originalEnd,
                row.getProposedStartsAt(),
                row.getProposedEndsAt(),
                ApiVersion.fromDatabase(shift.getVersion()),
                row.getReason(),
                decision,
                cancellation,
                row.getCreatedAt(),
                requested && decider,
                requested && decider,
                requested && ownRequest,
                approvalCancellable
        );
    }

    private static ShiftEvent findLast(List<ShiftEvent> events) {
        return events == null || events.isEmpty() ? null : events.getLast();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    private Optional<ShiftSwapView> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), ShiftSwapView.class));
    }

    private static String toReloadUrl(long id) {
        return SWAP_LOCATION + id;
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
