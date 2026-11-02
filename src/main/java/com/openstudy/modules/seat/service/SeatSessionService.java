package com.openstudy.modules.seat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.seat.dto.response.ActorSummary;
import com.openstudy.modules.seat.dto.response.SeatMove;
import com.openstudy.modules.seat.dto.response.SeatRef;
import com.openstudy.modules.seat.dto.response.SeatSessionPass;
import com.openstudy.modules.seat.dto.response.SeatSessionView;
import com.openstudy.modules.seat.dto.response.SeatUsage;
import com.openstudy.modules.seat.entity.Seat;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.entity.SeatSessionMove;
import com.openstudy.modules.seat.entity.SeatState;
import com.openstudy.modules.seat.repository.SeatRepository;
import com.openstudy.modules.seat.repository.SeatSessionMoveRepository;
import com.openstudy.modules.seat.repository.SeatSessionRepository;
import com.openstudy.modules.seat.repository.SeatSessionSearchRepository;
import com.openstudy.modules.ticket.dto.PassSettlement;
import com.openstudy.modules.ticket.dto.SessionPass;
import com.openstudy.modules.ticket.service.PassService;

/**
 * Staff check-in and check-out (FR-SEAT-06/07/08/14, M5-D01~D03·D06). Any valid member of the seat's branch may handle
 * them; the customer's lookup scope is deliberately not consulted. Every check of the contract tables runs after the
 * locks are held, in the order they list, and a refused command leaves nothing behind. Deductions are computed only
 * from the server clock and the pass row: no request field carries minutes or amounts.
 */
@Service
@RequiredArgsConstructor
public class SeatSessionService {
    private static final String CHECK_IN = "seat-sessions:check-in";
    private static final String CHECK_OUT = "seat-sessions:check-out";
    private static final String MOVE = "seat-sessions:move";
    private static final String ENTITY = "seat-sessions";
    private static final String CHECKED_OUT = "CHECKED_OUT";
    private final SeatSessionRepository sessions;
    private final SeatSessionSearchRepository search;
    private final SeatSessionMoveRepository moves;
    private final SeatRepository seats;
    private final CustomerService customers;
    private final PassService passes;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    public SeatSessionView checkIn(long actor, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("seatId", "customerId", "passId", "reason");
        long seatId = input.id("seatId");
        long customerId = input.id("customerId");
        long passId = input.id("passId");
        String reason = SeatInputs.reason(body);
        validKey(key);
        String scopeKey = Long.toString(seatId);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(seatId, customerId, passId, reason)));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> seats.branchOf(seatId), (state, branch) -> {
            var replayed = replay(actor, CHECK_IN, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var seat = seats.locked(seatId).orElseThrow(SeatSessionService::notFound);
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 입실할 수 없습니다.");
            if (seat.getStatus() == SeatState.BLOCKED) throw ApiException.conflict("SEAT_BLOCKED", "운영 중단된 좌석입니다.");
            if (sessions.existsBySeatIdAndStatus(seatId, SeatSession.Status.ACTIVE))
                throw ApiException.conflict("SEAT_OCCUPIED", "이미 사용 중인 좌석입니다.");
            customers.lockForCheckIn(customerId);
            passes.lockOwned(passId, customerId);
            customers.requireCheckInAllowed(customerId);
            if (sessions.existsByCustomerIdAndStatus(customerId, SeatSession.Status.ACTIVE))
                throw ApiException.conflict("CUSTOMER_ALREADY_CHECKED_IN", "이미 입실 중인 고객입니다.");
            // The processing instant: read after every lock, so a pass expiring during the wait is refused.
            Instant now = now();
            passes.requireUsable(passId, now);
            String startKey = storedKey("SIN-", actor, CHECK_IN, scopeKey, key);
            var session = SeatSessionConflicts.translating(() -> sessions.saveAndFlush(
                    SeatSession.start(customerId, passId, branch, seatId, now, actor, reason, startKey)));
            long id = session.getId();
            var after = new LinkedHashMap<String, Object>();
            after.put("seatId", Long.toString(seatId));
            after.put("customerId", Long.toString(customerId));
            after.put("passId", Long.toString(passId));
            after.put("checkedInAt", now.toString());
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "CHECKED_IN", reason, null, json.writeValueAsString(after), requestId));
            outbox.invalidate(branch, "seats", seatId);
            outbox.invalidate(branch, ENTITY, id);
            return complete(actor, CHECK_IN, scopeKey, key, fingerprint, 201, view(session));
        });
    }

    public SeatSessionView checkOut(long actor, long id, String key, Map<String, Object> body, String requestId) {
        new StrictInput(body).only("reason");
        String reason = SeatInputs.reason(body);
        validKey(key);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(id, reason)));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> sessions.branchOf(id), (state, branch) -> {
            var replayed = replay(actor, CHECK_OUT, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var session = sessions.locked(id).orElseThrow(SeatSessionService::notFound);
            if (session.getStatus() == SeatSession.Status.CLOSED)
                throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 퇴실 처리된 이용 기록입니다.");
            Instant in = session.getEffectiveCheckedInAt();
            Instant now = now();
            // A clock stepped back must not become a negative stay: ck_seat_sessions_2 forbids it and closing always works.
            Instant out = now.isBefore(in) ? in : now;
            PassSettlement settled = passes.settle(actor, session.getPassId(), id, in, out, reason);
            session.close(out, settled.usedMinutes(), settled.deductedKrw(), settled.overageMinutes(), settled.overageKrw(),
                    storedKey("SOUT-", actor, CHECK_OUT, scopeKey, key));
            sessions.saveAndFlush(session);
            var after = new LinkedHashMap<String, Object>();
            after.put("status", session.getStatus().name());
            after.put("checkedOutAt", out.toString());
            after.put("passId", Long.toString(session.getPassId()));
            after.put("usedMinutes", settled.usedMinutes());
            after.put("deductedMinutes", settled.deductedMinutes());
            after.put("deductedKrw", settled.deductedKrw());
            after.put("overageMinutes", settled.overageMinutes());
            after.put("overageKrw", settled.overageKrw());
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, CHECKED_OUT, reason,
                    json.writeValueAsString(Map.of("status", SeatSession.Status.ACTIVE.name())), json.writeValueAsString(after), requestId));
            outbox.invalidate(branch, "seats", session.getSeatId());
            outbox.invalidate(branch, ENTITY, id);
            return complete(actor, CHECK_OUT, scopeKey, key, fingerprint, 200, view(session));
        });
    }

    /**
     * Moves a running stay to another seat of the same branch (FR-SEAT-17). Lock order follows the contract: the
     * idempotency record, the session, then the target seat; no second seat is ever locked, so two moves cannot wait on
     * each other. Only seat_id changes, so the check-in time, the pass and therefore the deduction stay as they were.
     */
    public SeatSessionView move(long actor, long id, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("toSeatId", "reason");
        long toSeatId = input.id("toSeatId");
        String reason = SeatInputs.reason(body);
        validKey(key);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(id, toSeatId, reason)));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> sessions.branchOf(id), (state, branch) -> {
            // Seeing the session is not enough: moving it needs operations:write there (403, after the 404 above).
            policy.require(state, Set.of(branch), BranchAction.WRITE_SEATS);
            var replayed = replay(actor, MOVE, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var session = sessions.locked(id).orElseThrow(SeatSessionService::notFound);
            if (session.getStatus() == SeatSession.Status.CLOSED)
                throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 퇴실 처리된 이용 기록입니다.");
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 좌석을 이동할 수 없습니다.");
            long fromSeatId = session.getSeatId();
            if (toSeatId == fromSeatId) throw wrongSeat();
            var target = seats.locked(toSeatId).orElseThrow(SeatSessionService::wrongSeat);
            if (target.getBranchId() != branch) throw wrongSeat();
            if (target.getStatus() == SeatState.BLOCKED) throw ApiException.conflict("SEAT_BLOCKED", "운영 중단된 좌석입니다.");
            if (sessions.existsBySeatIdAndStatus(toSeatId, SeatSession.Status.ACTIVE))
                throw ApiException.conflict("SEAT_OCCUPIED", "이미 사용 중인 좌석입니다.");
            Instant now = now();
            String moveKey = storedKey("SMV-", actor, MOVE, scopeKey, key);
            session.moveTo(toSeatId);
            SeatSessionConflicts.translating(() -> sessions.saveAndFlush(session));
            SeatSessionConflicts.translating(() -> moves.saveAndFlush(
                    SeatSessionMove.of(id, fromSeatId, toSeatId, actor, reason, moveKey, now)));
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "SEAT_MOVED", reason,
                    json.writeValueAsString(Map.of("seatId", Long.toString(fromSeatId))),
                    json.writeValueAsString(Map.of("seatId", Long.toString(toSeatId))), requestId));
            outbox.invalidate(branch, "seats", fromSeatId);
            outbox.invalidate(branch, "seats", toSeatId);
            outbox.invalidate(branch, ENTITY, id);
            return complete(actor, MOVE, scopeKey, key, fingerprint, 200, view(session));
        });
    }

    /** The target is picked among the branch's own seats: a missing, foreign or current seat is a bad input, not a 404. */
    private static ApiException wrongSeat() {
        return ApiException.validation("toSeatId", "이동할 수 있는 같은 지점의 다른 좌석을 선택해 주세요.");
    }

    /** 404 hides sessions of branches outside the scope; the customer's lookup scope plays no part (M5-D06). */
    @Transactional(readOnly = true)
    public SeatSessionView get(long actor, long id, String scope) {
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        return sessions.findById(id).filter(session -> selected.contains(session.getBranchId())).map(this::view)
                .orElseThrow(SeatSessionService::notFound);
    }

    /**
     * The stays of the branches in scope (FR-SEAT-18). The filters only narrow those rows: a customerId outside the
     * reader's customer lookup scope is not an error and reveals nothing beyond the branch's own stays (M5-D06).
     */
    @Transactional(readOnly = true)
    public PageResponse<SeatSessionView> list(long actor, String scope, ListQuery query, String status, String from, String to,
            String seatId, String customerId) {
        var filterStatus = status == null ? null : SeatInputs.sessionStatus(status);
        var range = TimeRange.parse(from, to);
        Long seat = seatId == null ? null : Long.valueOf(SeatInputs.id(seatId, "seatId"));
        Long customer = customerId == null ? null : Long.valueOf(SeatInputs.id(customerId, "customerId"));
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        var page = search.find(new SeatSessionSearchRepository.Filter(selected, filterStatus, range, seat, customer),
                query.direction() == ListQuery.Direction.DESC, query.offset(), query.pageSize());
        return new PageResponse<>(items(page.rows()), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /**
     * The closed stays of these passes, wherever they happened (M5-D01). Handler and reason live only in the check-out
     * audit row; the caller has authorized the passes, not the branches, and must withhold them for branches it may not show.
     */
    @Transactional(readOnly = true)
    public List<SeatUsage> usage(Collection<Long> passIds) {
        if (passIds.isEmpty()) return List.of();
        var closed = sessions.findByPassIdInAndStatusOrderByIdAsc(passIds, SeatSession.Status.CLOSED);
        Map<Long, AuditEvent> checkOuts = new HashMap<>();
        audit.recorded(ENTITY, CHECKED_OUT, closed.stream().map(SeatSession::getId).toList())
                .forEach(event -> checkOuts.put(event.getEntityId(), event));
        return closed.stream().map(session -> {
            var checkOut = checkOuts.get(session.getId());
            return new SeatUsage(session.getId(), session.getPassId(), session.getBranchId(), session.getCheckedOutAt(),
                    session.getBilledMinutes(), checkOut == null ? null : checkOut.getActorEmployeeId(),
                    checkOut == null ? null : checkOut.getReason());
        }).toList();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<SeatSessionView> replay(long actor, String operation, String scopeKey, String key, byte[] fingerprint) {
        var prior = requests.lock(actor, operation, scopeKey, key);
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), SeatSessionView.class));
    }

    private SeatSessionView complete(long actor, String operation, String scopeKey, String key, byte[] fingerprint, int status,
            SeatSessionView result) {
        requests.complete(actor, operation, scopeKey, key, fingerprint, status, json.writeValueAsString(result),
                clock.instant().plus(Duration.ofHours(24)));
        return result;
    }

    private SeatSessionView view(SeatSession session) {
        long id = session.getId();
        var history = moves.findBySeatSessionIdOrderByMovedAtAscIdAsc(id);
        Set<Long> seatIds = new HashSet<>(Set.of(session.getSeatId()));
        Set<Long> actors = new HashSet<>();
        if (session.getExceptionActorId() != null) actors.add(session.getExceptionActorId());
        for (var move : history) {
            seatIds.add(move.getFromSeatId());
            seatIds.add(move.getToSeatId());
            if (move.getActorEmployeeId() != null) actors.add(move.getActorEmployeeId());
        }
        Map<Long, SeatRef> refs = new HashMap<>();
        seats.findAllById(seatIds).forEach(seat -> refs.put(seat.getId(), ref(seat)));
        Map<Long, String> names = actors.isEmpty() ? Map.of() : employees.names(actors);
        var moveViews = history.stream().map(move -> new SeatMove(refs.get(move.getFromSeatId()), refs.get(move.getToSeatId()),
                move.getMovedAt(), actor(move.getActorEmployeeId(), names), move.getReason())).toList();
        long customerId = session.getCustomerId();
        return view(session, passes.sessionPass(session.getPassId()), refs, customers.summaries(List.of(customerId)).get(customerId),
                names, moveViews);
    }

    /** A list item is a detail without its moves (the contract's empty array); the page is read in batches. */
    private List<SeatSessionView> items(List<SeatSession> rows) {
        if (rows.isEmpty()) return List.of();
        var ownedPasses = passes.sessionPasses(rows.stream().map(SeatSession::getPassId).toList());
        var summaries = customers.summaries(rows.stream().map(SeatSession::getCustomerId).toList());
        Map<Long, SeatRef> refs = new HashMap<>();
        seats.findAllById(rows.stream().map(SeatSession::getSeatId).collect(Collectors.toSet()))
                .forEach(seat -> refs.put(seat.getId(), ref(seat)));
        Map<Long, String> names = employees.names(rows.stream().map(SeatSession::getExceptionActorId).filter(Objects::nonNull).toList());
        return rows.stream().map(session -> view(session, ownedPasses.get(session.getPassId()), refs,
                summaries.get(session.getCustomerId()), names, List.of())).toList();
    }

    private static SeatSessionView view(SeatSession session, SessionPass pass, Map<Long, SeatRef> refs, CustomerSummary customer,
            Map<Long, String> names, List<SeatMove> moveViews) {
        // Minutes are not stored on their own: on a time pass every used minute is either deducted or overage.
        long deductedMinutes = pass.deductsMinutes() ? session.getBilledMinutes() - session.getOverageMinutes() : 0;
        return new SeatSessionView(Long.toString(session.getId()), ApiVersion.fromDatabase(session.getVersion()), session.getStatus().label(),
                Long.toString(session.getBranchId()), refs.get(session.getSeatId()), customer,
                new SeatSessionPass(pass.id(), pass.kind(), pass.productName(), pass.purchaseBranchId()), session.getCheckedInAt(),
                session.getCheckedOutAt(), session.getBilledMinutes(), deductedMinutes, session.getBilledKrw(),
                session.getOverageMinutes(), session.getOverageKrw(), pass.remainingMinutes(), pass.remainingCreditKrw(),
                actor(session.getExceptionActorId(), names), session.getExceptionReason(), moveViews);
    }

    private static SeatRef ref(Seat seat) { return new SeatRef(seat.getId().toString(), seat.getZone(), seat.getSeatNo()); }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    /** DATETIME(6) keeps microseconds, so the response shows exactly what is stored. */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    /** The request-key columns are globally unique and 120 characters, so they hold a hash (contract "멱등 키"). */
    private static String storedKey(String prefix, long actor, String operation, String scopeKey, String key) {
        return prefix + HexFormat.of().formatHex(digest(actor + "\n" + operation + "\n" + scopeKey + "\n" + key));
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
