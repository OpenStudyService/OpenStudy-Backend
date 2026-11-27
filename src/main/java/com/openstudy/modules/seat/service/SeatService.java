package com.openstudy.modules.seat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final int MAX_ZONE_LENGTH = 80;
    private static final int MAX_SEAT_NO_LENGTH = 30;
    private static final int MAX_LABEL_LENGTH = 20;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final int RATE_SCALE = 1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final SeatRepository seatRepository;
    private final SeatSessionRepository seatSessionRepository;
    private final SeatSearchRepository seatSearchRepository;
    private final CustomerService customerService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<SeatView> list(long actor, String scope, ListQuery query, SeatFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        SeatSearchRepository.Filter filter = new SeatSearchRepository.Filter(
                selected,
                filters.zone(),
                filters.kind(),
                filters.status()
        );
        SeatSearchRepository.Page page = seatSearchRepository.find(filter, query.offset(), query.pageSize());

        List<SeatView> items = toViews(page.rows());

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public SeatView get(long actor, long id, String scope) {
        Seat seat = findVisibleSeat(authorizationService.current(actor), id, scope);

        return toViews(List.of(seat)).get(0);
    }

    /** One item per branch in scope. A branch without seats still appears, with zeros and a null rate. */
    @Transactional(readOnly = true)
    public SeatOccupancy occupancy(long actor, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        Instant asOf = clock.instant();
        Map<Long, SeatSearchRepository.BranchCounts> found = new HashMap<>();
        seatSearchRepository.countByBranch(new SeatSearchRepository.Filter(selected, null, null, null))
                .forEach(row -> found.put(row.branchId(), row));
        List<SeatCounts> items = new ArrayList<>();

        for (long branch : new TreeSet<>(selected)) {
            SeatSearchRepository.BranchCounts empty = new SeatSearchRepository.BranchCounts(branch, 0, 0, 0, 0);
            SeatSearchRepository.BranchCounts row = found.getOrDefault(branch, empty);
            items.add(new SeatCounts(
                    Long.toString(branch),
                    row.total(),
                    row.inUse(),
                    row.available(),
                    row.blocked(),
                    toRate(row.inUse(), row.total()),
                    asOf
            ));
        }

        return new SeatOccupancy(items);
    }

    /**
     * Every seat of one branch grouped by zone. The counts are tallied from the very seats returned, so the board and its
     * numbers always agree; a kind filter narrows both. A branch the caller cannot read is 404, like a seat outside scope.
     */
    @Transactional(readOnly = true)
    public SeatBoard board(long actor, String branchId, String kind) {
        if (branchId == null) {
            throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        }

        long branch = SeatInputs.parseId(branchId, "branchId");
        SeatKind kindFilter = kind == null ? null : SeatInputs.parseKind(kind, "kind");
        AuthorizationState state = authorizationService.current(actor);

        if (!authorizationService.permits(state, branch, BranchAction.READ)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Instant asOf = clock.instant();
        SeatSearchRepository.Filter filter = new SeatSearchRepository.Filter(Set.of(branch), null, kindFilter, null);
        List<SeatView> seats = toViews(seatSearchRepository.findAll(filter));
        long inUse = countByStatus(seats, SeatDisplayStatus.IN_USE);
        long blocked = countByStatus(seats, SeatDisplayStatus.BLOCKED);
        Map<String, List<SeatView>> zones = new LinkedHashMap<>();
        seats.forEach(seat -> zones.computeIfAbsent(seat.zone(), zone -> new ArrayList<>()).add(seat));
        SeatCounts counts = new SeatCounts(
                Long.toString(branch),
                seats.size(),
                inUse,
                seats.size() - inUse - blocked,
                blocked,
                toRate(inUse, seats.size()),
                asOf
        );
        List<SeatZone> seatZones = zones.entrySet()
                .stream()
                .map(zone -> new SeatZone(zone.getKey(), zone.getValue()))
                .toList();

        return new SeatBoard(counts, seatZones);
    }

    public SeatView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "zone", "seatNo", "kind", "status");
        long branch = input.id("branchId");
        String zone = input.text("zone", MAX_ZONE_LENGTH);
        String seatNo = input.text("seatNo", MAX_SEAT_NO_LENGTH);
        SeatKind kind = SeatInputs.parseKind(input.text("kind", MAX_LABEL_LENGTH), "kind");
        SeatState stored = input.has("status")
                ? SeatInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status")
                : SeatState.AVAILABLE;
        requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(branch, zone, seatNo, kind.name(), stored.name());
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.WRITE_SEATS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    }

                    return objectMapper.readValue(prior.get().getResponseJson(), SeatView.class);
                }

                idempotencyStore.discard(prior.get());
            }

            if (seatRepository.existsByBranchIdAndSeatNo(branch, seatNo)) {
                throw createDuplicate();
            }

            Seat seat = seatRepository.saveAndFlush(new Seat(branch, zone, seatNo, kind, stored));
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, seat.getId(), "CREATED", null, null,
                    objectMapper.writeValueAsString(toSnapshot(seat)), requestId));
            SeatView result = toViews(List.of(seat)).get(0);
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, scopeKey, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    public SeatView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        boolean hasImmutableField = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (hasImmutableField) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD",
                    "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        }

        StrictInput input = new StrictInput(body).only("version", "zone", "seatNo", "kind");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String zone = input.has("zone") ? input.text("zone", MAX_ZONE_LENGTH) : null;
        String seatNo = input.has("seatNo") ? input.text("seatNo", MAX_SEAT_NO_LENGTH) : null;
        SeatKind kind = input.has("kind") ? SeatInputs.parseKind(input.text("kind", MAX_LABEL_LENGTH), "kind") : null;
        long branch = findVisibleSeat(authorizationService.current(actor), id, scope).getBranchId();

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_SEATS,
                () -> seatRepository.lock(id),
                state -> {
                    Seat seat = findLockedSeat(id);
                    ApiVersion.requireCurrent(version, seat.getVersion(), toReloadPath(id));
                    String newZone = zone == null ? seat.getZone() : zone;
                    String newSeatNo = seatNo == null ? seat.getSeatNo() : seatNo;
                    SeatKind newKind = kind == null ? seat.getKind() : kind;
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    putIfChanged("zone", seat.getZone(), newZone, before, after);
                    putIfChanged("seatNo", seat.getSeatNo(), newSeatNo, before, after);
                    putIfChanged("kind", seat.getKind().label(), newKind.label(), before, after);

                    // An identical request is not a change: no version bump, audit row or event.
                    if (before.isEmpty()) {
                        return toViews(List.of(seat)).get(0);
                    }

                    boolean seatNoTaken = !newSeatNo.equals(seat.getSeatNo())
                            && seatRepository.existsByBranchIdAndSeatNoAndIdNot(branch, newSeatNo, id);

                    if (seatNoTaken) {
                        throw createDuplicate();
                    }

                    seat.change(newZone, newSeatNo, newKind);
                    seatRepository.flush();
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null,
                            objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after), requestId));

                    return toViews(List.of(seat)).get(0);
                }
        );
    }

    public SeatView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        SeatState next = SeatInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status");
        String reason = input.text("reason", MAX_REASON_LENGTH);
        long branch = findVisibleSeat(authorizationService.current(actor), id, scope).getBranchId();

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_SEATS,
                () -> seatRepository.lock(id),
                state -> {
                    Seat seat = findLockedSeat(id);
                    ApiVersion.requireCurrent(version, seat.getVersion(), toReloadPath(id));
                    SeatState previous = seat.getStatus();

                    // The same state again is not a change: the current value comes back with no audit row or event.
                    if (previous == next) {
                        return toViews(List.of(seat)).get(0);
                    }

                    boolean blocksOccupiedSeat = next == SeatState.BLOCKED
                            && seatSessionRepository.existsBySeatIdAndStatus(id, SeatSession.Status.ACTIVE);

                    if (blocksOccupiedSeat) {
                        throw ApiException.conflict("SEAT_OCCUPIED", "이용 중인 좌석은 운영 중단할 수 없습니다.");
                    }

                    seat.changeStatus(next);
                    seatRepository.flush();
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                            objectMapper.writeValueAsString(Map.of("status", previous.label())),
                            objectMapper.writeValueAsString(Map.of("status", next.label())), requestId));

                    return toViews(List.of(seat)).get(0);
                }
        );
    }

    /** inUse ÷ total × 100 to one decimal, half up; null when there is no seat to divide by. */
    static BigDecimal toRate(long inUse, long total) {
        if (total == 0) {
            return null;
        }

        return BigDecimal.valueOf(inUse)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 404 hides seats outside the reader's scope, so IDs are not an existence oracle. */
    private Seat findVisibleSeat(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        return seatRepository.findById(id)
                .filter(seat -> selected.contains(seat.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Seat findLockedSeat(long id) {
        return seatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Builds views for seats the caller may already see: active sessions and their customers are read in bulk. */
    private List<SeatView> toViews(List<Seat> seats) {
        if (seats.isEmpty()) {
            return List.of();
        }

        List<Long> seatIds = seats.stream()
                .map(Seat::getId)
                .toList();
        Map<Long, SeatSession> active = new HashMap<>();
        seatSessionRepository.findByStatusAndSeatIdIn(SeatSession.Status.ACTIVE, seatIds)
                .forEach(session -> active.put(session.getSeatId(), session));
        Map<Long, CustomerSummary> people = active.isEmpty()
                ? Map.of()
                : customerService.summaries(active.values().stream().map(SeatSession::getCustomerId).toList());

        return seats.stream()
                .map(seat -> toView(seat, active.get(seat.getId()), people))
                .toList();
    }

    private static SeatView toView(Seat seat, SeatSession session, Map<Long, CustomerSummary> people) {
        ActiveSessionView current = session == null ? null : toActiveSessionView(session, people);
        SeatDisplayStatus shown;

        if (session != null) {
            shown = SeatDisplayStatus.IN_USE;
        } else if (seat.getStatus() == SeatState.BLOCKED) {
            shown = SeatDisplayStatus.BLOCKED;
        } else {
            shown = SeatDisplayStatus.AVAILABLE;
        }

        return new SeatView(
                seat.getId().toString(),
                ApiVersion.fromDatabase(seat.getVersion()),
                Long.toString(seat.getBranchId()),
                seat.getZone(),
                seat.getSeatNo(),
                seat.getKind().label(),
                shown.label(),
                current,
                seat.getCreatedAt()
        );
    }

    private static ActiveSessionView toActiveSessionView(SeatSession session, Map<Long, CustomerSummary> people) {
        CustomerSummary unknown = new CustomerSummary(Long.toString(session.getCustomerId()), "", "");
        CustomerSummary customer = people.getOrDefault(session.getCustomerId(), unknown);

        return new ActiveSessionView(session.getId().toString(), customer, session.getCheckedInAt());
    }

    private static long countByStatus(List<SeatView> seats, SeatDisplayStatus status) {
        return seats.stream()
                .filter(seat -> status.label().equals(seat.status()))
                .count();
    }

    private static Map<String, Object> toSnapshot(Seat seat) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("zone", seat.getZone());
        values.put("seatNo", seat.getSeatNo());
        values.put("kind", seat.getKind().label());
        values.put("status", seat.getStatus().label());

        return values;
    }

    private static void putIfChanged(
            String field,
            Object before,
            Object after,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues
    ) {
        if (before.equals(after)) {
            return;
        }

        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static ApiException createDuplicate() {
        return ApiException.conflict("DUPLICATE_SEAT_NO", "같은 지점에 이미 있는 좌석 번호입니다.");
    }

    private static String toReloadPath(long id) {
        return "/api/seats/" + id;
    }

    private static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
