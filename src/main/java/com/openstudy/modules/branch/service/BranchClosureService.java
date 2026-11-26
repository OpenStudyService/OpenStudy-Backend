package com.openstudy.modules.branch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.exception.OperationsConflict;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.branch.dto.response.BranchClosureCreated;
import com.openstudy.modules.branch.dto.response.BranchClosureCreated.AffectedReservation;
import com.openstudy.modules.branch.dto.response.BranchClosureList;
import com.openstudy.modules.branch.dto.response.BranchClosureView;
import com.openstudy.modules.branch.dto.response.ClosureNotice;
import com.openstudy.modules.branch.entity.BranchClosure;
import com.openstudy.modules.branch.repository.BranchClosureRepository;

/**
 * Branch closures (operations-extension-api.md "지점 휴무일", M13-D01·D05). A closure is a whole Asia/Seoul day: it refuses
 * new room reservations that start on it and is announced on the calendar and the home summary. Registering one warns
 * about the reservations already starting that day and cancels none of them. Every command runs in the common lock order,
 * which locks every branch row, so a closure and a reservation of the same branch never pass each other.
 */
@Service
@RequiredArgsConstructor
public class BranchClosureService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String CREATE_OPERATION = "branch-closures:create";
    private static final String AUDIT_ENTITY_TYPE = "branch-closures";
    private static final int MAX_NOTE_LENGTH = 200;
    private static final int MAX_DATE_LENGTH = 10;
    private static final int MAX_AHEAD_DAYS = 366;
    private static final int DEFAULT_PERIOD_DAYS = 90;
    private static final int HOME_NOTICE_DAYS = 7;
    private static final int MAX_AFFECTED_RESERVATIONS = 100;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final String DATE_FORMAT = "\\d{4}-\\d{2}-\\d{2}";
    private static final String VERSION_FORMAT = "[1-9][0-9]{0,15}";
    private static final Map<String, String> RESERVATION_LABELS = Map.of("CONFIRMED", "확정", "IN_USE", "이용 중");

    private final BranchClosureRepository branchClosureRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public BranchClosureList list(long actor, long branch, String from, String to) {
        AuthorizationState state = authorizationService.current(actor);
        authorizationService.require(state, Set.of(branch), BranchAction.READ);
        LocalDate today = findToday();

        if ((from == null) != (to == null)) {
            String missingField = from == null ? "from" : "to";
            throw ApiException.validation(missingField, "시작일과 종료일을 함께 입력해 주세요.");
        }

        LocalDate start = from == null ? today : parseDate(from, "from");
        LocalDate end = to == null ? today.plusDays(DEFAULT_PERIOD_DAYS) : parseDate(to, "to");

        if (end.isBefore(start)) {
            throw ApiException.validation("to", "종료일은 시작일 이후여야 합니다.");
        }

        if (ChronoUnit.DAYS.between(start, end) > MAX_AHEAD_DAYS) {
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간은 최대 366일입니다.", "to", "기간을 줄여 주세요.");
        }

        List<BranchClosure> rows = branchClosureRepository.findBetween(Set.of(branch), start, end);
        return new BranchClosureList(Long.toString(branch), start, end, toViews(rows, state));
    }

    public BranchClosureCreated create(
            long actor,
            long branch,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only("date", "note");
        LocalDate day = parseDate(input.text("date", MAX_DATE_LENGTH), "date");
        String note = input.has("note") ? input.text("note", MAX_NOTE_LENGTH) : null;
        requireValidKey(key);

        LocalDate today = findToday();
        boolean outOfRange = day.isBefore(today) || day.isAfter(today.plusDays(MAX_AHEAD_DAYS));

        if (outOfRange) {
            throw ApiException.invalid(
                    "CLOSURE_DATE_INVALID",
                    "휴무일은 오늘부터 366일 뒤까지 등록할 수 있습니다.",
                    "date",
                    "오늘 이후 366일 안의 날짜를 입력해 주세요."
            );
        }

        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(branch, day.toString(), note)));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_CLOSURES,
                () -> prior.set(idempotencyStore.lock(actor, CREATE_OPERATION, scopeKey, key)),
                state -> {
                    Optional<BranchClosureCreated> replayed = replay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    requireNoClosureOn(branch, day);

                    BranchClosure closure = BranchClosure.of(branch, day, note, actor);
                    BranchClosure saved = branchClosureRepository.saveAndFlush(closure);
                    recordChange(actor, branch, saved.getId(), "CREATED", null, toSnapshotJson(saved), requestId);

                    BranchClosureCreated result = toCreated(saved, state, branch, day);
                    idempotencyStore.complete(
                            actor,
                            CREATE_OPERATION,
                            scopeKey,
                            key,
                            fingerprint,
                            CREATED_STATUS,
                            objectMapper.writeValueAsString(result),
                            clock.instant().plus(KEY_RETENTION)
                    );
                    return result;
                }
        );
    }

    /** Only the note changes; the day is fixed (delete and register again). */
    public BranchClosureView update(long actor, long id, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "note");
        long version = input.version("version");

        if (!input.has("note")) {
            throw ApiException.validation("note", "안내 문구를 입력해 주세요. 지우려면 null을 보냅니다.");
        }

        String note = body.get("note") == null ? null : input.text("note", MAX_NOTE_LENGTH);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> branchClosureRepository.findBranchIdById(id),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.MANAGE_CLOSURES);
                    BranchClosure closure = findForUpdate(id);
                    ApiVersion.requireCurrent(version, closure.getVersion(), toReloadPath(closure));

                    String before = toSnapshotJson(closure);
                    closure.rewrite(note);
                    BranchClosure saved = branchClosureRepository.saveAndFlush(closure);
                    recordChange(actor, branch, id, "UPDATED", before, toSnapshotJson(saved), requestId);

                    return toViews(List.of(saved), state).getFirst();
                }
        );
    }

    /** A day that has come is kept as a record: only a future closure is deleted (state before version, as in M12). */
    public void delete(long actor, long id, String version, String requestId) {
        long requested = parseVersion(version);

        authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> branchClosureRepository.findBranchIdById(id),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.MANAGE_CLOSURES);
                    BranchClosure closure = findForUpdate(id);

                    if (!closure.getClosedOn().isAfter(findToday())) {
                        throw ApiException.conflict("CLOSURE_ALREADY_STARTED", "오늘이거나 지난 휴무일은 삭제할 수 없습니다.");
                    }

                    ApiVersion.requireCurrent(requested, closure.getVersion(), toReloadPath(closure));

                    String before = toSnapshotJson(closure);
                    branchClosureRepository.delete(closure);
                    branchClosureRepository.flush();
                    recordChange(actor, branch, id, "DELETED", before, null, requestId);
                    return null;
                }
        );
    }

    // ---- reads for other features --------------------------------------------------------------------------------

    /** The closure of a branch on a day, if any: a room reservation starting that day is refused (M13-D01). */
    @Transactional(readOnly = true)
    public boolean closed(long branch, LocalDate day) {
        return branchClosureRepository.findByBranchIdAndClosedOn(branch, day).isPresent();
    }

    /** Closure days of a branch that meet [from, to), in order: the room calendar's {@code closedDates}. */
    @Transactional(readOnly = true)
    public List<LocalDate> closedDates(long branch, Instant from, Instant to) {
        LocalDate first = LocalDate.ofInstant(from, SEOUL);
        LocalDate last = LocalDate.ofInstant(to.minusNanos(1), SEOUL);

        return branchClosureRepository.findBetween(Set.of(branch), first, last)
                .stream()
                .map(BranchClosure::getClosedOn)
                .toList();
    }

    /** Closures of the branches from today through seven days ahead, date then branch order: the home summary's notice. */
    @Transactional(readOnly = true)
    public List<ClosureNotice> upcoming(Collection<Long> branches, LocalDate today) {
        if (branches.isEmpty()) {
            return List.of();
        }

        LocalDate last = today.plusDays(HOME_NOTICE_DAYS);
        List<BranchClosure> rows = branchClosureRepository.findBetween(branches, today, last);
        Map<Long, String> branchNames = new HashMap<>();

        if (!rows.isEmpty()) {
            List<Long> branchIds = rows.stream()
                    .map(BranchClosure::getBranchId)
                    .distinct()
                    .toList();
            branchNames = toNameMap(branchClosureRepository.findBranchNames(branchIds));
        }

        Map<Long, String> names = branchNames;
        return rows.stream()
                .map(row -> new ClosureNotice(
                        Long.toString(row.getBranchId()),
                        names.get(row.getBranchId()),
                        row.getClosedOn(),
                        row.getNote(),
                        row.getClosedOn().equals(today)
                ))
                .toList();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private void requireNoClosureOn(long branch, LocalDate day) {
        Optional<BranchClosure> existing = branchClosureRepository.findByBranchIdAndClosedOn(branch, day);

        if (existing.isPresent()) {
            throw OperationsConflict.of(
                    "BRANCH_CLOSURE_EXISTS",
                    "이미 휴무일로 등록된 날짜입니다.",
                    "closureId",
                    existing.get().getId().toString()
            );
        }
    }

    private BranchClosureCreated toCreated(BranchClosure saved, AuthorizationState state, long branch, LocalDate day) {
        Instant dayStart = day.atStartOfDay(SEOUL).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(SEOUL).toInstant();

        List<AffectedReservation> affected = branchClosureRepository
                .findReservationsStarting(branch, dayStart, dayEnd, Limit.of(MAX_AFFECTED_RESERVATIONS))
                .stream()
                .map(BranchClosureService::toAffectedReservation)
                .toList();
        BranchClosureView view = toViews(List.of(saved), state).getFirst();
        long affectedCount = branchClosureRepository.countReservationsStarting(branch, dayStart, dayEnd);

        return new BranchClosureCreated(view, affected, affectedCount);
    }

    private List<BranchClosureView> toViews(List<BranchClosure> rows, AuthorizationState state) {
        Map<Long, String> employeeNames = new HashMap<>();

        if (!rows.isEmpty()) {
            List<Long> creatorIds = rows.stream()
                    .map(BranchClosure::getCreatedBy)
                    .distinct()
                    .toList();
            employeeNames = toNameMap(branchClosureRepository.findEmployeeNames(creatorIds));
        }

        Map<Long, String> names = employeeNames;
        LocalDate today = findToday();

        return rows.stream()
                .map(row -> toView(row, state, names, today))
                .toList();
    }

    private BranchClosureView toView(
            BranchClosure row,
            AuthorizationState state,
            Map<Long, String> employeeNames,
            LocalDate today
    ) {
        boolean canEdit = authorizationService.permits(state, row.getBranchId(), BranchAction.MANAGE_CLOSURES);
        boolean canDelete = canEdit && row.getClosedOn().isAfter(today);
        BranchClosureView.ActorSummary createdBy = new BranchClosureView.ActorSummary(
                Long.toString(row.getCreatedBy()),
                employeeNames.get(row.getCreatedBy())
        );

        return new BranchClosureView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                Long.toString(row.getBranchId()),
                row.getClosedOn(),
                row.getNote(),
                createdBy,
                row.getCreatedAt(),
                row.getUpdatedAt(),
                canEdit,
                canDelete
        );
    }

    private Optional<BranchClosureCreated> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord record = prior.get();

        if (!clock.instant().isBefore(record.getExpiresAt())) {
            idempotencyStore.discard(record);
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, record.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(record.getResponseJson(), BranchClosureCreated.class));
    }

    private BranchClosure findForUpdate(long id) {
        return branchClosureRepository.findForUpdateById(id).orElseThrow(BranchClosureService::notFound);
    }

    private void recordChange(
            long actor,
            long branch,
            long id,
            String action,
            String before,
            String after,
            String requestId
    ) {
        AuditEvent event = AuditEvent.change(actor, branch, AUDIT_ENTITY_TYPE, id, action, null, before, after, requestId);
        auditTrail.record(event);
    }

    private String toSnapshotJson(BranchClosure closure) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("date", closure.getClosedOn().toString());
        snapshot.put("note", closure.getNote());

        return objectMapper.writeValueAsString(snapshot);
    }

    private LocalDate findToday() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    private static AffectedReservation toAffectedReservation(Object[] row) {
        return new AffectedReservation(
                row[0].toString(),
                row[1].toString(),
                (String) row[2],
                (Instant) row[3],
                (Instant) row[4],
                RESERVATION_LABELS.get((String) row[5])
        );
    }

    private static Map<Long, String> toNameMap(List<Object[]> rows) {
        Map<Long, String> names = new HashMap<>();
        rows.forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));

        return names;
    }

    private static String toReloadPath(BranchClosure closure) {
        LocalDate day = closure.getClosedOn();

        return "/api/branches/" + closure.getBranchId() + "/closures?from=" + day + "&to=" + day;
    }

    static LocalDate parseDate(String value, String field) {
        try {
            if (value == null || !value.matches(DATE_FORMAT)) {
                throw new DateTimeParseException("format", String.valueOf(value), 0);
            }

            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "YYYY-MM-DD 형식의 날짜를 입력해 주세요.");
        }
    }

    private static long parseVersion(String value) {
        try {
            if (value == null || !value.matches(VERSION_FORMAT)) {
                throw new NumberFormatException();
            }

            long parsed = Long.parseLong(value);

            if (parsed > ApiVersion.MAX) {
                throw new NumberFormatException();
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.validation("version", "올바른 버전을 입력해 주세요.");
        }
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("휴무일을 찾을 수 없습니다.");
    }
}
