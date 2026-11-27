package com.openstudy.modules.workforce.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.workforce.dto.response.AttendanceRecordView;
import com.openstudy.modules.workforce.dto.response.CurrentAttendance;
import com.openstudy.modules.workforce.entity.AttendanceBreak;
import com.openstudy.modules.workforce.entity.AttendanceRecord;
import com.openstudy.modules.workforce.entity.ShiftStatus;
import com.openstudy.modules.workforce.repository.AttendanceBreakRepository;
import com.openstudy.modules.workforce.repository.AttendanceRecordRepository;
import com.openstudy.modules.workforce.repository.ShiftRepository;
import com.openstudy.modules.workforce.repository.ShiftSearchRepository;

/**
 * Clocking in and out (FR-ATT-01~05, ADR-M8-001). Every command acts on the requester's own attendance in progress and
 * takes no employee or attendance id, so nobody can change somebody else's (M8-D08). Every time is the server's, cut to
 * whole seconds, so worked minutes have one answer (M8-D07).
 * <p>The commands run inside the foundation guard of {@link AuthorizedCommands} like every other write; after it, a
 * clock-in takes its request key and the employee row, a break or a clock-out the attendance row, all FOR UPDATE
 * (workforce-api.md "처리 순서"). uk_attendance_records_1 keeps one attendance in progress per employee even if a path
 * ever skips those locks.</p>
 */
@Service
@RequiredArgsConstructor
public class AttendanceService {

    static final String ENTITY = "attendance-records";
    private static final String CLOCK_IN = "attendance:clock-in";
    private static final String RELOAD = "/api/me/attendance/current";
    private static final String ACTIVE = "ACTIVE";
    /** One key space per person, not per branch. */
    private static final String PERSONAL_SCOPE_KEY = "me";
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String UNIQUE_ACTIVE_ATTENDANCE = "uk_attendance_records_1";

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceBreakRepository attendanceBreakRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftSearchRepository shiftSearchRepository;
    private final AttendanceViews attendanceViews;
    private final RecordScope recordScope;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    /**
     * The requester's attendance in progress, or null. Unlike the list, also at a branch whose membership has ended: an
     * open attendance blocks every other clock-in until its owner closes it (workforce-api.md "조회 범위").
     */
    @Transactional(readOnly = true)
    public CurrentAttendance current(long actor) {
        AuthorizationState state = authorizationService.current(actor);
        Optional<AttendanceRecord> open = attendanceRecordRepository.findFirstByEmployeeIdAndStatus(
                state.employeeId(),
                AttendanceRecord.Status.WORKING
        );
        AttendanceRecordView view = open.map(attendanceViews::toView).orElse(null);

        return new CurrentAttendance(view, clock.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceRecordView> list(
            long actor,
            String scope,
            ListQuery query,
            String employeeId,
            String mine,
            String shiftId,
            String status,
            String from,
            String to
    ) {
        Long employee = employeeId == null ? null : ShiftInputs.parseId(employeeId, "employeeId");
        boolean own = ShiftInputs.parseFlag(mine, "mine");
        Long shift = shiftId == null ? null : ShiftInputs.parseId(shiftId, "shiftId");
        ShiftSearchRepository.AttendanceState state = status == null ? null : parseState(status);
        LocalDate first = from == null ? null : ShiftInputs.parseDate(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.parseDate(to, "to");

        if (first != null && last != null && last.isBefore(first)) {
            throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        }

        AuthorizationState reader = authorizationService.current(actor);
        Instant start = first == null ? null : ShiftInputs.toStartOf(first);
        Instant end = last == null ? null : ShiftInputs.toStartOf(last.plusDays(1));
        ShiftSearchRepository.Filter filter = recordScope.createFilter(reader, scope, employee, own, null, start, end);
        ShiftSearchRepository.AttendancePage page = shiftSearchRepository.findAttendance(
                filter,
                shift,
                state,
                query.offset(),
                query.pageSize()
        );

        return new PageResponse<>(
                attendanceViews.toViews(page.rows()),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    /** 404 for a record the reader may not see, whatever the reason, so ids are not an existence oracle (M8-D02). */
    @Transactional(readOnly = true)
    public AttendanceRecordView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> visible = recordScope.findReadable(state);

        return attendanceRecordRepository.findById(id)
                .filter(found -> recordScope.canSee(state, visible, found.getBranchId(), found.getEmployeeId()))
                .map(attendanceViews::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The contract's order: malformed input, not an active member of the branch (an OWNER needs a membership row too,
     * fk_attendance_records_1), a stopped branch, an attendance already in progress at any branch. The shift containing
     * the clock-in instant at the same branch, if any, is linked once; a planned shift alone never clocks anybody in.
     */
    public AttendanceRecordView clockIn(long actor, String key, Map<String, Object> body) {
        long branch = new StrictInput(body).only("branchId").id("branchId");
        ShiftInputs.validateKey(key);

        // Without a lock first, as the shift commands do: a request that may not clock in here waits for nothing.
        if (!isMember(authorizationService.current(actor), branch)) {
            throw AuthorizationService.forbidden();
        }

        // One key space per person, not per branch: the same key sent again with another branch is another input (409),
        // not a second clock-in.
        String scopeKey = PERSONAL_SCOPE_KEY;
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(List.of(branch)));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CLOCK_IN, scopeKey, key));
                    shiftRepository.lockEmployee(actor);
                },
                state -> {
                    // The membership as read under lock (FOR SHARE after the guard); only then may a stored result be
                    // replayed.
                    if (!isMember(state, branch)) {
                        throw AuthorizationService.forbidden();
                    }

                    Optional<AttendanceRecordView> replayed = findReplay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    if (!ACTIVE.equals(state.branches().get(branch))) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 출근할 수 없습니다.");
                    }

                    if (attendanceRecordRepository.lockedCurrent(actor).isPresent()) {
                        throw createAlreadyWorkingError();
                    }

                    Instant now = findNow();
                    Long shift = shiftRepository.findCovering(actor, branch, ShiftStatus.SCHEDULED, now)
                            .stream()
                            .findFirst()
                            .map(found -> found.getId())
                            .orElse(null);
                    AttendanceRecord attendance = new AttendanceRecord(actor, branch, shift, now);
                    AttendanceRecord record = translateDuplicate(
                            () -> attendanceRecordRepository.saveAndFlush(attendance)
                    );
                    AttendanceRecordView result = attendanceViews.toView(record);
                    idempotencyStore.complete(
                            actor,
                            CLOCK_IN,
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

    /** Only while working; the attendance's branch must still be the requester's: a break is new activity there. */
    public AttendanceRecordView startBreak(long actor, Map<String, Object> body) {
        return runCommand(actor, body, (state, record) -> {
            if (findOpenBreak(record).isPresent()) {
                throw ApiException.conflict("ATTENDANCE_ON_BREAK", "이미 휴게 중입니다.");
            }

            return () -> {
                if (!isMember(state, record.getBranchId())) {
                    throw AuthorizationService.forbidden();
                }

                attendanceBreakRepository.saveAndFlush(new AttendanceBreak(record.getId(), findNow()));
                record.touch();
            };
        });
    }

    /** Only on a break. Also after the membership ended and at a stopped branch: closing up is always allowed. */
    public AttendanceRecordView endBreak(long actor, Map<String, Object> body) {
        return runCommand(actor, body, (state, record) -> {
            AttendanceBreak open = findOpenBreak(record)
                    .orElseThrow(() -> ApiException.conflict("ATTENDANCE_NOT_ON_BREAK", "휴게 중이 아닙니다."));

            return () -> {
                open.end(findNow());
                attendanceBreakRepository.saveAndFlush(open);
                record.touch();
            };
        });
    }

    /** Not on a break: the break is ended first (M8-D07). Also after the membership ended and at a stopped branch. */
    public AttendanceRecordView clockOut(long actor, Map<String, Object> body) {
        return runCommand(actor, body, (state, record) -> {
            if (findOpenBreak(record).isPresent()) {
                throw ApiException.conflict("ATTENDANCE_ON_BREAK", "휴게를 먼저 종료해 주세요.");
            }

            return () -> record.clockOut(findNow());
        });
    }

    /**
     * Shared frame of a break and a clock-out: the requester's attendance in progress FOR UPDATE, then its state, then
     * the version, so a repeated command learns what happened ("이미 퇴근") rather than that its version is old. The
     * state check returns the change to make once the version has matched.
     */
    private AttendanceRecordView runCommand(
            long actor,
            Map<String, Object> body,
            BiFunction<AuthorizationState, AttendanceRecord, Runnable> check
    ) {
        long version = new StrictInput(body).only("version").version("version");
        AtomicReference<Optional<AttendanceRecord>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> locked.set(attendanceRecordRepository.lockedCurrent(actor)),
                state -> {
                    AttendanceRecord record = locked.get()
                            .orElseThrow(() -> ApiException.conflict("ATTENDANCE_NOT_WORKING", "진행 중인 근태가 없습니다."));
                    Runnable change = check.apply(state, record);
                    ApiVersion.requireCurrent(version, record.getVersion(), RELOAD);
                    change.run();
                    AttendanceRecord saved = attendanceRecordRepository.saveAndFlush(record);

                    return attendanceViews.toView(saved);
                }
        );
    }

    /** An active membership row at the branch. The OWNER's global role does not count: it is not a membership. */
    private static boolean isMember(AuthorizationState state, long branch) {
        AuthorizationState.Membership membership = state.memberships().get(branch);

        return state.branches().containsKey(branch) && membership != null && ACTIVE.equals(membership.status());
    }

    private Optional<AttendanceBreak> findOpenBreak(AttendanceRecord record) {
        return attendanceBreakRepository.findLocked(record.getId())
                .stream()
                .filter(part -> part.getEndedAt() == null)
                .findFirst();
    }

    /** Server time without its fraction of a second, so every difference is whole seconds (M8-D07). */
    private Instant findNow() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private Optional<AttendanceRecordView> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), AttendanceRecordView.class));
    }

    /**
     * uk_attendance_records_1 is the last line of defence: should a clock-in ever get past the locked check, the client
     * gets the 409 the check would have given and the whole command rolls back.
     */
    private static <T> T translateDuplicate(Supplier<T> insert) {
        try {
            return DuplicateKeys.translate(insert);
        } catch (DuplicateKeyException exception) {
            if (String.valueOf(exception.getMessage()).contains(UNIQUE_ACTIVE_ATTENDANCE)) {
                throw createAlreadyWorkingError();
            }

            throw exception;
        }
    }

    private static ApiException createAlreadyWorkingError() {
        return ApiException.conflict("ATTENDANCE_ALREADY_WORKING", "이미 출근 중입니다. 먼저 퇴근해 주세요.");
    }

    private static ShiftSearchRepository.AttendanceState parseState(String value) {
        return switch (value) {
            case "근무 중" -> ShiftSearchRepository.AttendanceState.WORKING;
            case "휴게 중" -> ShiftSearchRepository.AttendanceState.ON_BREAK;
            case "퇴근" -> ShiftSearchRepository.AttendanceState.LEFT;
            default -> throw ApiException.validation("status", "허용되지 않은 값입니다.");
        };
    }
}
