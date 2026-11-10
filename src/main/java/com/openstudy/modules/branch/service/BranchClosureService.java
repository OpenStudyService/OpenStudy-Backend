package com.openstudy.modules.branch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final String CREATE = "branch-closures:create";
    private static final String ENTITY = "branch-closures";
    private static final int NOTE = 200;
    private static final int AHEAD_DAYS = 366;
    private static final int DEFAULT_DAYS = 90;
    private static final int HOME_DAYS = 7;
    private static final int WARNINGS = 100;
    private static final Map<String, String> RESERVATION_LABELS = Map.of("CONFIRMED", "확정", "IN_USE", "이용 중");

    private final BranchClosureRepository closures;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore keys;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public BranchClosureList list(long actor, long branch, String from, String to) {
        var state = policy.current(actor);
        policy.require(state, Set.of(branch), BranchAction.READ);
        LocalDate today = today();
        if ((from == null) != (to == null)) throw ApiException.validation(from == null ? "from" : "to", "시작일과 종료일을 함께 입력해 주세요.");
        LocalDate start = from == null ? today : date(from, "from");
        LocalDate end = to == null ? today.plusDays(DEFAULT_DAYS) : date(to, "to");
        if (end.isBefore(start)) throw ApiException.validation("to", "종료일은 시작일 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(start, end) > AHEAD_DAYS)
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간은 최대 366일입니다.", "to", "기간을 줄여 주세요.");
        return new BranchClosureList(Long.toString(branch), start, end, views(closures.between(Set.of(branch), start, end), state));
    }

    public BranchClosureCreated create(long actor, long branch, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("date", "note");
        LocalDate day = date(input.text("date", 10), "date");
        String note = input.has("note") ? input.text("note", NOTE) : null;
        validKey(key);
        LocalDate today = today();
        if (day.isBefore(today) || day.isAfter(today.plusDays(AHEAD_DAYS)))
            throw ApiException.invalid("CLOSURE_DATE_INVALID", "휴무일은 오늘부터 366일 뒤까지 등록할 수 있습니다.", "date",
                    "오늘 이후 366일 안의 날짜를 입력해 주세요.");
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, day.toString(), note)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_CLOSURES,
                () -> prior.set(keys.lock(actor, CREATE, scopeKey, key)), state -> {
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var existing = closures.findByBranchIdAndClosedOn(branch, day);
            if (existing.isPresent())
                throw OperationsConflict.of("BRANCH_CLOSURE_EXISTS", "이미 휴무일로 등록된 날짜입니다.", "closureId", existing.get().getId().toString());
            var saved = closures.saveAndFlush(BranchClosure.of(branch, day, note, actor));
            audit.record(AuditEvent.change(actor, branch, ENTITY, saved.getId(), "CREATED", null, null,
                    json.writeValueAsString(snapshot(saved)), requestId));
            Instant dayStart = day.atStartOfDay(SEOUL).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(SEOUL).toInstant();
            var affected = closures.reservationsStarting(branch, dayStart, dayEnd, Limit.of(WARNINGS)).stream()
                    .map(row -> new AffectedReservation(row[0].toString(), row[1].toString(), (String) row[2], (Instant) row[3],
                            (Instant) row[4], RESERVATION_LABELS.get((String) row[5])))
                    .toList();
            var result = new BranchClosureCreated(views(List.of(saved), state).getFirst(), affected,
                    closures.countReservationsStarting(branch, dayStart, dayEnd));
            keys.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Only the note changes; the day is fixed (delete and register again). */
    public BranchClosureView update(long actor, long id, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "note");
        long version = input.version("version");
        if (!input.has("note")) throw ApiException.validation("note", "안내 문구를 입력해 주세요. 지우려면 null을 보냅니다.");
        String note = body.get("note") == null ? null : input.text("note", NOTE);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> closures.branchOf(id), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.MANAGE_CLOSURES);
            var closure = closures.locked(id).orElseThrow(BranchClosureService::notFound);
            ApiVersion.requireCurrent(version, closure.getVersion(), reload(closure));
            String before = json.writeValueAsString(snapshot(closure));
            closure.rewrite(note);
            var saved = closures.saveAndFlush(closure);
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null, before, json.writeValueAsString(snapshot(saved)),
                    requestId));
            return views(List.of(saved), state).getFirst();
        });
    }

    /** A day that has come is kept as a record: only a future closure is deleted (state before version, as in M12). */
    public void delete(long actor, long id, String version, String requestId) {
        long requested = version(version);
        commands.inRecordBranch(actor, BranchAction.READ, () -> closures.branchOf(id), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.MANAGE_CLOSURES);
            var closure = closures.locked(id).orElseThrow(BranchClosureService::notFound);
            if (!closure.getClosedOn().isAfter(today()))
                throw ApiException.conflict("CLOSURE_ALREADY_STARTED", "오늘이거나 지난 휴무일은 삭제할 수 없습니다.");
            ApiVersion.requireCurrent(requested, closure.getVersion(), reload(closure));
            String before = json.writeValueAsString(snapshot(closure));
            closures.delete(closure);
            closures.flush();
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "DELETED", null, before, null, requestId));
            return null;
        });
    }

    // ---- reads for other features --------------------------------------------------------------------------------

    /** The closure of a branch on a day, if any: a room reservation starting that day is refused (M13-D01). */
    @Transactional(readOnly = true)
    public boolean closed(long branch, LocalDate day) {
        return closures.findByBranchIdAndClosedOn(branch, day).isPresent();
    }

    /** Closure days of a branch that meet [from, to), in order: the room calendar's {@code closedDates}. */
    @Transactional(readOnly = true)
    public List<LocalDate> closedDates(long branch, Instant from, Instant to) {
        LocalDate first = LocalDate.ofInstant(from, SEOUL);
        LocalDate last = LocalDate.ofInstant(to.minusNanos(1), SEOUL);
        return closures.between(Set.of(branch), first, last).stream().map(BranchClosure::getClosedOn).toList();
    }

    /** Closures of the branches from today through seven days ahead, date then branch order: the home summary's notice. */
    @Transactional(readOnly = true)
    public List<ClosureNotice> upcoming(Collection<Long> branches, LocalDate today) {
        if (branches.isEmpty()) return List.of();
        var rows = closures.between(branches, today, today.plusDays(HOME_DAYS));
        var names = new HashMap<Long, String>();
        if (!rows.isEmpty())
            closures.branchNames(rows.stream().map(BranchClosure::getBranchId).distinct().toList())
                    .forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        return rows.stream().map(row -> new ClosureNotice(Long.toString(row.getBranchId()), names.get(row.getBranchId()),
                row.getClosedOn(), row.getNote(), row.getClosedOn().equals(today))).toList();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private List<BranchClosureView> views(List<BranchClosure> rows, AuthorizationState state) {
        var names = new HashMap<Long, String>();
        if (!rows.isEmpty())
            closures.employeeNames(rows.stream().map(BranchClosure::getCreatedBy).distinct().toList())
                    .forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        LocalDate today = today();
        return rows.stream().map(row -> {
            boolean canEdit = policy.permits(state, row.getBranchId(), BranchAction.MANAGE_CLOSURES);
            return new BranchClosureView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()),
                    Long.toString(row.getBranchId()), row.getClosedOn(), row.getNote(),
                    new BranchClosureView.ActorSummary(Long.toString(row.getCreatedBy()), names.get(row.getCreatedBy())),
                    row.getCreatedAt(), row.getUpdatedAt(), canEdit, canEdit && row.getClosedOn().isAfter(today));
        }).toList();
    }

    private Optional<BranchClosureCreated> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            keys.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), BranchClosureCreated.class));
    }

    private static Map<String, Object> snapshot(BranchClosure closure) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("date", closure.getClosedOn().toString());
        snapshot.put("note", closure.getNote());
        return snapshot;
    }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), SEOUL); }

    private static String reload(BranchClosure closure) {
        return "/api/branches/" + closure.getBranchId() + "/closures?from=" + closure.getClosedOn() + "&to=" + closure.getClosedOn();
    }

    static LocalDate date(String value, String field) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format", String.valueOf(value), 0);
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "YYYY-MM-DD 형식의 날짜를 입력해 주세요.");
        }
    }

    private static long version(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,15}")) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed > ApiVersion.MAX) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.validation("version", "올바른 버전을 입력해 주세요.");
        }
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static ApiException notFound() { return ApiException.notFound("휴무일을 찾을 수 없습니다."); }
}
