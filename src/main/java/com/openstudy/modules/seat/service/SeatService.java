package com.openstudy.modules.seat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
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
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.seat.dto.response.ActiveSessionView;
import com.openstudy.modules.seat.dto.response.SeatBoard;
import com.openstudy.modules.seat.dto.response.SeatCounts;
import com.openstudy.modules.seat.dto.response.SeatOccupancy;
import com.openstudy.modules.seat.dto.response.SeatView;
import com.openstudy.modules.seat.dto.response.SeatZone;
import com.openstudy.modules.seat.entity.Seat;
import com.openstudy.modules.seat.entity.SeatDisplayStatus;
import com.openstudy.modules.seat.entity.SeatKind;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.entity.SeatState;
import com.openstudy.modules.seat.repository.SeatRepository;
import com.openstudy.modules.seat.repository.SeatSearchRepository;
import com.openstudy.modules.seat.repository.SeatSessionRepository;

/**
 * Seat ledger, operating stop/resume and live counts (FR-SEAT-01~05·11·13·15, ADR-M5-001). Any valid member of a branch
 * reads; registering and editing need operations:write there, stopping and resuming also need the MANAGER role. A seat
 * shows "사용 중" only while an active seat session points at it, so that state is computed on every read and is never
 * stored. Every change writes one audit row in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class SeatService {
    private static final String CREATE = "seats:create";
    private static final String ENTITY = "seats";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "status");
    private final SeatRepository repository;
    private final SeatSessionRepository sessions;
    private final SeatSearchRepository search;
    private final CustomerService customers;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<SeatView> list(long actor, String scope, ListQuery query, SeatFilters filters) {
        var state = policy.current(actor);
        var page = search.find(new SeatSearchRepository.Filter(policy.scope(state, scope, BranchAction.READ), filters.zone(),
                filters.kind(), filters.status()), query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows()), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public SeatView get(long actor, long id, String scope) {
        return views(List.of(visible(policy.current(actor), id, scope))).get(0);
    }

    /** One item per branch in scope. A branch without seats still appears, with zeros and a null rate. */
    @Transactional(readOnly = true)
    public SeatOccupancy occupancy(long actor, String scope) {
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var asOf = clock.instant();
        Map<Long, SeatSearchRepository.BranchCounts> found = new HashMap<>();
        search.counts(new SeatSearchRepository.Filter(selected, null, null, null)).forEach(row -> found.put(row.branchId(), row));
        var items = new ArrayList<SeatCounts>();
        for (long branch : new TreeSet<>(selected)) {
            var row = found.getOrDefault(branch, new SeatSearchRepository.BranchCounts(branch, 0, 0, 0, 0));
            items.add(new SeatCounts(Long.toString(branch), row.total(), row.inUse(), row.available(), row.blocked(),
                    rate(row.inUse(), row.total()), asOf));
        }
        return new SeatOccupancy(items);
    }

    /**
     * Every seat of one branch grouped by zone. The counts are tallied from the very seats returned, so the board and its
     * numbers always agree; a kind filter narrows both. A branch the caller cannot read is 404, like a seat outside scope.
     */
    @Transactional(readOnly = true)
    public SeatBoard board(long actor, String branchId, String kind) {
        if (branchId == null) throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        long branch = SeatInputs.id(branchId, "branchId");
        SeatKind filter = kind == null ? null : SeatInputs.kind(kind, "kind");
        var state = policy.current(actor);
        if (!policy.permits(state, branch, BranchAction.READ)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var asOf = clock.instant();
        var seats = views(search.findAll(new SeatSearchRepository.Filter(Set.of(branch), null, filter, null)));
        long inUse = seats.stream().filter(seat -> SeatDisplayStatus.IN_USE.label().equals(seat.status())).count();
        long blocked = seats.stream().filter(seat -> SeatDisplayStatus.BLOCKED.label().equals(seat.status())).count();
        var zones = new LinkedHashMap<String, List<SeatView>>();
        seats.forEach(seat -> zones.computeIfAbsent(seat.zone(), zone -> new ArrayList<>()).add(seat));
        return new SeatBoard(new SeatCounts(Long.toString(branch), seats.size(), inUse, seats.size() - inUse - blocked, blocked,
                rate(inUse, seats.size()), asOf), zones.entrySet().stream().map(zone -> new SeatZone(zone.getKey(), zone.getValue())).toList());
    }

    public SeatView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "zone", "seatNo", "kind", "status");
        long branch = input.id("branchId");
        String zone = input.text("zone", 80);
        String seatNo = input.text("seatNo", 30);
        SeatKind kind = SeatInputs.kind(input.text("kind", 20), "kind");
        SeatState stored = input.has("status") ? SeatInputs.state(input.text("status", 20), "status") : SeatState.AVAILABLE;
        validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, zone, seatNo, kind.name(), stored.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_SEATS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            var now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), SeatView.class);
                }
                requests.discard(prior.get());
            }
            if (repository.existsByBranchIdAndSeatNo(branch, seatNo)) throw duplicate();
            var seat = repository.saveAndFlush(new Seat(branch, zone, seatNo, kind, stored));
            audit.record(AuditEvent.change(actor, branch, ENTITY, seat.getId(), "CREATED", null, null,
                    json.writeValueAsString(snapshot(seat)), requestId));
            outbox.invalidate(branch, ENTITY, seat.getId());
            var result = views(List.of(seat)).get(0);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public SeatView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        var input = new StrictInput(body).only("version", "zone", "seatNo", "kind");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String zone = input.has("zone") ? input.text("zone", 80) : null;
        String seatNo = input.has("seatNo") ? input.text("seatNo", 30) : null;
        SeatKind kind = input.has("kind") ? SeatInputs.kind(input.text("kind", 20), "kind") : null;
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_SEATS, () -> repository.lock(id), state -> {
            var seat = locked(id);
            ApiVersion.requireCurrent(version, seat.getVersion(), reload(id));
            String newZone = zone == null ? seat.getZone() : zone;
            String newSeatNo = seatNo == null ? seat.getSeatNo() : seatNo;
            SeatKind newKind = kind == null ? seat.getKind() : kind;
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("zone", seat.getZone(), newZone, before, after);
            compare("seatNo", seat.getSeatNo(), newSeatNo, before, after);
            compare("kind", seat.getKind().label(), newKind.label(), before, after);
            // An identical request is not a change: no version bump, audit row or event.
            if (before.isEmpty()) return views(List.of(seat)).get(0);
            if (!newSeatNo.equals(seat.getSeatNo()) && repository.existsByBranchIdAndSeatNoAndIdNot(branch, newSeatNo, id))
                throw duplicate();
            seat.change(newZone, newSeatNo, newKind);
            repository.flush();
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null, json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            outbox.invalidate(branch, ENTITY, id);
            return views(List.of(seat)).get(0);
        });
    }

    public SeatView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        SeatState next = SeatInputs.state(input.text("status", 20), "status");
        String reason = input.text("reason", 500);
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_SEATS, () -> repository.lock(id), state -> {
            var seat = locked(id);
            ApiVersion.requireCurrent(version, seat.getVersion(), reload(id));
            SeatState previous = seat.getStatus();
            // The same state again is not a change: the current value comes back with no audit row or event.
            if (previous == next) return views(List.of(seat)).get(0);
            if (next == SeatState.BLOCKED && sessions.existsBySeatIdAndStatus(id, SeatSession.Status.ACTIVE))
                throw ApiException.conflict("SEAT_OCCUPIED", "이용 중인 좌석은 운영 중단할 수 없습니다.");
            seat.changeStatus(next);
            repository.flush();
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                    json.writeValueAsString(Map.of("status", previous.label())),
                    json.writeValueAsString(Map.of("status", next.label())), requestId));
            outbox.invalidate(branch, ENTITY, id);
            return views(List.of(seat)).get(0);
        });
    }

    /** 404 hides seats outside the reader's scope, so IDs are not an existence oracle. */
    private Seat visible(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        return repository.findById(id).filter(seat -> selected.contains(seat.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Seat locked(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Builds views for seats the caller may already see: active sessions and their customers are read in bulk. */
    private List<SeatView> views(List<Seat> seats) {
        if (seats.isEmpty()) return List.of();
        Map<Long, SeatSession> active = new HashMap<>();
        sessions.findByStatusAndSeatIdIn(SeatSession.Status.ACTIVE, seats.stream().map(Seat::getId).toList())
                .forEach(session -> active.put(session.getSeatId(), session));
        Map<Long, CustomerSummary> people = active.isEmpty() ? Map.of()
                : customers.summaries(active.values().stream().map(SeatSession::getCustomerId).toList());
        return seats.stream().map(seat -> {
            var session = active.get(seat.getId());
            ActiveSessionView current = session == null ? null : new ActiveSessionView(session.getId().toString(),
                    people.getOrDefault(session.getCustomerId(), new CustomerSummary(Long.toString(session.getCustomerId()), "", "")),
                    session.getCheckedInAt());
            SeatDisplayStatus shown = session != null ? SeatDisplayStatus.IN_USE
                    : seat.getStatus() == SeatState.BLOCKED ? SeatDisplayStatus.BLOCKED : SeatDisplayStatus.AVAILABLE;
            return new SeatView(seat.getId().toString(), ApiVersion.fromDatabase(seat.getVersion()), Long.toString(seat.getBranchId()),
                    seat.getZone(), seat.getSeatNo(), seat.getKind().label(), shown.label(), current, seat.getCreatedAt());
        }).toList();
    }

    /** inUse ÷ total × 100 to one decimal, half up; null when there is no seat to divide by. */
    static BigDecimal rate(long inUse, long total) {
        if (total == 0) return null;
        return BigDecimal.valueOf(inUse).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> snapshot(Seat seat) {
        var values = new LinkedHashMap<String, Object>();
        values.put("zone", seat.getZone());
        values.put("seatNo", seat.getSeatNo());
        values.put("kind", seat.getKind().label());
        values.put("status", seat.getStatus().label());
        return values;
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        if (before.equals(after)) return;
        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static ApiException duplicate() {
        return ApiException.conflict("DUPLICATE_SEAT_NO", "같은 지점에 이미 있는 좌석 번호입니다.");
    }

    private static String reload(long id) { return "/api/seats/" + id; }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
