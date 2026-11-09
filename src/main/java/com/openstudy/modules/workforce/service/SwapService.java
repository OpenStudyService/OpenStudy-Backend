package com.openstudy.modules.workforce.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
    private final ShiftSwapRepository swaps;
    private final ShiftRepository shifts;
    private final ShiftEventRepository history;
    private final LeaveRequestRepository leaves;
    private final LeaveSearchRepository search;
    private final RecordScope readers;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore keys;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<ShiftSwapView> list(long actor, String scope, ListQuery query, String employeeId, String mine, String status,
            String from, String to) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        LeaveRequest.Status filterStatus = status == null ? null : LeaveInputs.status(status);
        LocalDate first = from == null ? null : ShiftInputs.date(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.date(to, "to");
        if (first != null && last != null && last.isBefore(first)) throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        var state = policy.current(actor);
        Set<Long> readable = policy.scope(state, scope, BranchAction.READ);
        Set<Long> managed = readable.stream().filter(branch -> readers.manages(state, branch)).collect(Collectors.toSet());
        Long person = employee;
        if (own) {
            if (employee != null && employee != actor) return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), 0));
            person = actor;
            managed = Set.of();
        }
        var page = search.swaps(readable, managed, actor, person, filterStatus, first == null ? null : ShiftInputs.startOf(first),
                last == null ? null : ShiftInputs.startOf(last.plusDays(1)), query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public ShiftSwapView get(long actor, long id) {
        var state = policy.current(actor);
        return views(List.of(visible(state, id)), state).getFirst();
    }

    /**
     * Only the shift's own employee, before it starts, for someone else who is an active member of the shift branch, with
     * no other undecided swap of the shift, and at a time the replacement has free (M8-D05·D06). Nothing changes on the
     * shift until a manager approves (M12-D04).
     */
    public ShiftSwapView create(long actor, long shiftId, String key, Map<String, Object> body) {
        var reader = policy.current(actor);
        var readable = readers.readable(reader);
        var found = shifts.findById(shiftId).filter(shift -> readers.sees(reader, readable, shift.getBranchId(), shift.getEmployeeId()))
                .orElseThrow(SwapService::notFound);
        if (found.getEmployeeId() != actor) throw AuthorizationService.forbidden();
        var input = new StrictInput(body).only("replacementEmployeeId", "proposedStartsAt", "proposedEndsAt", "reason");
        long replacement = input.id("replacementEmployeeId");
        Instant start = ShiftInputs.instant(body.get("proposedStartsAt"), "proposedStartsAt");
        Instant end = ShiftInputs.instant(body.get("proposedEndsAt"), "proposedEndsAt");
        String reason = input.text("reason", 500);
        ShiftInputs.validKey(key);
        ShiftInputs.requireValid(start, end, found.getPlannedBreakMinutes());
        String scopeKey = Long.toString(found.getBranchId());
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(Arrays.asList(shiftId, replacement, start.toString(), end.toString(), reason)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        var locked = new AtomicReference<Optional<Shift>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            prior.set(keys.lock(actor, CREATE, scopeKey, key));
            lockEmployees(actor, replacement);
            locked.set(shifts.locked(shiftId));
        }, state -> {
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var visibleNow = readers.readable(state);
            var shift = locked.get().filter(row -> readers.sees(state, visibleNow, row.getBranchId(), row.getEmployeeId()))
                    .orElseThrow(SwapService::notFound);
            if (shift.getEmployeeId() != actor) throw AuthorizationService.forbidden();
            ShiftInputs.requireValid(start, end, shift.getPlannedBreakMinutes());
            requireReplacement(replacement, actor, shift.getBranchId());
            if (shift.cancelled()) throw ApiException.conflict("INVALID_STATE", "취소된 근무는 대체근무를 신청할 수 없습니다.");
            requireNotStarted(shift);
            requireOperating(state, shift.getBranchId());
            var pending = swaps.forShift(shiftId, LeaveRequest.Status.REQUESTED);
            if (!pending.isEmpty()) throw WorkforceRequestRefusal.pendingSwap(pending.getFirst().getId());
            requireFree(state, replacement, start, end, shiftId);
            var swap = swaps.saveAndFlush(new ShiftSwap(shiftId, actor, replacement, start, end, reason));
            var result = views(List.of(swap), state).getFirst();
            keys.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /**
     * The shift must still be exactly what the requester asked about and the manager saw: same employee, no change since
     * the request, and the version the manager sends. Then the replacement's membership and free time, and the shift's
     * employee and time change together with a SWAPPED row.
     */
    public ShiftSwapView approve(long actor, long id, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "shiftVersion");
        long version = input.version("version");
        long shiftVersion = input.version("shiftVersion");
        var swap = decidable(actor, id);
        return frame(actor, id, swap, version, LeaveRequest.Status.REQUESTED, "이미 처리된 대체근무 신청입니다.", true, (context) -> {
            var row = context.swap();
            var shift = context.shift();
            if (shift.cancelled()) throw ApiException.conflict("INVALID_STATE", "취소된 근무는 대체할 수 없습니다.");
            if (ApiVersion.fromDatabase(shift.getVersion()) != shiftVersion || shift.getEmployeeId() != row.getRequestedBy()
                    || history.existsByShiftIdAndCreatedAtAfter(shift.getId(), row.getCreatedAt()))
                throw WorkforceRequestRefusal.shiftVersion(shift.getId(), ApiVersion.fromDatabase(shift.getVersion()));
            requireNotStarted(shift);
            requireOperating(context.state(), shift.getBranchId());
            requireReplacement(row.getReplacementEmployeeId(), row.getRequestedBy(), shift.getBranchId());
            requireFree(context.state(), row.getReplacementEmployeeId(), row.getProposedStartsAt(), row.getProposedEndsAt(), shift.getId());
            var before = ShiftService.values(shift);
            shift.reassign(row.getReplacementEmployeeId(), row.getProposedStartsAt(), row.getProposedEndsAt());
            swapped(shifts.saveAndFlush(shift), actor, before, row, APPROVED);
            row.approve(actor, clock.instant());
        });
    }

    /** The shift does not change. */
    public ShiftSwapView reject(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var swap = decidable(actor, id);
        return frame(actor, id, swap, version, LeaveRequest.Status.REQUESTED, "이미 처리된 대체근무 신청입니다.", true,
                context -> context.swap().reject(actor, clock.instant()));
    }

    /** The requester withdraws an undecided request; also after the shift started or the membership ended (tidying up). */
    public ShiftSwapView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var swap = swaps.findById(id).orElseThrow(SwapService::notFound);
        if (swap.getRequestedBy() != actor) {
            visible(policy.current(actor), id);
            throw AuthorizationService.forbidden();
        }
        return frame(actor, id, swap, version, LeaveRequest.Status.REQUESTED, "승인 전 신청만 취소할 수 있습니다.", false,
                context -> context.swap().withdraw(actor, clock.instant()));
    }

    /**
     * M12-D09: only while the shift is exactly as the approval left it (its version then) and has not started, and only
     * if the original employee is still a member and free at the original time. The restore is a SWAPPED row too.
     */
    public ShiftSwapView cancelApproval(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var swap = decidable(actor, id);
        return frame(actor, id, swap, version, LeaveRequest.Status.APPROVED, "승인된 대체근무만 승인 취소할 수 있습니다.", true, context -> {
            var row = context.swap();
            var shift = context.shift();
            var approval = marker(shift.getId(), row.getId(), APPROVED).orElseThrow(() -> new IllegalStateException("Approved swap without its SWAPPED row"));
            Map<String, Object> after = json.readValue(approval.getAfterJson(), Map.class);
            Map<String, Object> before = json.readValue(approval.getBeforeJson(), Map.class);
            long approvedVersion = ((Number) after.get("version")).longValue();
            if (shift.cancelled() || ApiVersion.fromDatabase(shift.getVersion()) != approvedVersion)
                throw WorkforceRequestRefusal.restore(shift.getId(), ApiVersion.fromDatabase(shift.getVersion()), approvedVersion);
            requireNotStarted(shift);
            long original = Long.parseLong((String) before.get("employeeId"));
            Instant start = Instant.parse((String) before.get("startsAt"));
            Instant end = Instant.parse((String) before.get("endsAt"));
            if (shifts.activeMember(original, shift.getBranchId()).isEmpty())
                throw ApiException.unprocessable("INVALID_SHIFT_EMPLOYEE", "원래 담당자가 더 이상 근무 지점의 활성 소속 직원이 아닙니다.");
            requireFree(context.state(), original, start, end, shift.getId());
            var previous = ShiftService.values(shift);
            shift.reassign(original, start, end);
            swapped(shifts.saveAndFlush(shift), actor, previous, row, RESTORED);
            row.cancelApproval();
        });
    }

    private record Context(AuthorizationState state, ShiftSwap swap, Shift shift) {}

    private interface Command { void apply(Context context); }

    /** Before any lock: 404 for a swap the reader cannot see, 403 unless they manage the shift branch, 403 for their own. */
    private ShiftSwap decidable(long actor, long id) {
        var state = policy.current(actor);
        var swap = visible(state, id);
        requireDecider(state, swap, branchOf(swap));
        return swap;
    }

    /**
     * Shared frame: the guard, both employees' rows in id order, the swap row, the shift row; the same 404 and 403s on the
     * state read under lock; then the status before the version, so a repeated command is told what happened.
     */
    private ShiftSwapView frame(long actor, long id, ShiftSwap known, long version, LeaveRequest.Status from, String wrongState,
            boolean decider, Command command) {
        var lockedSwap = new AtomicReference<Optional<ShiftSwap>>(Optional.empty());
        var lockedShift = new AtomicReference<Optional<Shift>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            lockEmployees(known.getRequestedBy(), known.getReplacementEmployeeId());
            lockedSwap.set(swaps.locked(id));
            lockedShift.set(shifts.locked(known.getOriginalShiftId()));
        }, state -> {
            var swap = lockedSwap.get().orElseThrow(SwapService::notFound);
            var shift = lockedShift.get().orElseThrow(SwapService::notFound);
            if (decider) {
                if (!sees(state, readers.readable(state), swap, shift)) throw notFound();
                requireDecider(state, swap, shift.getBranchId());
            } else if (swap.getRequestedBy() != actor) {
                throw AuthorizationService.forbidden();
            }
            if (swap.getStatus() != from)
                throw WorkforceRequestRefusal.invalidState(wrongState, ApiVersion.fromDatabase(swap.getVersion()), reload(id));
            ApiVersion.requireCurrent(version, swap.getVersion(), reload(id));
            command.apply(new Context(state, swap, shift));
            return views(List.of(swaps.saveAndFlush(swap)), state).getFirst();
        });
    }

    private void requireDecider(AuthorizationState state, ShiftSwap swap, long branch) {
        if (!readers.manages(state, branch)) throw AuthorizationService.forbidden();
        if (swap.getRequestedBy() == state.employeeId()) throw WorkforceRequestRefusal.selfDecision();
    }

    private void requireReplacement(long replacement, long requester, long branch) {
        if (replacement == requester || shifts.activeMember(replacement, branch).isEmpty())
            throw ApiException.unprocessable("INVALID_SHIFT_EMPLOYEE", "근무 지점의 활성 소속인 다른 직원만 대체할 수 있습니다.");
    }

    private void requireNotStarted(Shift shift) {
        if (!shift.getStartsAt().isAfter(clock.instant()))
            throw ApiException.conflict("SHIFT_ALREADY_STARTED", "시작한 근무는 대체근무로 바꾸거나 되돌릴 수 없습니다.");
    }

    private static void requireOperating(AuthorizationState state, long branch) {
        if (!"ACTIVE".equals(state.branches().get(branch)))
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점의 근무는 대체할 수 없습니다.");
    }

    /**
     * The same check as a shift registration (M8-D05·D06): every scheduled shift of the employee at every branch and every
     * approved leave day, this shift left out; a shift the requester cannot see is only an overlap.
     */
    private void requireFree(AuthorizationState state, long employee, Instant start, Instant end, long self) {
        var taken = shifts.overlapping(employee, ShiftStatus.SCHEDULED, start, end).stream().filter(shift -> shift.getId() != self).toList();
        var away = leaves.touching(employee, LeaveRequest.Status.APPROVED, ShiftInputs.day(start), ShiftInputs.day(end.minusNanos(1)));
        if (taken.isEmpty() && away.isEmpty()) return;
        var readable = readers.readable(state);
        var conflicts = ShiftConflicts.of(start, end, taken, shift -> readers.sees(state, readable, shift.getBranchId(), shift.getEmployeeId()), away);
        if (!conflicts.isEmpty()) throw WorkforceRequestRefusal.shiftConflict(conflicts);
    }

    private void lockEmployees(long one, long other) {
        shifts.lockEmployee(Math.min(one, other));
        if (one != other) shifts.lockEmployee(Math.max(one, other));
    }

    /** One SWAPPED row: the shift before and after, and in after_json which swap did it and whether it applied or restored it. */
    private void swapped(Shift shift, long actor, Map<String, Object> before, ShiftSwap swap, String outcome) {
        var after = new LinkedHashMap<>(ShiftService.values(shift));
        after.put("swapId", swap.getId().toString());
        after.put("swap", outcome);
        history.save(new ShiftEvent(shift.getId(), actor, ShiftEvent.Type.SWAPPED, json.writeValueAsString(before),
                json.writeValueAsString(after), swap.getReason()));
    }

    private Optional<ShiftEvent> marker(long shift, long swap, String outcome) {
        return markers(List.of(shift)).getOrDefault(key(swap, outcome), List.of()).stream().reduce((first, second) -> second);
    }

    /** SWAPPED rows of these shifts by "swapId:outcome". */
    private Map<String, List<ShiftEvent>> markers(List<Long> shiftIds) {
        var result = new HashMap<String, List<ShiftEvent>>();
        for (var event : history.findByShiftIdInAndEventTypeOrderByIdAsc(shiftIds, ShiftEvent.Type.SWAPPED)) {
            Map<String, Object> after = json.readValue(event.getAfterJson(), Map.class);
            if (after.get("swapId") instanceof String swap && after.get("swap") instanceof String outcome)
                result.computeIfAbsent(swap + ":" + outcome, k -> new java.util.ArrayList<>()).add(event);
        }
        return result;
    }

    private static String key(long swap, String outcome) { return swap + ":" + outcome; }

    private ShiftSwap visible(AuthorizationState state, long id) {
        var swap = swaps.findById(id).orElseThrow(SwapService::notFound);
        var shift = shifts.findById(swap.getOriginalShiftId()).orElseThrow(SwapService::notFound);
        if (!sees(state, readers.readable(state), swap, shift)) throw notFound();
        return swap;
    }

    /** M8-D02 by the shift branch: a managed branch shows every swap, any other readable branch those the reader is part of. */
    private boolean sees(AuthorizationState state, Set<Long> readable, ShiftSwap swap, Shift shift) {
        return readable.contains(shift.getBranchId()) && (readers.manages(state, shift.getBranchId())
                || swap.getRequestedBy() == state.employeeId() || swap.getReplacementEmployeeId() == state.employeeId());
    }

    private long branchOf(ShiftSwap swap) {
        return shifts.findById(swap.getOriginalShiftId()).map(Shift::getBranchId).orElseThrow(SwapService::notFound);
    }

    /** The one mapping behind every swap response. */
    private List<ShiftSwapView> views(List<ShiftSwap> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var shiftIds = rows.stream().map(ShiftSwap::getOriginalShiftId).distinct().toList();
        Map<Long, Shift> byId = shifts.findAllById(shiftIds).stream().collect(Collectors.toMap(Shift::getId, Function.identity()));
        var marks = markers(shiftIds);
        var people = new LinkedHashSet<Long>();
        rows.forEach(row -> { people.add(row.getRequestedBy()); people.add(row.getReplacementEmployeeId());
            if (row.getDecidedBy() != null) people.add(row.getDecidedBy()); });
        marks.values().forEach(events -> events.forEach(event -> people.add(event.getActorEmployeeId())));
        var names = employees.names(people);
        Instant now = clock.instant();
        return rows.stream().map(row -> {
            var shift = byId.get(row.getOriginalShiftId());
            var approval = last(marks.get(key(row.getId(), APPROVED)));
            var restore = last(marks.get(key(row.getId(), RESTORED)));
            Instant originalStart = shift.getStartsAt(), originalEnd = shift.getEndsAt();
            if (approval != null) {
                Map<String, Object> before = json.readValue(approval.getBeforeJson(), Map.class);
                originalStart = Instant.parse((String) before.get("startsAt"));
                originalEnd = Instant.parse((String) before.get("endsAt"));
            }
            var status = row.getStatus();
            RequestDecision decision = row.getDecidedBy() != null && (status == LeaveRequest.Status.APPROVED || status == LeaveRequest.Status.REJECTED
                    || approval != null) ? new RequestDecision(actor(row.getDecidedBy(), names), row.getDecidedAt()) : null;
            RequestCancellation cancellation = null;
            if (status == LeaveRequest.Status.CANCELLED)
                cancellation = restore != null ? new RequestCancellation("승인 취소", actor(restore.getActorEmployeeId(), names), restore.getCreatedAt())
                        : new RequestCancellation("신청 취소", actor(row.getDecidedBy(), names), row.getDecidedAt());
            boolean decider = readers.manages(state, shift.getBranchId()) && row.getRequestedBy() != state.employeeId();
            boolean requested = status == LeaveRequest.Status.REQUESTED;
            return new ShiftSwapView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), status.label,
                    shift.getId().toString(), Long.toString(shift.getBranchId()), actor(row.getRequestedBy(), names),
                    actor(row.getReplacementEmployeeId(), names), originalStart, originalEnd, row.getProposedStartsAt(), row.getProposedEndsAt(),
                    ApiVersion.fromDatabase(shift.getVersion()), row.getReason(), decision, cancellation, row.getCreatedAt(),
                    requested && decider, requested && decider, requested && row.getRequestedBy() == state.employeeId(),
                    status == LeaveRequest.Status.APPROVED && decider && shift.getStartsAt().isAfter(now));
        }).toList();
    }

    private static ShiftEvent last(List<ShiftEvent> events) { return events == null || events.isEmpty() ? null : events.getLast(); }

    private Optional<ShiftSwapView> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            keys.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), ShiftSwapView.class));
    }

    private static String reload(long id) { return "/api/shift-swaps/" + id; }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), Objects.requireNonNullElse(names.get(id), ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
