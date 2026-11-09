package com.openstudy.modules.workforce.service;

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
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workforce.dto.response.ActorSummary;
import com.openstudy.modules.workforce.dto.response.AttendanceCorrectionValues;
import com.openstudy.modules.workforce.dto.response.AttendanceCorrectionView;
import com.openstudy.modules.workforce.dto.response.CorrectionDecision;
import com.openstudy.modules.workforce.dto.response.RequestCancellation;
import com.openstudy.modules.workforce.entity.AttendanceBreak;
import com.openstudy.modules.workforce.entity.AttendanceCorrection;
import com.openstudy.modules.workforce.entity.AttendanceRecord;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.repository.AttendanceBreakRepository;
import com.openstudy.modules.workforce.repository.AttendanceCorrectionRepository;
import com.openstudy.modules.workforce.repository.AttendanceRecordRepository;
import com.openstudy.modules.workforce.repository.LeaveSearchRepository;
import com.openstudy.modules.workforce.repository.ShiftRepository;

/**
 * Attendance corrections (#107, FR-CORR-01~04, ADR-M12-001 M12-D01·D05·D08, workforce-requests-api.md "근태 정정"). The
 * employee asks to correct the clock-in/out or one break of their own closed attendance; the values at that moment are
 * kept as before_*, and the attendance changes only when the attendance branch's MANAGER or the OWNER approves, never
 * the requester (2026-11-09 user decision). Worked minutes are not stored: the M8 reading of the changed row gives them.
 * <p>Every command locks the requester's employee row after the foundation guard, then the correction row, then the
 * attendance row, so two decisions on one correction run one at a time and the attendance changes once. An approved
 * correction is never cancelled (M12-D08); a new correction starts from the corrected values. A rejection's reason is
 * kept in audit_events (2026-11-09 user decision: V1 has no column for it on the correction).</p>
 */
@Service
@RequiredArgsConstructor
public class CorrectionService {
    static final String ENTITY = "attendance-corrections";
    static final String REJECTED = "REJECTED";
    private static final String CREATE = "attendance-corrections:create";
    private static final String CLOCK = "출퇴근";
    private static final String BREAK = "휴게";
    private final AttendanceCorrectionRepository corrections;
    private final AttendanceRecordRepository records;
    private final AttendanceBreakRepository breaks;
    private final ShiftRepository shifts;
    private final LeaveSearchRepository search;
    private final RecordScope readers;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore keys;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AttendanceCorrectionView> list(long actor, String scope, ListQuery query, String employeeId, String mine,
            String status, String attendanceRecordId, String from, String to) {
        Long employee = employeeId == null ? null : ShiftInputs.id(employeeId, "employeeId");
        boolean own = ShiftInputs.flag(mine, "mine");
        var filterStatus = status == null ? null : AttendanceCorrection.Status.valueOf(LeaveInputs.status(status).name());
        Long attendance = attendanceRecordId == null ? null : ShiftInputs.id(attendanceRecordId, "attendanceRecordId");
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
        var page = search.corrections(readable, managed, actor, person, filterStatus, attendance,
                first == null ? null : ShiftInputs.startOf(first), last == null ? null : ShiftInputs.startOf(last.plusDays(1)),
                query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public AttendanceCorrectionView get(long actor, long id) {
        var state = policy.current(actor);
        return views(List.of(visible(state, id)), state).getFirst();
    }

    /**
     * Only the attendance's own employee, only once it is closed, one undecided correction per attendance. The overlap
     * and break checks of the approval run here too, to tell at once; nothing changes on the attendance (M12-D01).
     */
    public AttendanceCorrectionView create(long actor, long recordId, String key, Map<String, Object> body) {
        var reader = policy.current(actor);
        var readable = readers.readable(reader);
        var found = records.findById(recordId).filter(row -> readers.sees(reader, readable, row.getBranchId(), row.getEmployeeId()))
                .orElseThrow(CorrectionService::notFound);
        if (found.getEmployeeId() != actor) throw AuthorizationService.forbidden();
        var asked = Asked.parse(body);
        ShiftInputs.validKey(key);
        asked.requireValid(clock.instant());
        String scopeKey = Long.toString(recordId);
        byte[] fingerprint = ShiftInputs.digest(json.writeValueAsString(Arrays.asList(recordId, asked.kind(), asked.breakId(),
                text(asked.start()), text(asked.end()), asked.reason())));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        var locked = new AtomicReference<Optional<AttendanceRecord>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            prior.set(keys.lock(actor, CREATE, scopeKey, key));
            shifts.lockEmployee(actor);
            locked.set(records.shared(recordId));
        }, state -> {
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var visibleNow = readers.readable(state);
            var record = locked.get().filter(row -> readers.sees(state, visibleNow, row.getBranchId(), row.getEmployeeId()))
                    .orElseThrow(CorrectionService::notFound);
            if (record.getEmployeeId() != actor) throw AuthorizationService.forbidden();
            var parts = breaks.locked(recordId);
            AttendanceCorrection correction;
            if (CLOCK.equals(asked.kind())) {
                if (asked.start().equals(record.getClockInAt()) && asked.end().equals(record.getClockOutAt())) throw unchanged();
                correction = AttendanceCorrection.clock(recordId, actor, record.getClockInAt(), record.getClockOutAt(), asked.start(),
                        asked.end(), asked.reason());
            } else {
                var target = parts.stream().filter(part -> part.getId().equals(asked.breakId())).findFirst()
                        .orElseThrow(() -> ApiException.validation("breakId", "이 근태의 휴게가 아닙니다."));
                if (asked.start().equals(target.getStartedAt()) && asked.end().equals(target.getEndedAt())) throw unchanged();
                correction = AttendanceCorrection.pause(recordId, actor, target.getId(), target.getStartedAt(), target.getEndedAt(),
                        asked.start(), asked.end(), asked.reason());
            }
            if (record.working()) throw ApiException.conflict("CORRECTION_TARGET_OPEN", "퇴근한 근태만 정정을 신청할 수 있습니다.");
            var pending = corrections.forAttendance(recordId, AttendanceCorrection.Status.REQUESTED);
            if (!pending.isEmpty()) throw WorkforceRequestRefusal.pendingCorrection(pending.getFirst().getId());
            requireFits(state, record, parts, correction);
            var saved = corrections.saveAndFlush(correction);
            var result = views(List.of(saved), state).getFirst();
            keys.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Checks the corrected attendance again under lock, then changes it and its version; worked minutes follow (M8-D07). */
    public AttendanceCorrectionView approve(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var known = decidable(actor, id);
        return frame(actor, id, known, version, true, context -> {
            var row = context.correction();
            var record = context.record();
            var parts = breaks.locked(record.getId());
            requireFits(context.state(), record, parts, row);
            if (row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK) {
                record.correct(row.getRequestedClockInAt(), row.getRequestedClockOutAt());
            } else {
                var target = parts.stream().filter(part -> part.getId().equals(row.getBreakId())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Correction of a break that is gone"));
                target.correct(row.getAfterBreakStartAt(), row.getAfterBreakEndAt());
                breaks.saveAndFlush(target);
                record.touch();
            }
            records.saveAndFlush(record);
            row.approve(actor, clock.instant());
        });
    }

    /** The attendance does not change; the reason and the handler are kept in audit_events (2026-11-09 user decision). */
    public AttendanceCorrectionView reject(long actor, long id, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.text("reason", 500);
        var known = decidable(actor, id);
        return frame(actor, id, known, version, true, context -> {
            context.correction().reject(actor, clock.instant());
            audit.record(AuditEvent.change(actor, context.record().getBranchId(), ENTITY, id, REJECTED, reason, null, null, requestId));
        });
    }

    /** The requester withdraws an undecided request; also after the membership ended (tidying up). */
    public AttendanceCorrectionView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        var known = corrections.findById(id).orElseThrow(CorrectionService::notFound);
        if (known.getRequestedBy() != actor) {
            visible(policy.current(actor), id);
            throw AuthorizationService.forbidden();
        }
        return frame(actor, id, known, version, false, context -> context.correction().withdraw(actor, clock.instant()));
    }

    private record Context(AuthorizationState state, AttendanceCorrection correction, AttendanceRecord record) {}

    private interface Command { void apply(Context context); }

    /** What the request body asks: the kind, the corrected interval, the break for a break correction, and why. */
    private record Asked(String kind, Long breakId, Instant start, Instant end, String reason) {
        static Asked parse(Map<String, Object> body) {
            var input = new StrictInput(body);
            String kind = input.choice("kind", CLOCK, BREAK);
            if (CLOCK.equals(kind)) {
                input.only("kind", "clockInAt", "clockOutAt", "reason");
                return new Asked(kind, null, ShiftInputs.instant(body.get("clockInAt"), "clockInAt"),
                        ShiftInputs.instant(body.get("clockOutAt"), "clockOutAt"), input.text("reason", 500));
            }
            input.only("kind", "breakId", "breakStartedAt", "breakEndedAt", "reason");
            return new Asked(kind, input.id("breakId"), ShiftInputs.instant(body.get("breakStartedAt"), "breakStartedAt"),
                    ShiftInputs.instant(body.get("breakEndedAt"), "breakEndedAt"), input.text("reason", 500));
        }

        /** Whole seconds (M8-D07), start before end, nothing in the future, and a clock-in to clock-out of at most 24 hours. */
        void requireValid(Instant now) {
            String startField = CLOCK.equals(kind) ? "clockInAt" : "breakStartedAt";
            String endField = CLOCK.equals(kind) ? "clockOutAt" : "breakEndedAt";
            if (start.getNano() != 0) throw time(startField, "초 단위까지만 입력해 주세요.");
            if (end.getNano() != 0) throw time(endField, "초 단위까지만 입력해 주세요.");
            if (!start.isBefore(end)) throw time(endField, "종료는 시작보다 늦어야 합니다.");
            if (end.isAfter(now)) throw time(endField, "미래 시각으로 정정할 수 없습니다.");
            if (CLOCK.equals(kind) && Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0)
                throw time(endField, "출근부터 퇴근까지 24시간 이하여야 합니다.");
        }

        private static ApiException time(String field, String message) {
            return ApiException.invalid("CORRECTION_TIME_INVALID", "정정 시각을 확인해 주세요.", field, message);
        }
    }

    /** Before any lock: 404 for a correction the reader cannot see, 403 unless they manage the attendance branch, 403 for their own. */
    private AttendanceCorrection decidable(long actor, long id) {
        var state = policy.current(actor);
        var correction = visible(state, id);
        requireDecider(state, correction, records.findById(correction.getAttendanceRecordId()).map(AttendanceRecord::getBranchId)
                .orElseThrow(CorrectionService::notFound));
        return correction;
    }

    /**
     * Shared frame: the guard, the requester's employee row, the correction row, the attendance row; the same 404 and 403s
     * on the state read under lock; then the status before the version, so a repeated command is told what happened.
     */
    private AttendanceCorrectionView frame(long actor, long id, AttendanceCorrection known, long version, boolean decider, Command command) {
        var lockedCorrection = new AtomicReference<Optional<AttendanceCorrection>>(Optional.empty());
        var lockedRecord = new AtomicReference<Optional<AttendanceRecord>>(Optional.empty());
        return commands.activeEmployee(actor, () -> {
            shifts.lockEmployee(known.getRequestedBy());
            lockedCorrection.set(corrections.locked(id));
            lockedRecord.set(records.locked(known.getAttendanceRecordId()));
        }, state -> {
            var correction = lockedCorrection.get().orElseThrow(CorrectionService::notFound);
            var record = lockedRecord.get().orElseThrow(CorrectionService::notFound);
            if (decider) {
                if (!readers.sees(state, readers.readable(state), record.getBranchId(), correction.getRequestedBy())) throw notFound();
                requireDecider(state, correction, record.getBranchId());
            } else if (correction.getRequestedBy() != actor) {
                throw AuthorizationService.forbidden();
            }
            if (correction.getStatus() != AttendanceCorrection.Status.REQUESTED)
                throw WorkforceRequestRefusal.invalidState(decider ? "이미 처리된 정정 신청입니다." : "승인 전 신청만 취소할 수 있습니다.",
                        ApiVersion.fromDatabase(correction.getVersion()), reload(id));
            ApiVersion.requireCurrent(version, correction.getVersion(), reload(id));
            command.apply(new Context(state, correction, record));
            return views(List.of(corrections.saveAndFlush(correction)), state).getFirst();
        });
    }

    private void requireDecider(AuthorizationState state, AttendanceCorrection correction, long branch) {
        if (!readers.manages(state, branch)) throw AuthorizationService.forbidden();
        if (correction.getRequestedBy() == state.employeeId()) throw WorkforceRequestRefusal.selfDecision();
    }

    /**
     * M12-D08 on the attendance as the correction would leave it: a clock correction must not meet another attendance of
     * the employee at any branch; then every break must lie inside the stretch without overlapping another.
     */
    private void requireFits(AuthorizationState state, AttendanceRecord record, List<AttendanceBreak> parts, AttendanceCorrection row) {
        boolean clock = row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK;
        Instant in = clock ? row.getRequestedClockInAt() : record.getClockInAt();
        Instant out = clock ? row.getRequestedClockOutAt() : record.getClockOutAt();
        if (clock) {
            var others = records.meeting(record.getEmployeeId(), record.getId(), in, out);
            if (!others.isEmpty()) {
                var readable = readers.readable(state);
                var conflicts = AttendanceConflicts.overlaps(in, out, others, this.clock.instant(),
                        other -> readers.sees(state, readable, other.getBranchId(), other.getEmployeeId()));
                if (!conflicts.isEmpty()) throw WorkforceRequestRefusal.attendanceOverlap(conflicts);
            }
        }
        var placed = new ArrayList<AttendanceConflicts.Placed>();
        for (var part : parts) {
            if (part.getEndedAt() == null) continue;
            placed.add(!clock && part.getId().equals(row.getBreakId())
                    ? new AttendanceConflicts.Placed(part.getId(), row.getAfterBreakStartAt(), row.getAfterBreakEndAt())
                    : AttendanceConflicts.Placed.of(part));
        }
        var misplaced = AttendanceConflicts.misplaced(in, out, placed);
        if (!misplaced.isEmpty()) throw WorkforceRequestRefusal.breakOutside(misplaced);
    }

    private AttendanceCorrection visible(AuthorizationState state, long id) {
        var correction = corrections.findById(id).orElseThrow(CorrectionService::notFound);
        var record = records.findById(correction.getAttendanceRecordId()).orElseThrow(CorrectionService::notFound);
        if (!readers.sees(state, readers.readable(state), record.getBranchId(), correction.getRequestedBy())) throw notFound();
        return correction;
    }

    /** The one mapping behind every correction response. */
    private List<AttendanceCorrectionView> views(List<AttendanceCorrection> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var recordIds = rows.stream().map(AttendanceCorrection::getAttendanceRecordId).distinct().toList();
        Map<Long, AttendanceRecord> byId = records.findAllById(recordIds).stream().collect(Collectors.toMap(AttendanceRecord::getId, Function.identity()));
        var rejected = rows.stream().filter(row -> row.getStatus() == AttendanceCorrection.Status.REJECTED).map(AttendanceCorrection::getId).toList();
        var reasons = new HashMap<Long, String>();
        audit.recorded(ENTITY, REJECTED, rejected).forEach(event -> reasons.put(event.getEntityId(), event.getReason()));
        var people = new LinkedHashSet<Long>();
        rows.forEach(row -> { people.add(row.getRequestedBy()); if (row.getDecidedBy() != null) people.add(row.getDecidedBy()); });
        var names = employees.names(people);
        return rows.stream().map(row -> {
            var record = byId.get(row.getAttendanceRecordId());
            var status = row.getStatus();
            CorrectionDecision decision = status == AttendanceCorrection.Status.APPROVED || status == AttendanceCorrection.Status.REJECTED
                    ? new CorrectionDecision(actor(row.getDecidedBy(), names), row.getDecidedAt(), reasons.get(row.getId())) : null;
            RequestCancellation cancellation = status == AttendanceCorrection.Status.CANCELLED
                    ? new RequestCancellation("신청 취소", actor(row.getDecidedBy(), names), row.getDecidedAt()) : null;
            boolean clockKind = row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK;
            var before = clockKind ? new AttendanceCorrectionValues(row.getBeforeClockInAt(), row.getBeforeClockOutAt(), null, null)
                    : new AttendanceCorrectionValues(null, null, row.getBeforeBreakStartAt(), row.getBeforeBreakEndAt());
            var after = clockKind ? new AttendanceCorrectionValues(row.getRequestedClockInAt(), row.getRequestedClockOutAt(), null, null)
                    : new AttendanceCorrectionValues(null, null, row.getAfterBreakStartAt(), row.getAfterBreakEndAt());
            boolean requested = status == AttendanceCorrection.Status.REQUESTED;
            boolean decider = readers.manages(state, record.getBranchId()) && row.getRequestedBy() != state.employeeId();
            return new AttendanceCorrectionView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()),
                    LeaveRequest.Status.valueOf(status.name()).label, clockKind ? CLOCK : BREAK, record.getId().toString(),
                    Long.toString(record.getBranchId()), actor(row.getRequestedBy(), names),
                    row.getBreakId() == null ? null : row.getBreakId().toString(), before, after, row.getReason(), decision, cancellation,
                    row.getCreatedAt(), requested && decider, requested && decider, requested && row.getRequestedBy() == state.employeeId());
        }).toList();
    }

    private Optional<AttendanceCorrectionView> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            keys.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), AttendanceCorrectionView.class));
    }

    private static ApiException unchanged() {
        return ApiException.unprocessable("CORRECTION_UNCHANGED", "정정 값이 현재 값과 같습니다.");
    }

    private static String text(Instant at) { return at.toString(); }

    private static String reload(long id) { return "/api/attendance-corrections/" + id; }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), Objects.requireNonNullElse(names.get(id), ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
