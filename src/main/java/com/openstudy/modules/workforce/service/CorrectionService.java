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
import com.openstudy.modules.workforce.dto.response.AttendanceConflict;
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
    private static final String CANCELLATION_LABEL = "신청 취소";
    private static final String CORRECTION_LOCATION = "/api/attendance-corrections/";
    private static final String UNKNOWN_NAME = "";
    private static final int REASON_MAX_LENGTH = 500;
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration MAX_ATTENDANCE = Duration.ofHours(24);

    private final AttendanceCorrectionRepository attendanceCorrectionRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceBreakRepository attendanceBreakRepository;
    private final ShiftRepository shiftRepository;
    private final LeaveSearchRepository leaveSearchRepository;
    private final RecordScope recordScope;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private record Context(AuthorizationState state, AttendanceCorrection correction, AttendanceRecord record) {
    }

    private interface Command {

        void apply(Context context);
    }

    /** What the request body asks: the kind, the corrected interval, the break for a break correction, and why. */
    private record Asked(String kind, Long breakId, Instant start, Instant end, String reason) {

        static Asked parse(Map<String, Object> body) {
            StrictInput input = new StrictInput(body);
            String kind = input.choice("kind", CLOCK, BREAK);

            if (CLOCK.equals(kind)) {
                input.only("kind", "clockInAt", "clockOutAt", "reason");

                return new Asked(
                        kind,
                        null,
                        ShiftInputs.parseInstant(body.get("clockInAt"), "clockInAt"),
                        ShiftInputs.parseInstant(body.get("clockOutAt"), "clockOutAt"),
                        input.text("reason", REASON_MAX_LENGTH)
                );
            }

            input.only("kind", "breakId", "breakStartedAt", "breakEndedAt", "reason");

            return new Asked(
                    kind,
                    input.id("breakId"),
                    ShiftInputs.parseInstant(body.get("breakStartedAt"), "breakStartedAt"),
                    ShiftInputs.parseInstant(body.get("breakEndedAt"), "breakEndedAt"),
                    input.text("reason", REASON_MAX_LENGTH)
            );
        }

        /** Whole seconds (M8-D07), start before end, nothing in the future, and a clock-in to clock-out of at most 24 hours. */
        void requireValid(Instant now) {
            String startField = CLOCK.equals(kind) ? "clockInAt" : "breakStartedAt";
            String endField = CLOCK.equals(kind) ? "clockOutAt" : "breakEndedAt";

            if (start.getNano() != 0) {
                throw createTimeError(startField, "초 단위까지만 입력해 주세요.");
            }

            if (end.getNano() != 0) {
                throw createTimeError(endField, "초 단위까지만 입력해 주세요.");
            }

            if (!start.isBefore(end)) {
                throw createTimeError(endField, "종료는 시작보다 늦어야 합니다.");
            }

            if (end.isAfter(now)) {
                throw createTimeError(endField, "미래 시각으로 정정할 수 없습니다.");
            }

            boolean tooLong = CLOCK.equals(kind) && Duration.between(start, end).compareTo(MAX_ATTENDANCE) > 0;

            if (tooLong) {
                throw createTimeError(endField, "출근부터 퇴근까지 24시간 이하여야 합니다.");
            }
        }

        private static ApiException createTimeError(String field, String message) {
            return ApiException.invalid("CORRECTION_TIME_INVALID", "정정 시각을 확인해 주세요.", field, message);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceCorrectionView> list(
            long actor,
            String scope,
            ListQuery query,
            String employeeId,
            String mine,
            String status,
            String attendanceRecordId,
            String from,
            String to
    ) {
        Long employee = employeeId == null ? null : ShiftInputs.parseId(employeeId, "employeeId");
        boolean own = ShiftInputs.parseFlag(mine, "mine");
        AttendanceCorrection.Status filterStatus = status == null
                ? null
                : AttendanceCorrection.Status.valueOf(LeaveInputs.parseStatus(status).name());
        Long attendance = attendanceRecordId == null
                ? null
                : ShiftInputs.parseId(attendanceRecordId, "attendanceRecordId");
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
        LeaveSearchRepository.Page<AttendanceCorrection> page = leaveSearchRepository.findCorrections(
                readable,
                managed,
                actor,
                person,
                filterStatus,
                attendance,
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
    public AttendanceCorrectionView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);

        return toViews(List.of(findVisible(state, id)), state).getFirst();
    }

    /**
     * Only the attendance's own employee, only once it is closed, one undecided correction per attendance. The overlap
     * and break checks of the approval run here too, to tell at once; nothing changes on the attendance (M12-D01).
     */
    public AttendanceCorrectionView create(long actor, long recordId, String key, Map<String, Object> body) {
        AuthorizationState reader = authorizationService.current(actor);
        Set<Long> readable = recordScope.findReadable(reader);
        AttendanceRecord found = attendanceRecordRepository.findById(recordId)
                .filter(row -> recordScope.canSee(reader, readable, row.getBranchId(), row.getEmployeeId()))
                .orElseThrow(CorrectionService::createNotFoundError);

        if (found.getEmployeeId() != actor) {
            throw AuthorizationService.forbidden();
        }

        Asked asked = Asked.parse(body);
        ShiftInputs.validateKey(key);
        asked.requireValid(clock.instant());
        String scopeKey = Long.toString(recordId);
        List<Object> fingerprintValues = Arrays.asList(
                recordId,
                asked.kind(),
                asked.breakId(),
                toText(asked.start()),
                toText(asked.end()),
                asked.reason()
        );
        byte[] fingerprint = ShiftInputs.digest(objectMapper.writeValueAsString(fingerprintValues));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<AttendanceRecord>> locked = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    prior.set(idempotencyStore.lock(actor, CREATE, scopeKey, key));
                    shiftRepository.lockEmployee(actor);
                    locked.set(attendanceRecordRepository.findShared(recordId));
                },
                state -> {
                    Optional<AttendanceCorrectionView> replayed = findReplay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Set<Long> visibleNow = recordScope.findReadable(state);
                    AttendanceRecord record = locked.get()
                            .filter(row -> recordScope.canSee(
                                    state,
                                    visibleNow,
                                    row.getBranchId(),
                                    row.getEmployeeId()
                            ))
                            .orElseThrow(CorrectionService::createNotFoundError);

                    if (record.getEmployeeId() != actor) {
                        throw AuthorizationService.forbidden();
                    }

                    List<AttendanceBreak> parts = attendanceBreakRepository.findLocked(recordId);
                    AttendanceCorrection correction = createCorrection(actor, recordId, record, parts, asked);

                    if (record.working()) {
                        throw ApiException.conflict("CORRECTION_TARGET_OPEN", "퇴근한 근태만 정정을 신청할 수 있습니다.");
                    }

                    List<AttendanceCorrection> pending = attendanceCorrectionRepository.findForAttendance(
                            recordId,
                            AttendanceCorrection.Status.REQUESTED
                    );

                    if (!pending.isEmpty()) {
                        throw WorkforceRequestRefusal.pendingCorrection(pending.getFirst().getId());
                    }

                    requireFits(state, record, parts, correction);
                    AttendanceCorrection saved = attendanceCorrectionRepository.saveAndFlush(correction);
                    AttendanceCorrectionView result = toViews(List.of(saved), state).getFirst();
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

    /** Checks the corrected attendance again under lock, then changes it and its version; worked minutes follow (M8-D07). */
    public AttendanceCorrectionView approve(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        AttendanceCorrection known = findDecidable(actor, id);

        return runCommand(actor, id, known, version, true, context -> {
            AttendanceCorrection row = context.correction();
            AttendanceRecord record = context.record();
            List<AttendanceBreak> parts = attendanceBreakRepository.findLocked(record.getId());
            requireFits(context.state(), record, parts, row);

            if (row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK) {
                record.correct(row.getRequestedClockInAt(), row.getRequestedClockOutAt());
            } else {
                AttendanceBreak target = parts.stream()
                        .filter(part -> part.getId().equals(row.getBreakId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Correction of a break that is gone"));
                target.correct(row.getAfterBreakStartAt(), row.getAfterBreakEndAt());
                attendanceBreakRepository.saveAndFlush(target);
                record.touch();
            }

            attendanceRecordRepository.saveAndFlush(record);
            row.approve(actor, clock.instant());
        });
    }

    /** The attendance does not change; the reason and the handler are kept in audit_events (2026-11-09 user decision). */
    public AttendanceCorrectionView reject(long actor, long id, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.text("reason", REASON_MAX_LENGTH);
        AttendanceCorrection known = findDecidable(actor, id);

        return runCommand(actor, id, known, version, true, context -> {
            context.correction().reject(actor, clock.instant());
            auditTrail.record(AuditEvent.change(
                    actor,
                    context.record().getBranchId(),
                    ENTITY,
                    id,
                    REJECTED,
                    reason,
                    null,
                    null,
                    requestId
            ));
        });
    }

    /** The requester withdraws an undecided request; also after the membership ended (tidying up). */
    public AttendanceCorrectionView cancel(long actor, long id, Map<String, Object> body) {
        long version = new StrictInput(body).only("version").version("version");
        AttendanceCorrection known = attendanceCorrectionRepository.findById(id)
                .orElseThrow(CorrectionService::createNotFoundError);

        if (known.getRequestedBy() != actor) {
            findVisible(authorizationService.current(actor), id);

            throw AuthorizationService.forbidden();
        }

        return runCommand(
                actor,
                id,
                known,
                version,
                false,
                context -> context.correction().withdraw(actor, clock.instant())
        );
    }

    private static AttendanceCorrection createCorrection(
            long actor,
            long recordId,
            AttendanceRecord record,
            List<AttendanceBreak> parts,
            Asked asked
    ) {
        if (CLOCK.equals(asked.kind())) {
            boolean unchanged = asked.start().equals(record.getClockInAt())
                    && asked.end().equals(record.getClockOutAt());

            if (unchanged) {
                throw createUnchangedError();
            }

            return AttendanceCorrection.clock(
                    recordId,
                    actor,
                    record.getClockInAt(),
                    record.getClockOutAt(),
                    asked.start(),
                    asked.end(),
                    asked.reason()
            );
        }

        AttendanceBreak target = parts.stream()
                .filter(part -> part.getId().equals(asked.breakId()))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("breakId", "이 근태의 휴게가 아닙니다."));
        boolean unchanged = asked.start().equals(target.getStartedAt()) && asked.end().equals(target.getEndedAt());

        if (unchanged) {
            throw createUnchangedError();
        }

        return AttendanceCorrection.pause(
                recordId,
                actor,
                target.getId(),
                target.getStartedAt(),
                target.getEndedAt(),
                asked.start(),
                asked.end(),
                asked.reason()
        );
    }

    /** Before any lock: 404 for a correction the reader cannot see, 403 unless they manage the attendance branch, 403 for their own. */
    private AttendanceCorrection findDecidable(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        AttendanceCorrection correction = findVisible(state, id);
        long branch = attendanceRecordRepository.findById(correction.getAttendanceRecordId())
                .map(AttendanceRecord::getBranchId)
                .orElseThrow(CorrectionService::createNotFoundError);
        requireDecider(state, correction, branch);

        return correction;
    }

    /**
     * Shared frame: the guard, the requester's employee row, the correction row, the attendance row; the same 404 and 403s
     * on the state read under lock; then the status before the version, so a repeated command is told what happened.
     */
    private AttendanceCorrectionView runCommand(
            long actor,
            long id,
            AttendanceCorrection known,
            long version,
            boolean decider,
            Command command
    ) {
        AtomicReference<Optional<AttendanceCorrection>> lockedCorrection = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<AttendanceRecord>> lockedRecord = new AtomicReference<>(Optional.empty());

        return authorizedCommands.activeEmployee(
                actor,
                () -> {
                    shiftRepository.lockEmployee(known.getRequestedBy());
                    lockedCorrection.set(attendanceCorrectionRepository.findLocked(id));
                    lockedRecord.set(attendanceRecordRepository.findLocked(known.getAttendanceRecordId()));
                },
                state -> {
                    AttendanceCorrection correction = lockedCorrection.get()
                            .orElseThrow(CorrectionService::createNotFoundError);
                    AttendanceRecord record = lockedRecord.get()
                            .orElseThrow(CorrectionService::createNotFoundError);

                    if (decider) {
                        Set<Long> readable = recordScope.findReadable(state);

                        if (!recordScope.canSee(state, readable, record.getBranchId(), correction.getRequestedBy())) {
                            throw createNotFoundError();
                        }

                        requireDecider(state, correction, record.getBranchId());
                    } else if (correction.getRequestedBy() != actor) {
                        throw AuthorizationService.forbidden();
                    }

                    if (correction.getStatus() != AttendanceCorrection.Status.REQUESTED) {
                        String message = decider ? "이미 처리된 정정 신청입니다." : "승인 전 신청만 취소할 수 있습니다.";

                        throw WorkforceRequestRefusal.invalidState(
                                message,
                                ApiVersion.fromDatabase(correction.getVersion()),
                                toReloadUrl(id)
                        );
                    }

                    ApiVersion.requireCurrent(version, correction.getVersion(), toReloadUrl(id));
                    command.apply(new Context(state, correction, record));
                    AttendanceCorrection saved = attendanceCorrectionRepository.saveAndFlush(correction);

                    return toViews(List.of(saved), state).getFirst();
                }
        );
    }

    private void requireDecider(AuthorizationState state, AttendanceCorrection correction, long branch) {
        if (!recordScope.canManage(state, branch)) {
            throw AuthorizationService.forbidden();
        }

        if (correction.getRequestedBy() == state.employeeId()) {
            throw WorkforceRequestRefusal.selfDecision();
        }
    }

    /**
     * M12-D08 on the attendance as the correction would leave it: a clock correction must not meet another attendance of
     * the employee at any branch; then every break must lie inside the stretch without overlapping another.
     */
    private void requireFits(
            AuthorizationState state,
            AttendanceRecord record,
            List<AttendanceBreak> parts,
            AttendanceCorrection row
    ) {
        boolean clockKind = row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK;
        Instant in = clockKind ? row.getRequestedClockInAt() : record.getClockInAt();
        Instant out = clockKind ? row.getRequestedClockOutAt() : record.getClockOutAt();

        if (clockKind) {
            requireNoOverlap(state, record, in, out);
        }

        List<AttendanceConflicts.Placed> placed = new ArrayList<>();

        for (AttendanceBreak part : parts) {
            if (part.getEndedAt() == null) {
                continue;
            }

            boolean corrected = !clockKind && part.getId().equals(row.getBreakId());
            placed.add(corrected
                    ? new AttendanceConflicts.Placed(part.getId(), row.getAfterBreakStartAt(), row.getAfterBreakEndAt())
                    : AttendanceConflicts.Placed.of(part));
        }

        List<String> misplaced = AttendanceConflicts.findMisplaced(in, out, placed);

        if (!misplaced.isEmpty()) {
            throw WorkforceRequestRefusal.breakOutside(misplaced);
        }
    }

    private void requireNoOverlap(AuthorizationState state, AttendanceRecord record, Instant in, Instant out) {
        List<AttendanceRecord> others = attendanceRecordRepository.findMeeting(
                record.getEmployeeId(),
                record.getId(),
                in,
                out
        );

        if (others.isEmpty()) {
            return;
        }

        Set<Long> readable = recordScope.findReadable(state);
        List<AttendanceConflict> conflicts = AttendanceConflicts.findOverlaps(
                in,
                out,
                others,
                clock.instant(),
                other -> recordScope.canSee(state, readable, other.getBranchId(), other.getEmployeeId())
        );

        if (!conflicts.isEmpty()) {
            throw WorkforceRequestRefusal.attendanceOverlap(conflicts);
        }
    }

    private AttendanceCorrection findVisible(AuthorizationState state, long id) {
        AttendanceCorrection correction = attendanceCorrectionRepository.findById(id)
                .orElseThrow(CorrectionService::createNotFoundError);
        AttendanceRecord record = attendanceRecordRepository.findById(correction.getAttendanceRecordId())
                .orElseThrow(CorrectionService::createNotFoundError);

        Set<Long> readable = recordScope.findReadable(state);

        if (!recordScope.canSee(state, readable, record.getBranchId(), correction.getRequestedBy())) {
            throw createNotFoundError();
        }

        return correction;
    }

    /** The one mapping behind every correction response. */
    private List<AttendanceCorrectionView> toViews(List<AttendanceCorrection> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> recordIds = rows.stream()
                .map(AttendanceCorrection::getAttendanceRecordId)
                .distinct()
                .toList();
        Map<Long, AttendanceRecord> byId = attendanceRecordRepository.findAllById(recordIds)
                .stream()
                .collect(Collectors.toMap(AttendanceRecord::getId, Function.identity()));
        List<Long> rejected = rows.stream()
                .filter(row -> row.getStatus() == AttendanceCorrection.Status.REJECTED)
                .map(AttendanceCorrection::getId)
                .toList();
        Map<Long, String> reasons = new HashMap<>();
        auditTrail.recorded(ENTITY, REJECTED, rejected)
                .forEach(event -> reasons.put(event.getEntityId(), event.getReason()));
        Set<Long> people = new LinkedHashSet<>();
        rows.forEach(row -> {
            people.add(row.getRequestedBy());

            if (row.getDecidedBy() != null) {
                people.add(row.getDecidedBy());
            }
        });
        Map<Long, String> names = employeeAccountService.names(people);

        return rows.stream()
                .map(row -> toView(row, byId.get(row.getAttendanceRecordId()), reasons, names, state))
                .toList();
    }

    private AttendanceCorrectionView toView(
            AttendanceCorrection row,
            AttendanceRecord record,
            Map<Long, String> reasons,
            Map<Long, String> names,
            AuthorizationState state
    ) {
        AttendanceCorrection.Status status = row.getStatus();
        boolean decided = status == AttendanceCorrection.Status.APPROVED
                || status == AttendanceCorrection.Status.REJECTED;
        CorrectionDecision decision = decided
                ? new CorrectionDecision(
                        toActor(row.getDecidedBy(), names),
                        row.getDecidedAt(),
                        reasons.get(row.getId())
                )
                : null;
        RequestCancellation cancellation = status == AttendanceCorrection.Status.CANCELLED
                ? new RequestCancellation(CANCELLATION_LABEL, toActor(row.getDecidedBy(), names), row.getDecidedAt())
                : null;
        boolean clockKind = row.getCorrectionKind() == AttendanceCorrection.Kind.CLOCK;
        AttendanceCorrectionValues before = clockKind
                ? new AttendanceCorrectionValues(row.getBeforeClockInAt(), row.getBeforeClockOutAt(), null, null)
                : new AttendanceCorrectionValues(null, null, row.getBeforeBreakStartAt(), row.getBeforeBreakEndAt());
        AttendanceCorrectionValues after = clockKind
                ? new AttendanceCorrectionValues(row.getRequestedClockInAt(), row.getRequestedClockOutAt(), null, null)
                : new AttendanceCorrectionValues(null, null, row.getAfterBreakStartAt(), row.getAfterBreakEndAt());
        boolean requested = status == AttendanceCorrection.Status.REQUESTED;
        boolean decider = recordScope.canManage(state, record.getBranchId())
                && row.getRequestedBy() != state.employeeId();
        boolean ownRequest = row.getRequestedBy() == state.employeeId();

        return new AttendanceCorrectionView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                LeaveRequest.Status.valueOf(status.name()).label,
                clockKind ? CLOCK : BREAK,
                record.getId().toString(),
                Long.toString(record.getBranchId()),
                toActor(row.getRequestedBy(), names),
                row.getBreakId() == null ? null : row.getBreakId().toString(),
                before,
                after,
                row.getReason(),
                decision,
                cancellation,
                row.getCreatedAt(),
                requested && decider,
                requested && decider,
                requested && ownRequest
        );
    }

    private Optional<AttendanceCorrectionView> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), AttendanceCorrectionView.class));
    }

    private static ApiException createUnchangedError() {
        return ApiException.unprocessable("CORRECTION_UNCHANGED", "정정 값이 현재 값과 같습니다.");
    }

    private static String toText(Instant at) {
        return at.toString();
    }

    private static String toReloadUrl(long id) {
        return CORRECTION_LOCATION + id;
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
