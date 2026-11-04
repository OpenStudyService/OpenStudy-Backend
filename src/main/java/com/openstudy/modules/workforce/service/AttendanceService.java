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
import com.openstudy.common.outbox.WorkspaceOutbox;
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
    private final AttendanceRecordRepository records;
    private final AttendanceBreakRepository breaks;
    private final ShiftRepository shifts;
    private final ShiftSearchRepository search;
    private final AttendanceViews views;
    private final RecordScope readers;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    /**
     * The requester's attendance in progress, or null. Unlike the list, also at a branch whose membership has ended: an
     * open attendance blocks every other clock-in until its owner closes it (workforce-api.md "조회 범위").
     */
    @Transactional(readOnly = true)
    public CurrentAttendance current(long actor) {
        var state = policy.current(actor);
        var open = records.findFirstByEmployeeIdAndStatus(state.employeeId(), AttendanceRecord.Status.WORKING);
        return new CurrentAttendance(open.map(views::record).orElse(null), clock.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceRecordView> list(long actor, String scope, ListQuery query, String employeeId, String mine, String shiftId,
            String status, String from, String to) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        Long shift = shiftId == null ? null : ShiftInputs.id(shiftId, "shiftId");
        var state = status == null ? null : state(status);
        LocalDate first = from == null ? null : ShiftInputs.date(from, "from");
        LocalDate last = to == null ? null : ShiftInputs.date(to, "to");
        if (first != null && last != null && last.isBefore(first)) throw ApiException.validation("to", "종료 날짜는 시작 날짜보다 앞설 수 없습니다.");
        var reader = policy.current(actor);
        var filter = readers.filter(reader, scope, employee, own, null, first == null ? null : ShiftInputs.startOf(first),
                last == null ? null : ShiftInputs.startOf(last.plusDays(1)));
        var page = search.findAttendance(filter, shift, state, query.offset(), query.pageSize());
        return new PageResponse<>(views.records(page.rows()), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 for a record the reader may not see, whatever the reason, so ids are not an existence oracle (M8-D02). */
    @Transactional(readOnly = true)
    public AttendanceRecordView get(long actor, long id) {
        var state = policy.current(actor);
        var visible = readers.readable(state);
        return records.findById(id).filter(found -> readers.sees(state, visible, found.getBranchId(), found.getEmployeeId()))
                .map(views::record).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The contract's order: malformed input, not an active member of the branch (an OWNER needs a membership row too,
     * fk_attendance_records_1), a stopped branch, an attendance already in progress at any branch. The shift containing
     * the clock-in instant at the same branch, if any, is linked once; a planned shift alone never clocks anybody in.
     */
    public AttendanceRecordView clockIn(long actor, String key, Map<String, Object> body) {
        long branch = new StrictInput(body).only("branchId").id("branchId");
        ShiftInputs.validKey(key);
        // Without a lock first, as the shift commands do: a request that may not clock in here waits for nothing.
        if (!member(policy.current(actor), branch)) throw AuthorizationService.forbidden();
        // One key space per person, not per branch: the same key sent again with another branch is another input (409),
        // not a second clock-in.
        String scopeKey = "me";
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(List.of(branch)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            prior.set(requests.lock(actor, CLOCK_IN, scopeKey, key));
            shifts.lockEmployee(actor);
        }, state -> {
            // The membership as read under lock (FOR SHARE after the guard); only then may a stored result be replayed.
            if (!member(state, branch)) throw AuthorizationService.forbidden();
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 출근할 수 없습니다.");
            if (records.lockedCurrent(actor).isPresent()) throw alreadyWorking();
            Instant now = now();
            Long shift = shifts.covering(actor, branch, ShiftStatus.SCHEDULED, now).stream().findFirst().map(found -> found.getId()).orElse(null);
            var record = translating(() -> records.saveAndFlush(new AttendanceRecord(actor, branch, shift, now)));
            outbox.invalidate(branch, ENTITY, record.getId());
            var result = views.record(record);
            requests.complete(actor, CLOCK_IN, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Only while working; the attendance's branch must still be the requester's: a break is new activity there. */
    public AttendanceRecordView startBreak(long actor, Map<String, Object> body) {
        return command(actor, body, (state, record) -> {
            if (open(record).isPresent()) throw ApiException.conflict("ATTENDANCE_ON_BREAK", "이미 휴게 중입니다.");
            return () -> {
                if (!member(state, record.getBranchId())) throw AuthorizationService.forbidden();
                breaks.saveAndFlush(new AttendanceBreak(record.getId(), now()));
                record.touch();
            };
        });
    }

    /** Only on a break. Also after the membership ended and at a stopped branch: closing up is always allowed. */
    public AttendanceRecordView endBreak(long actor, Map<String, Object> body) {
        return command(actor, body, (state, record) -> {
            var open = open(record).orElseThrow(() -> ApiException.conflict("ATTENDANCE_NOT_ON_BREAK", "휴게 중이 아닙니다."));
            return () -> {
                open.end(now());
                breaks.saveAndFlush(open);
                record.touch();
            };
        });
    }

    /** Not on a break: the break is ended first (M8-D07). Also after the membership ended and at a stopped branch. */
    public AttendanceRecordView clockOut(long actor, Map<String, Object> body) {
        return command(actor, body, (state, record) -> {
            if (open(record).isPresent()) throw ApiException.conflict("ATTENDANCE_ON_BREAK", "휴게를 먼저 종료해 주세요.");
            return () -> record.clockOut(now());
        });
    }

    /**
     * Whether a workspace stream may hear of this attendance: the OWNER, the MANAGER of its branch or its own employee,
     * the readers of M8-D02. A branch event alone would reach every member of the branch (workforce-api.md "workspace 이벤트").
     */
    @Transactional(readOnly = true)
    public boolean announcedTo(AuthorizationState state, long branch, long attendanceId) {
        if (readers.manages(state, branch)) return true;
        return records.findById(attendanceId).filter(record -> record.getEmployeeId() == state.employeeId()).isPresent();
    }

    /**
     * Shared frame of a break and a clock-out: the requester's attendance in progress FOR UPDATE, then its state, then
     * the version, so a repeated command learns what happened ("이미 퇴근") rather than that its version is old. The
     * state check returns the change to make once the version has matched.
     */
    private AttendanceRecordView command(long actor, Map<String, Object> body,
            BiFunction<AuthorizationState, AttendanceRecord, Runnable> check) {
        long version = new StrictInput(body).only("version").version("version");
        var locked = new AtomicReference<Optional<AttendanceRecord>>(Optional.empty());
        return commands.activeEmployee(actor, () -> locked.set(records.lockedCurrent(actor)), state -> {
            var record = locked.get().orElseThrow(() -> ApiException.conflict("ATTENDANCE_NOT_WORKING", "진행 중인 근태가 없습니다."));
            var change = check.apply(state, record);
            ApiVersion.requireCurrent(version, record.getVersion(), RELOAD);
            change.run();
            var saved = records.saveAndFlush(record);
            outbox.invalidate(saved.getBranchId(), ENTITY, saved.getId());
            return views.record(saved);
        });
    }

    /** An active membership row at the branch. The OWNER's global role does not count: it is not a membership. */
    private static boolean member(AuthorizationState state, long branch) {
        var membership = state.memberships().get(branch);
        return state.branches().containsKey(branch) && membership != null && "ACTIVE".equals(membership.status());
    }

    private Optional<AttendanceBreak> open(AttendanceRecord record) {
        return breaks.locked(record.getId()).stream().filter(part -> part.getEndedAt() == null).findFirst();
    }

    /** Server time without its fraction of a second, so every difference is whole seconds (M8-D07). */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }

    private Optional<AttendanceRecordView> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), AttendanceRecordView.class));
    }

    /**
     * uk_attendance_records_1 is the last line of defence: should a clock-in ever get past the locked check, the client
     * gets the 409 the check would have given and the whole command rolls back.
     */
    private static <T> T translating(Supplier<T> insert) {
        try {
            return DuplicateKeys.translate(insert);
        } catch (DuplicateKeyException exception) {
            if (String.valueOf(exception.getMessage()).contains("uk_attendance_records_1")) throw alreadyWorking();
            throw exception;
        }
    }

    private static ApiException alreadyWorking() {
        return ApiException.conflict("ATTENDANCE_ALREADY_WORKING", "이미 출근 중입니다. 먼저 퇴근해 주세요.");
    }

    private static ShiftSearchRepository.AttendanceState state(String value) {
        return switch (value) {
            case "근무 중" -> ShiftSearchRepository.AttendanceState.WORKING;
            case "휴게 중" -> ShiftSearchRepository.AttendanceState.ON_BREAK;
            case "퇴근" -> ShiftSearchRepository.AttendanceState.LEFT;
            default -> throw ApiException.validation("status", "허용되지 않은 값입니다.");
        };
    }
}
