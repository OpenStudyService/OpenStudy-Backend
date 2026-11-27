package com.openstudy.modules.workforce.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
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
import com.openstudy.modules.workforce.dto.response.AttendanceSummary;
import com.openstudy.modules.workforce.dto.response.ShiftCancellation;
import com.openstudy.modules.workforce.dto.response.ShiftColleague;
import com.openstudy.modules.workforce.dto.response.ShiftConflict;
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
    private static final String ALL_BRANCHES_SCOPE = "all";
    private static final String ACTIVE = "ACTIVE";
    private static final String SHIFT_LOCATION = "/api/shifts/";
    private static final String UNKNOWN_NAME = "";
    private static final int REASON_MAX_LENGTH = 500;
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final int DAYS_PER_WEEK = 7;

    private final ShiftRepository shiftRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final ShiftSearchRepository shiftSearchRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceViews attendanceViews;
    private final RecordScope recordScope;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    /**
     * Same-branch colleagues' shifts that overlap [start, end), for the home card only (2026-11-05 user decision). The
     * branches are those where the viewer holds an active membership; GET /shifts keeps its M8-D02 scope. Sorted by start,
     * end, name, branch and then the internal shift ID, which is not returned.
     */
    @Transactional(readOnly = true)
    public List<ShiftColleague> colleagues(long viewer, Set<Long> branches, Instant start, Instant end) {
        if (branches.isEmpty()) {
            return List.of();
        }

        List<Long> shiftIds = shiftRepository.findColleagueShiftIds(branches, viewer, start, end)
                .stream()
                .map(Number::longValue)
                .toList();
        List<Shift> rows = shiftRepository.findAllById(shiftIds);
        List<Long> employeeIds = rows.stream()
                .map(Shift::getEmployeeId)
                .toList();
        Map<Long, String> names = employeeAccountService.names(employeeIds);
        Comparator<Shift> order = Comparator.comparing(Shift::getStartsAt)
                .thenComparing(Shift::getEndsAt)
                .thenComparing(shift -> names.getOrDefault(shift.getEmployeeId(), UNKNOWN_NAME))
                .thenComparingLong(Shift::getBranchId)
                .thenComparing(Shift::getId);

        return rows.stream()
                .sorted(order)
                .map(shift -> new ShiftColleague(
                        shift.getBranchId(),
                        names.getOrDefault(shift.getEmployeeId(), UNKNOWN_NAME),
                        shift.getStartsAt(),
                        shift.getEndsAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ShiftView> list(
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
        ShiftStatus filterStatus = status == null ? ShiftStatus.SCHEDULED : ShiftInputs.parseStatus(status);
        LocalDate first = from == null ? null : ShiftInputs.parseDate(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.parseDate(to, "to");

        if (first != null && last != null && last.isBefore(first)) {
            throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        }

        AuthorizationState state = authorizationService.current(actor);
        Instant start = first == null ? null : ShiftInputs.toStartOf(first);
        Instant end = last == null ? null : ShiftInputs.toStartOf(last.plusDays(1));
        ShiftSearchRepository.Filter filter = recordScope.createFilter(
                state,
                scope,
                employee,
                own,
                filterStatus,
                start,
                end
        );
        ShiftSearchRepository.Page page = shiftSearchRepository.find(filter, query.offset(), query.pageSize());

        return new PageResponse<>(
                toViews(page.rows(), state),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    /**
     * Monday 00:00 to the next Monday 00:00 in Seoul. A shift belongs to the day and week it starts in, a clock-in to the
     * day it happened, so a shift across midnight on Sunday appears in one week only. Cancelled shifts are left out.
     */
    @Transactional(readOnly = true)
    public WeeklyShiftSchedule weekly(long actor, String scope, String weekOf, String employeeId, String mine) {
        Instant now = clock.instant();
        LocalDate day = weekOf == null ? ShiftInputs.toDay(now) : ShiftInputs.parseDate(weekOf, "weekOf");
        Long employee = employeeId == null ? null : ShiftInputs.parseId(employeeId, "employeeId");
        boolean own = ShiftInputs.parseFlag(mine, "mine");
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        AuthorizationState state = authorizationService.current(actor);
        ShiftSearchRepository.Filter filter = recordScope.createFilter(
                state,
                scope,
                employee,
                own,
                ShiftStatus.SCHEDULED,
                ShiftInputs.toStartOf(monday),
                ShiftInputs.toStartOf(monday.plusDays(DAYS_PER_WEEK))
        );
        List<ShiftView> shifts = toViews(shiftSearchRepository.findAll(filter), state);

        return new WeeklyShiftSchedule(
                monday,
                monday.plusDays(DAYS_PER_WEEK - 1),
                ShiftInputs.SEOUL.getId(),
                shifts,
                attendanceViews.toViews(shiftSearchRepository.findAttendanceRecords(filter)),
                now
        );
    }

    /** Also a cancelled shift. 404 hides a shift the reader may not see, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public ShiftView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> visible = recordScope.findReadable(state);
        Shift shift = shiftRepository.findById(id)
                .filter(found -> canSee(state, visible, found))
                .orElseThrow(ShiftService::createNotFoundError);

        return toViews(List.of(shift), state).getFirst();
    }

    /**
     * The checks run in the contract's order: 403, malformed input, the time rules, a stopped branch, the employee, then
     * what already holds the time. A past shift may be registered, to record it afterwards.
     */
    public ShiftView create(long actor, String scope, String key, Map<String, Object> body) {
        StrictInput input = new StrictInput(body);
        long branch = input.id("branchId");
        // Without a lock first, so a request that may not register here never waits for, or locks, an employee row.
        AuthorizationState reader = authorizationService.current(actor);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(reader, scope, BranchAction.READ);

        if (!authorizationService.permits(reader, branch, BranchAction.MANAGE_SHIFTS)) {
            throw AuthorizationService.forbidden();
        }

        input.only("branchId", "employeeId", "startsAt", "endsAt", "plannedBreakMinutes");
        long employee = input.id("employeeId");
        Instant start = ShiftInputs.parseInstant(body.get("startsAt"), "startsAt");
        Instant end = ShiftInputs.parseInstant(body.get("endsAt"), "endsAt");
        int breakMinutes = input.has("plannedBreakMinutes") ? ShiftInputs.parseBreakMinutes(body) : 0;
        ShiftInputs.validateKey(key);
        ShiftInputs.requireValid(start, end, breakMinutes);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintValues = Arrays.asList(
                branch,
                employee,
                start.toString(),
                end.toString(),
                breakMinutes
        );
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(fingerprintValues));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        // MANAGE_SHIFTS also passes at a stopped branch, so that refusal comes as 409 BRANCH_NOT_ACTIVE at its place.
        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_SHIFTS,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CREATE, scopeKey, key));
                    shiftRepository.lockEmployee(employee);
                },
                state -> {
                    // Authorization has just been re-evaluated under lock; only now may a stored result be replayed.
                    Optional<ShiftView> replayed = findReplay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    requireOperating(state, branch);
                    requireMember(employee, branch);
                    requireFree(state, employee, start, end, null);
                    Shift shift = shiftRepository.saveAndFlush(
                            new Shift(branch, employee, start, end, breakMinutes, actor)
                    );
                    recordEvent(shift, actor, ShiftEvent.Type.CREATED, null, null);
                    ShiftView result = toViews(List.of(shift), state).getFirst();
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

    /** Start, end and planned break only. The rules apply to the final values, with the fields not sent taken as they are. */
    public ShiftView update(long actor, long id, Map<String, Object> body) {
        long employee = requireManaged(actor, id);
        boolean immutableSent = body != null && body.keySet()
                .stream()
                .anyMatch(IMMUTABLE::contains);

        if (immutableSent) {
            throw ApiException.unprocessable(
                    "IMMUTABLE_FIELD",
                    "지점·직원·상태는 바꿀 수 없습니다. 다른 직원이나 지점으로 옮기려면 취소한 뒤 새로 등록해 주세요."
            );
        }

        StrictInput input = new StrictInput(body).only(
                "version",
                "startsAt",
                "endsAt",
                "plannedBreakMinutes",
                "reason"
        );
        long version = input.version("version");
        boolean onlyVersion = body.keySet()
                .stream()
                .noneMatch(field -> !"version".equals(field));

        if (onlyVersion) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        Instant start = input.has("startsAt") ? ShiftInputs.parseInstant(body.get("startsAt"), "startsAt") : null;
        Instant end = input.has("endsAt") ? ShiftInputs.parseInstant(body.get("endsAt"), "endsAt") : null;
        Integer breakMinutes = input.has("plannedBreakMinutes")
                ? Integer.valueOf(ShiftInputs.parseBreakMinutes(body))
                : null;
        String reason = input.has("reason") ? input.text("reason", REASON_MAX_LENGTH) : null;

        return runChange(actor, id, employee, version, (state, shift) -> {
            if (shift.cancelled()) {
                throw ApiException.conflict("INVALID_STATE", "취소된 근무는 수정할 수 없습니다.");
            }

            requireOperating(state, shift.getBranchId());
            requireMember(shift.getEmployeeId(), shift.getBranchId());
            Instant newStart = start == null ? shift.getStartsAt() : start;
            Instant newEnd = end == null ? shift.getEndsAt() : end;
            int newBreak = breakMinutes == null ? shift.getPlannedBreakMinutes() : breakMinutes;
            ShiftInputs.requireValid(newStart, newEnd, newBreak);
            requireFree(state, shift.getEmployeeId(), newStart, newEnd, shift.getId());
            // An identical request is not a change: no version bump, history row or event.
            boolean unchanged = newStart.equals(shift.getStartsAt())
                    && newEnd.equals(shift.getEndsAt())
                    && newBreak == shift.getPlannedBreakMinutes();

            if (unchanged) {
                return toViews(List.of(shift), state).getFirst();
            }

            Map<String, Object> before = toValues(shift);
            shift.reschedule(newStart, newEnd, newBreak);

            return saveAndView(shift, actor, ShiftEvent.Type.CHANGED, before, reason, state);
        });
    }

    /**
     * Also at a stopped branch and after the employee's membership ended: cancelling only tidies up (M8-D04). The row, its
     * history and any attendance linked to it stay; the time is free again at once.
     */
    public ShiftView cancel(long actor, long id, Map<String, Object> body) {
        long employee = requireManaged(actor, id);
        StrictInput input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.has("reason") ? input.text("reason", REASON_MAX_LENGTH) : null;

        return runChange(actor, id, employee, version, (state, shift) -> {
            if (shift.cancelled()) {
                throw ApiException.conflict("INVALID_STATE", "이미 취소된 근무입니다.");
            }

            Map<String, Object> before = toValues(shift);
            shift.cancel();

            return saveAndView(shift, actor, ShiftEvent.Type.CANCELLED, before, reason, state);
        });
    }

    /** Before any lock: 404 for a shift the requester cannot see, then 403 unless they manage its branch. Returns its employee. */
    private long requireManaged(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> visible = authorizationService.scope(state, ALL_BRANCHES_SCOPE, BranchAction.READ);
        Shift shift = shiftRepository.findById(id)
                .filter(found -> canSee(state, visible, found))
                .orElseThrow(ShiftService::createNotFoundError);

        if (!authorizationService.permits(state, shift.getBranchId(), BranchAction.MANAGE_SHIFTS)) {
            throw AuthorizationService.forbidden();
        }

        return shift.getEmployeeId();
    }

    /**
     * Shared frame of a change and a cancellation: the employee row, then the shift row, then the same 404 and 403 again
     * on the state read under lock, then the version. Employee before shift is the order of a registration too.
     */
    private ShiftView runChange(
            long actor,
            long id,
            long employee,
            long version,
            BiFunction<AuthorizationState, Shift, ShiftView> command
    ) {
        AtomicReference<Optional<Shift>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    shiftRepository.lockEmployee(employee);
                    locked.set(shiftRepository.findLocked(id));
                },
                state -> {
                    Set<Long> visible = authorizationService.scope(state, ALL_BRANCHES_SCOPE, BranchAction.READ);
                    Shift shift = locked.get()
                            .filter(found -> canSee(state, visible, found))
                            .orElseThrow(ShiftService::createNotFoundError);

                    if (!authorizationService.permits(state, shift.getBranchId(), BranchAction.MANAGE_SHIFTS)) {
                        throw AuthorizationService.forbidden();
                    }

                    ApiVersion.requireCurrent(version, shift.getVersion(), SHIFT_LOCATION + id);

                    return command.apply(state, shift);
                }
        );
    }

    private ShiftView saveAndView(
            Shift shift,
            long actor,
            ShiftEvent.Type type,
            Map<String, Object> before,
            String reason,
            AuthorizationState state
    ) {
        Shift result = shiftRepository.saveAndFlush(shift);
        recordEvent(result, actor, type, before, reason);

        return toViews(List.of(result), state).getFirst();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<ShiftView> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), ShiftView.class));
    }

    /** From the branch status read under lock, separately from the role check, so a stopped branch is 409, not 403. */
    private static void requireOperating(AuthorizationState state, long branch) {
        if (!ACTIVE.equals(state.branches().get(branch))) {
            throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 근무를 등록하거나 수정할 수 없습니다.");
        }
    }

    /** An OWNER needs a membership row like anyone else: the global role manages shifts but is not one (fk_shifts_1). */
    private void requireMember(long employee, long branch) {
        if (shiftRepository.findActiveMember(employee, branch).isEmpty()) {
            throw ApiException.unprocessable(
                    "INVALID_SHIFT_EMPLOYEE",
                    "근무 지점의 활성 소속 직원만 근무를 받을 수 있습니다."
            );
        }
    }

    /**
     * Every scheduled shift of the employee at every branch and every approved leave day, whether or not the requester may
     * see them (M8-D05·D06); a change leaves itself out. Locking reads, after the employee row lock.
     */
    private void requireFree(AuthorizationState state, long employee, Instant start, Instant end, Long self) {
        List<Shift> taken = shiftRepository.findOverlapping(employee, ShiftStatus.SCHEDULED, start, end)
                .stream()
                .filter(shift -> !shift.getId().equals(self))
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

        Set<Long> visible = authorizationService.scope(state, ALL_BRANCHES_SCOPE, BranchAction.READ);
        List<ShiftConflict> conflicts = ShiftConflicts.toConflicts(
                start,
                end,
                taken,
                shift -> canSee(state, visible, shift),
                away
        );

        if (!conflicts.isEmpty()) {
            throw new ShiftConflictException(conflicts);
        }
    }

    /** M8-D02 for one shift: its branch is readable and either managed by the reader or the shift is the reader's own. */
    private boolean canSee(AuthorizationState state, Set<Long> readable, Shift shift) {
        return recordScope.canSee(state, readable, shift.getBranchId(), shift.getEmployeeId());
    }

    private void recordEvent(Shift shift, long actor, ShiftEvent.Type type, Map<String, Object> before, String reason) {
        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        String afterJson = objectMapper.writeValueAsString(toValues(shift));
        shiftEventRepository.save(new ShiftEvent(shift.getId(), actor, type, beforeJson, afterJson, reason));
    }

    /** The shift values the contract keeps in shift_events; version is the API one, as the screen showed it. */
    static Map<String, Object> toValues(Shift shift) {
        Map<String, Object> values = new LinkedHashMap<>();
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
    private List<ShiftView> toViews(List<Shift> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> ids = rows.stream()
                .map(Shift::getId)
                .toList();
        Map<Long, ShiftEvent> cancelled = new HashMap<>();
        shiftEventRepository.findByShiftIdInAndEventTypeOrderByIdAsc(ids, ShiftEvent.Type.CANCELLED)
                .forEach(event -> cancelled.put(event.getShiftId(), event));
        Map<Long, List<AttendanceSummary>> attended = attendanceViews.findLinkedTo(ids);
        Set<Long> people = new LinkedHashSet<>();
        rows.forEach(row -> {
            people.add(row.getEmployeeId());
            people.add(row.getCreatedBy());
        });
        cancelled.values().forEach(event -> people.add(event.getActorEmployeeId()));
        Map<Long, String> names = employeeAccountService.names(people);

        return rows.stream()
                .map(row -> toView(row, cancelled.get(row.getId()), attended, names, state))
                .toList();
    }

    private ShiftView toView(
            Shift row,
            ShiftEvent cancel,
            Map<Long, List<AttendanceSummary>> attended,
            Map<Long, String> names,
            AuthorizationState state
    ) {
        long minutes = Duration.between(row.getStartsAt(), row.getEndsAt()).toMinutes();
        ShiftCancellation cancellation = row.cancelled() && cancel != null
                ? new ShiftCancellation(
                        cancel.getCreatedAt(),
                        toActor(cancel.getActorEmployeeId(), names),
                        cancel.getReason()
                )
                : null;
        boolean editable = !row.cancelled()
                && authorizationService.permits(state, row.getBranchId(), BranchAction.MANAGE_SHIFTS);

        return new ShiftView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getStatus().label(),
                Long.toString(row.getBranchId()),
                toActor(row.getEmployeeId(), names),
                row.getStartsAt(),
                row.getEndsAt(),
                ShiftInputs.toDay(row.getStartsAt()),
                row.getPlannedBreakMinutes(),
                minutes - row.getPlannedBreakMinutes(),
                cancellation,
                attended.getOrDefault(row.getId(), List.of()),
                toActor(row.getCreatedBy(), names),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                editable
        );
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
