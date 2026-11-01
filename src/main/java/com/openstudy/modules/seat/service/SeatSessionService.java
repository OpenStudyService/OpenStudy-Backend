package com.openstudy.modules.seat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.seat.dto.response.ActorSummary;
import com.openstudy.modules.seat.dto.response.SeatMove;
import com.openstudy.modules.seat.dto.response.SeatRef;
import com.openstudy.modules.seat.dto.response.SeatSessionPass;
import com.openstudy.modules.seat.dto.response.SeatSessionView;
import com.openstudy.modules.seat.entity.Seat;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.entity.SeatState;
import com.openstudy.modules.seat.repository.SeatRepository;
import com.openstudy.modules.seat.repository.SeatSessionMoveRepository;
import com.openstudy.modules.seat.repository.SeatSessionRepository;
import com.openstudy.modules.ticket.dto.PassSettlement;
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
    private static final String ENTITY = "seat-sessions";
    private final SeatSessionRepository sessions;
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
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "CHECKED_OUT", reason,
                    json.writeValueAsString(Map.of("status", SeatSession.Status.ACTIVE.name())), json.writeValueAsString(after), requestId));
            outbox.invalidate(branch, "seats", session.getSeatId());
            outbox.invalidate(branch, ENTITY, id);
            return complete(actor, CHECK_OUT, scopeKey, key, fingerprint, 200, view(session));
        });
    }

    /** 404 hides sessions of branches outside the scope; the customer's lookup scope plays no part (M5-D06). */
    @Transactional(readOnly = true)
    public SeatSessionView get(long actor, long id, String scope) {
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        return sessions.findById(id).filter(session -> selected.contains(session.getBranchId())).map(this::view)
                .orElseThrow(SeatSessionService::notFound);
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
        var pass = passes.sessionPass(session.getPassId());
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
        // Minutes are not stored on their own: on a time pass every used minute is either deducted or overage.
        long deductedMinutes = pass.deductsMinutes() ? session.getBilledMinutes() - session.getOverageMinutes() : 0;
        return new SeatSessionView(Long.toString(id), ApiVersion.fromDatabase(session.getVersion()), session.getStatus().label(),
                Long.toString(session.getBranchId()), refs.get(session.getSeatId()), customers.summaries(List.of(customerId)).get(customerId),
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
