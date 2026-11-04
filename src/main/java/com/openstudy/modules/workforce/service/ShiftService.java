package com.openstudy.modules.workforce.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
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
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workforce.dto.response.ActorSummary;
import com.openstudy.modules.workforce.dto.response.ShiftCancellation;
import com.openstudy.modules.workforce.dto.response.ShiftView;
import com.openstudy.modules.workforce.dto.response.WeeklyShiftSchedule;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.Shift;
import com.openstudy.modules.workforce.entity.ShiftEvent;
import com.openstudy.modules.workforce.entity.ShiftStatus;
import com.openstudy.modules.workforce.repository.LeaveRequestRepository;
import com.openstudy.modules.workforce.repository.ShiftEventRepository;
import com.openstudy.modules.workforce.repository.ShiftRepository;
import com.openstudy.modules.workforce.repository.ShiftSearchRepository;

/**
 * Planned shifts (FR-SHIFT-01~04, ADR-M8-001). Only the OWNER or the shift branch's MANAGER registers, changes and cancels
 * them (M8-D01); everyone else reads by the recorded branch: a managed branch shows everyone, any other branch only the
 * reader's own shifts (M8-D02). One employee's shifts never overlap at any branch, nor an approved leave day (M8-D05·D06).
 * <p>No constraint refuses an overlap, so every write takes the employee's row FOR UPDATE before it reads the
 * authorization state under lock, and decides from locking reads after it (workforce-api.md "처리 순서"). Today the
 * foundation guard of {@link AuthorizedCommands} already runs all commands one at a time; the employee row is what keeps
 * one employee's writes in order if that guard is ever split. Each change writes one shift_events row with it.</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftService {
    private static final String CREATE = "shifts:create";
    private static final String ENTITY = "shifts";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "employeeId", "status");
    private final ShiftRepository shifts;
    private final ShiftEventRepository history;
    private final ShiftSearchRepository search;
    private final LeaveRequestRepository leaves;
    private final AttendanceViews attendance;
    private final RecordScope readers;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<ShiftView> list(long actor, String scope, ListQuery query, String employeeId, String mine, String status,
            String from, String to) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        ShiftStatus filterStatus = status == null ? ShiftStatus.SCHEDULED : ShiftInputs.status(status);
        LocalDate first = from == null ? null : ShiftInputs.date(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.date(to, "to");
        if (first != null && last != null && last.isBefore(first)) throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        var state = policy.current(actor);
        var filter = readers.filter(state, scope, employee, own, filterStatus, first == null ? null : ShiftInputs.startOf(first),
                last == null ? null : ShiftInputs.startOf(last.plusDays(1)));
        var page = search.find(filter, query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /**
     * Monday 00:00 to the next Monday 00:00 in Seoul. A shift belongs to the day and week it starts in, a clock-in to the
     * day it happened, so a shift across midnight on Sunday appears in one week only. Cancelled shifts are left out.
     */
    @Transactional(readOnly = true)
    public WeeklyShiftSchedule weekly(long actor, String scope, String weekOf, String employeeId, String mine) {
        Instant now = clock.instant();
        LocalDate day = weekOf == null ? ShiftInputs.day(now) : ShiftInputs.date(weekOf, "weekOf");
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var state = policy.current(actor);
        var filter = readers.filter(state, scope, employee, own, ShiftStatus.SCHEDULED, ShiftInputs.startOf(monday), ShiftInputs.startOf(monday.plusDays(7)));
        return new WeeklyShiftSchedule(monday, monday.plusDays(6), ShiftInputs.SEOUL.getId(), views(search.all(filter), state),
                attendance.records(search.attendance(filter)), now);
    }

    /** Also a cancelled shift. 404 hides a shift the reader may not see, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public ShiftView get(long actor, long id) {
        var state = policy.current(actor);
        var visible = readers.readable(state);
        var shift = shifts.findById(id).filter(found -> sees(state, visible, found)).orElseThrow(ShiftService::notFound);
        return views(List.of(shift), state).getFirst();
    }

    /**
     * The checks run in the contract's order: 403, malformed input, the time rules, a stopped branch, the employee, then
     * what already holds the time. A past shift may be registered, to record it afterwards.
     */
    public ShiftView create(long actor, String scope, String key, Map<String, Object> body) {
        var input = new StrictInput(body);
        long branch = input.id("branchId");
        // Without a lock first, so a request that may not register here never waits for, or locks, an employee row.
        var reader = policy.current(actor);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(reader, scope, BranchAction.READ);
        if (!policy.permits(reader, branch, BranchAction.MANAGE_SHIFTS)) throw AuthorizationService.forbidden();
        input.only("branchId", "employeeId", "startsAt", "endsAt", "plannedBreakMinutes");
        long employee = input.id("employeeId");
        Instant start = ShiftInputs.instant(body.get("startsAt"), "startsAt");
        Instant end = ShiftInputs.instant(body.get("endsAt"), "endsAt");
        int breakMinutes = input.has("plannedBreakMinutes") ? ShiftInputs.breakMinutes(body) : 0;
        ShiftInputs.validKey(key);
        ShiftInputs.requireValid(start, end, breakMinutes);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(Arrays.asList(branch, employee, start.toString(), end.toString(), breakMinutes)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        // MANAGE_SHIFTS also passes at a stopped branch, so that refusal comes as 409 BRANCH_NOT_ACTIVE at its place.
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_SHIFTS, () -> {
            prior.set(requests.lock(actor, CREATE, scopeKey, key));
            shifts.lockEmployee(employee);
        }, state -> {
            // Authorization has just been re-evaluated under lock; only now may a stored result be replayed.
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            requireOperating(state, branch);
            requireMember(employee, branch);
            requireFree(state, employee, start, end, null);
            var shift = shifts.saveAndFlush(new Shift(branch, employee, start, end, breakMinutes, actor));
            record(shift, actor, ShiftEvent.Type.CREATED, null, null);
            outbox.invalidate(branch, ENTITY, shift.getId());
            var result = views(List.of(shift), state).getFirst();
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Start, end and planned break only. The rules apply to the final values, with the fields not sent taken as they are. */
    public ShiftView update(long actor, long id, Map<String, Object> body) {
        long employee = managed(actor, id);
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·직원·상태는 바꿀 수 없습니다. 다른 직원이나 지점으로 옮기려면 취소한 뒤 새로 등록해 주세요.");
        var input = new StrictInput(body).only("version", "startsAt", "endsAt", "plannedBreakMinutes", "reason");
        long version = input.version("version");
        if (body.keySet().stream().noneMatch(field -> !"version".equals(field))) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        Instant start = input.has("startsAt") ? ShiftInputs.instant(body.get("startsAt"), "startsAt") : null;
        Instant end = input.has("endsAt") ? ShiftInputs.instant(body.get("endsAt"), "endsAt") : null;
        Integer breakMinutes = input.has("plannedBreakMinutes") ? Integer.valueOf(ShiftInputs.breakMinutes(body)) : null;
        String reason = input.has("reason") ? input.text("reason", 500) : null;
        return change(actor, id, employee, version, (state, shift) -> {
            if (shift.cancelled()) throw ApiException.conflict("INVALID_STATE", "취소된 근무는 수정할 수 없습니다.");
            requireOperating(state, shift.getBranchId());
            requireMember(shift.getEmployeeId(), shift.getBranchId());
            Instant newStart = start == null ? shift.getStartsAt() : start;
            Instant newEnd = end == null ? shift.getEndsAt() : end;
            int newBreak = breakMinutes == null ? shift.getPlannedBreakMinutes() : breakMinutes;
            ShiftInputs.requireValid(newStart, newEnd, newBreak);
            requireFree(state, shift.getEmployeeId(), newStart, newEnd, shift.getId());
            // An identical request is not a change: no version bump, history row or event.
            if (newStart.equals(shift.getStartsAt()) && newEnd.equals(shift.getEndsAt()) && newBreak == shift.getPlannedBreakMinutes())
                return views(List.of(shift), state).getFirst();
            var before = values(shift);
            shift.reschedule(newStart, newEnd, newBreak);
            return saved(shift, actor, ShiftEvent.Type.CHANGED, before, reason, state);
        });
    }

    /**
     * Also at a stopped branch and after the employee's membership ended: cancelling only tidies up (M8-D04). The row, its
     * history and any attendance linked to it stay; the time is free again at once.
     */
    public ShiftView cancel(long actor, long id, Map<String, Object> body) {
        long employee = managed(actor, id);
        var input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.has("reason") ? input.text("reason", 500) : null;
        return change(actor, id, employee, version, (state, shift) -> {
            if (shift.cancelled()) throw ApiException.conflict("INVALID_STATE", "이미 취소된 근무입니다.");
            var before = values(shift);
            shift.cancel();
            return saved(shift, actor, ShiftEvent.Type.CANCELLED, before, reason, state);
        });
    }

    /**
     * Whether a workspace stream may hear of this shift: the OWNER, the MANAGER of its branch or its own employee, the
     * readers of M8-D02. A branch event alone would reach every member of the branch (workforce-api.md "workspace 이벤트").
     */
    @Transactional(readOnly = true)
    public boolean announcedTo(AuthorizationState state, long branch, long shiftId) {
        if (readers.manages(state, branch)) return true;
        return shifts.employeeOf(shiftId).filter(employee -> employee == state.employeeId()).isPresent();
    }

    /** Before any lock: 404 for a shift the requester cannot see, then 403 unless they manage its branch. Returns its employee. */
    private long managed(long actor, long id) {
        var state = policy.current(actor);
        var visible = policy.scope(state, "all", BranchAction.READ);
        var shift = shifts.findById(id).filter(found -> sees(state, visible, found)).orElseThrow(ShiftService::notFound);
        if (!policy.permits(state, shift.getBranchId(), BranchAction.MANAGE_SHIFTS)) throw AuthorizationService.forbidden();
        return shift.getEmployeeId();
    }

    /**
     * Shared frame of a change and a cancellation: the employee row, then the shift row, then the same 404 and 403 again
     * on the state read under lock, then the version. Employee before shift is the order of a registration too.
     */
    private ShiftView change(long actor, long id, long employee, long version, BiFunction<AuthorizationState, Shift, ShiftView> command) {
        var locked = new AtomicReference<Optional<Shift>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            shifts.lockEmployee(employee);
            locked.set(shifts.locked(id));
        }, state -> {
            var visible = policy.scope(state, "all", BranchAction.READ);
            var shift = locked.get().filter(found -> sees(state, visible, found)).orElseThrow(ShiftService::notFound);
            if (!policy.permits(state, shift.getBranchId(), BranchAction.MANAGE_SHIFTS)) throw AuthorizationService.forbidden();
            ApiVersion.requireCurrent(version, shift.getVersion(), "/api/shifts/" + id);
            return command.apply(state, shift);
        });
    }

    private ShiftView saved(Shift shift, long actor, ShiftEvent.Type type, Map<String, Object> before, String reason, AuthorizationState state) {
        var result = shifts.saveAndFlush(shift);
        record(result, actor, type, before, reason);
        outbox.invalidate(result.getBranchId(), ENTITY, result.getId());
        return views(List.of(result), state).getFirst();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<ShiftView> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), ShiftView.class));
    }

    /** From the branch status read under lock, separately from the role check, so a stopped branch is 409, not 403. */
    private static void requireOperating(AuthorizationState state, long branch) {
        if (!"ACTIVE".equals(state.branches().get(branch)))
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 근무를 등록하거나 수정할 수 없습니다.");
    }

    /** An OWNER needs a membership row like anyone else: the global role manages shifts but is not one (fk_shifts_1). */
    private void requireMember(long employee, long branch) {
        if (shifts.activeMember(employee, branch).isEmpty())
            throw ApiException.unprocessable("INVALID_SHIFT_EMPLOYEE", "근무 지점의 활성 소속 직원만 근무를 받을 수 있습니다.");
    }

    /**
     * Every scheduled shift of the employee at every branch and every approved leave day, whether or not the requester may
     * see them (M8-D05·D06); a change leaves itself out. Locking reads, after the employee row lock.
     */
    private void requireFree(AuthorizationState state, long employee, Instant start, Instant end, Long self) {
        var taken = shifts.overlapping(employee, ShiftStatus.SCHEDULED, start, end).stream()
                .filter(shift -> !shift.getId().equals(self)).toList();
        var away = leaves.touching(employee, LeaveRequest.Status.APPROVED, ShiftInputs.day(start), ShiftInputs.day(end.minusNanos(1)));
        if (taken.isEmpty() && away.isEmpty()) return;
        var visible = policy.scope(state, "all", BranchAction.READ);
        var conflicts = ShiftConflicts.of(start, end, taken, shift -> sees(state, visible, shift), away);
        if (!conflicts.isEmpty()) throw new ShiftConflictException(conflicts);
    }

    /** M8-D02 for one shift: its branch is readable and either managed by the reader or the shift is the reader's own. */
    private boolean sees(AuthorizationState state, Set<Long> readable, Shift shift) {
        return readers.sees(state, readable, shift.getBranchId(), shift.getEmployeeId());
    }

    private void record(Shift shift, long actor, ShiftEvent.Type type, Map<String, Object> before, String reason) {
        history.save(new ShiftEvent(shift.getId(), actor, type, before == null ? null : json.writeValueAsString(before),
                json.writeValueAsString(values(shift)), reason));
    }

    /** The shift values the contract keeps in shift_events; version is the API one, as the screen showed it. */
    private static Map<String, Object> values(Shift shift) {
        var values = new LinkedHashMap<String, Object>();
        values.put("branchId", Long.toString(shift.getBranchId()));
        values.put("employeeId", Long.toString(shift.getEmployeeId()));
        values.put("startsAt", shift.getStartsAt().toString());
        values.put("endsAt", shift.getEndsAt().toString());
        values.put("plannedBreakMinutes", shift.getPlannedBreakMinutes());
        values.put("status", shift.getStatus().name());
        values.put("version", ApiVersion.fromDatabase(shift.getVersion()));
        return values;
    }

    /** The one mapping behind every shift response, so the list, the week and the detail cannot disagree. */
    private List<ShiftView> views(List<Shift> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(Shift::getId).toList();
        Map<Long, ShiftEvent> cancelled = new HashMap<>();
        history.findByShiftIdInAndEventTypeOrderByIdAsc(ids, ShiftEvent.Type.CANCELLED).forEach(event -> cancelled.put(event.getShiftId(), event));
        var attended = attendance.linkedTo(ids);
        var people = new LinkedHashSet<Long>();
        rows.forEach(row -> { people.add(row.getEmployeeId()); people.add(row.getCreatedBy()); });
        cancelled.values().forEach(event -> people.add(event.getActorEmployeeId()));
        var names = employees.names(people);
        return rows.stream().map(row -> {
            var cancel = cancelled.get(row.getId());
            long minutes = Duration.between(row.getStartsAt(), row.getEndsAt()).toMinutes();
            return new ShiftView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), row.getStatus().label(),
                    Long.toString(row.getBranchId()), actor(row.getEmployeeId(), names), row.getStartsAt(), row.getEndsAt(),
                    ShiftInputs.day(row.getStartsAt()), row.getPlannedBreakMinutes(), minutes - row.getPlannedBreakMinutes(),
                    row.cancelled() && cancel != null ? new ShiftCancellation(cancel.getCreatedAt(), actor(cancel.getActorEmployeeId(), names), cancel.getReason()) : null,
                    attended.getOrDefault(row.getId(), List.of()), actor(row.getCreatedBy(), names), row.getCreatedAt(), row.getUpdatedAt(),
                    !row.cancelled() && policy.permits(state, row.getBranchId(), BranchAction.MANAGE_SHIFTS));
        }).toList();
    }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), Objects.requireNonNullElse(names.get(id), ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
