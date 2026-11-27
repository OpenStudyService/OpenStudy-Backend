package com.openstudy.modules.seat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import org.springframework.data.domain.Limit;
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
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.seat.dto.response.ActorSummary;
import com.openstudy.modules.seat.dto.response.CustomerSeatUsage;
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
    private static final String ACTIVE_BRANCH = "ACTIVE";
    private static final String CHECK_IN_KEY_PREFIX = "SIN-";
    private static final String CHECK_OUT_KEY_PREFIX = "SOUT-";
    private static final String MOVE_KEY_PREFIX = "SMV-";
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final SeatSessionRepository seatSessionRepository;
    private final SeatSessionSearchRepository seatSessionSearchRepository;
    private final SeatSessionMoveRepository seatSessionMoveRepository;
    private final SeatRepository seatRepository;
    private final CustomerService customerService;
    private final PassService passService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public SeatSessionView checkIn(long actor, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("seatId", "customerId", "passId", "reason");
        long seatId = input.id("seatId");
        long customerId = input.id("customerId");
        long passId = input.id("passId");
        String reason = SeatInputs.parseReason(body);
        requireValidKey(key);
        String scopeKey = Long.toString(seatId);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(seatId, customerId, passId, reason)));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> seatRepository.findBranch(seatId),
                (state, branch) -> {
                    Optional<SeatSessionView> replayed = replay(actor, CHECK_IN, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Seat seat = seatRepository.findLocked(seatId).orElseThrow(SeatSessionService::createNotFound);

                    if (!ACTIVE_BRANCH.equals(state.branches().get(branch))) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 입실할 수 없습니다.");
                    }

                    if (seat.getStatus() == SeatState.BLOCKED) {
                        throw ApiException.conflict("SEAT_BLOCKED", "운영 중단된 좌석입니다.");
                    }

                    if (seatSessionRepository.existsBySeatIdAndStatus(seatId, SeatSession.Status.ACTIVE)) {
                        throw ApiException.conflict("SEAT_OCCUPIED", "이미 사용 중인 좌석입니다.");
                    }

                    customerService.lockForCheckIn(customerId);
                    passService.lockOwned(passId, customerId);
                    customerService.requireCheckInAllowed(customerId);

                    if (seatSessionRepository.existsByCustomerIdAndStatus(customerId, SeatSession.Status.ACTIVE)) {
                        throw ApiException.conflict("CUSTOMER_ALREADY_CHECKED_IN", "이미 입실 중인 고객입니다.");
                    }

                    // The processing instant: read after every lock, so a pass expiring during the wait is refused.
                    Instant now = currentInstant();
                    passService.requireUsable(passId, now);
                    String startKey = toStoredKey(CHECK_IN_KEY_PREFIX, actor, CHECK_IN, scopeKey, key);
                    SeatSession newSession = SeatSession.start(customerId, passId, branch, seatId, now, actor, reason,
                            startKey);
                    SeatSession session = SeatSessionConflicts.translating(
                            () -> seatSessionRepository.saveAndFlush(newSession)
                    );
                    long id = session.getId();

                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("seatId", Long.toString(seatId));
                    after.put("customerId", Long.toString(customerId));
                    after.put("passId", Long.toString(passId));
                    after.put("checkedInAt", now.toString());
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "CHECKED_IN", reason, null,
                            objectMapper.writeValueAsString(after), requestId));

                    return completeRequest(actor, CHECK_IN, scopeKey, key, fingerprint, CREATED,
                            toCommandView(session));
                }
        );
    }

    public SeatSessionView checkOut(long actor, long id, String key, Map<String, Object> body, String requestId) {
        new StrictInput(body).only("reason");
        String reason = SeatInputs.parseReason(body);
        requireValidKey(key);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(id, reason)));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> seatSessionRepository.findBranch(id),
                (state, branch) -> {
                    Optional<SeatSessionView> replayed = replay(actor, CHECK_OUT, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    SeatSession session = seatSessionRepository.findLocked(id)
                            .orElseThrow(SeatSessionService::createNotFound);

                    if (session.getStatus() == SeatSession.Status.CLOSED) {
                        throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 퇴실 처리된 이용 기록입니다.");
                    }

                    Instant in = session.getEffectiveCheckedInAt();
                    Instant now = currentInstant();
                    // A clock stepped back must not become a negative stay: ck_seat_sessions_2 forbids it and closing
                    // always works.
                    Instant out = now.isBefore(in) ? in : now;
                    PassSettlement settled = passService.settle(actor, session.getPassId(), id, in, out, reason);
                    String endKey = toStoredKey(CHECK_OUT_KEY_PREFIX, actor, CHECK_OUT, scopeKey, key);
                    session.close(
                            out,
                            settled.usedMinutes(),
                            settled.deductedKrw(),
                            settled.overageMinutes(),
                            settled.overageKrw(),
                            endKey
                    );
                    seatSessionRepository.saveAndFlush(session);

                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("status", session.getStatus().name());
                    after.put("checkedOutAt", out.toString());
                    after.put("passId", Long.toString(session.getPassId()));
                    after.put("usedMinutes", settled.usedMinutes());
                    after.put("deductedMinutes", settled.deductedMinutes());
                    after.put("deductedKrw", settled.deductedKrw());
                    after.put("overageMinutes", settled.overageMinutes());
                    after.put("overageKrw", settled.overageKrw());
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, CHECKED_OUT, reason,
                            objectMapper.writeValueAsString(Map.of("status", SeatSession.Status.ACTIVE.name())),
                            objectMapper.writeValueAsString(after), requestId));

                    return completeRequest(actor, CHECK_OUT, scopeKey, key, fingerprint, OK, toCommandView(session));
                }
        );
    }

    /**
     * Moves a running stay to another seat of the same branch (FR-SEAT-17). Lock order follows the contract: the
     * idempotency record, the session, then the target seat; no second seat is ever locked, so two moves cannot wait on
     * each other. Only seat_id changes, so the check-in time, the pass and therefore the deduction stay as they were.
     */
    public SeatSessionView move(long actor, long id, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("toSeatId", "reason");
        long toSeatId = input.id("toSeatId");
        String reason = SeatInputs.parseReason(body);
        requireValidKey(key);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(id, toSeatId, reason)));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> seatSessionRepository.findBranch(id),
                (state, branch) -> {
                    // Seeing the session is not enough: moving it needs operations:write there (403, after the 404 above).
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_SEATS);
                    Optional<SeatSessionView> replayed = replay(actor, MOVE, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    SeatSession session = seatSessionRepository.findLocked(id)
                            .orElseThrow(SeatSessionService::createNotFound);

                    if (session.getStatus() == SeatSession.Status.CLOSED) {
                        throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 퇴실 처리된 이용 기록입니다.");
                    }

                    if (!ACTIVE_BRANCH.equals(state.branches().get(branch))) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 좌석을 이동할 수 없습니다.");
                    }

                    long fromSeatId = session.getSeatId();

                    if (toSeatId == fromSeatId) {
                        throw createWrongSeat();
                    }

                    Seat target = seatRepository.findLocked(toSeatId).orElseThrow(SeatSessionService::createWrongSeat);

                    if (target.getBranchId() != branch) {
                        throw createWrongSeat();
                    }

                    if (target.getStatus() == SeatState.BLOCKED) {
                        throw ApiException.conflict("SEAT_BLOCKED", "운영 중단된 좌석입니다.");
                    }

                    if (seatSessionRepository.existsBySeatIdAndStatus(toSeatId, SeatSession.Status.ACTIVE)) {
                        throw ApiException.conflict("SEAT_OCCUPIED", "이미 사용 중인 좌석입니다.");
                    }

                    Instant now = currentInstant();
                    String moveKey = toStoredKey(MOVE_KEY_PREFIX, actor, MOVE, scopeKey, key);
                    session.moveTo(toSeatId);
                    SeatSessionConflicts.translating(() -> seatSessionRepository.saveAndFlush(session));
                    SeatSessionMove move = SeatSessionMove.of(id, fromSeatId, toSeatId, actor, reason, moveKey, now);
                    SeatSessionConflicts.translating(() -> seatSessionMoveRepository.saveAndFlush(move));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "SEAT_MOVED", reason,
                            objectMapper.writeValueAsString(Map.of("seatId", Long.toString(fromSeatId))),
                            objectMapper.writeValueAsString(Map.of("seatId", Long.toString(toSeatId))), requestId));

                    return completeRequest(actor, MOVE, scopeKey, key, fingerprint, OK, toCommandView(session));
                }
        );
    }

    /** 404 hides sessions of branches outside the scope; the customer's lookup scope plays no part (M5-D06). */
    @Transactional(readOnly = true)
    public SeatSessionView get(long actor, long id, String scope) {
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);

        return seatSessionRepository.findById(id)
                .filter(session -> selected.contains(session.getBranchId()))
                .map(this::toStoredView)
                .orElseThrow(SeatSessionService::createNotFound);
    }

    /**
     * The stays of the branches in scope (FR-SEAT-18). The filters only narrow those rows: a customerId outside the
     * reader's customer lookup scope is not an error and reveals nothing beyond the branch's own stays (M5-D06).
     */
    @Transactional(readOnly = true)
    public PageResponse<SeatSessionView> list(
            long actor,
            String scope,
            ListQuery query,
            String status,
            String from,
            String to,
            String seatId,
            String customerId
    ) {
        SeatSession.Status filterStatus = status == null ? null : SeatInputs.parseSessionStatus(status);
        TimeRange range = TimeRange.parse(from, to);
        Long seat = seatId == null ? null : Long.valueOf(SeatInputs.parseId(seatId, "seatId"));
        Long customer = customerId == null ? null : Long.valueOf(SeatInputs.parseId(customerId, "customerId"));
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);
        SeatSessionSearchRepository.Filter filter = new SeatSessionSearchRepository.Filter(
                selected,
                filterStatus,
                range,
                seat,
                customer
        );
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        SeatSessionSearchRepository.Page page = seatSessionSearchRepository.find(
                filter,
                descending,
                query.offset(),
                query.pageSize()
        );
        List<SeatSessionView> items = toListItems(page.rows());

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /**
     * The closed stays of these passes, wherever they happened (M5-D01). Handler and reason live only in the check-out
     * audit row; the caller has authorized the passes, not the branches, and must withhold them for branches it may not show.
     */
    @Transactional(readOnly = true)
    public List<SeatUsage> usage(Collection<Long> passIds) {
        if (passIds.isEmpty()) {
            return List.of();
        }

        List<SeatSession> closed = seatSessionRepository.findByPassIdInAndStatusOrderByIdAsc(
                passIds,
                SeatSession.Status.CLOSED
        );
        List<Long> sessionIds = closed.stream()
                .map(SeatSession::getId)
                .toList();
        Map<Long, AuditEvent> checkOuts = new HashMap<>();
        auditTrail.recorded(ENTITY, CHECKED_OUT, sessionIds)
                .forEach(event -> checkOuts.put(event.getEntityId(), event));

        return closed.stream()
                .map(session -> toUsage(session, checkOuts.get(session.getId())))
                .toList();
    }

    /** The customer's latest stays at any branch (M5-D01), reduced to place and time for the cross-branch summary (M6-D06). */
    @Transactional(readOnly = true)
    public List<CustomerSeatUsage> recentUsages(long customerId, int limit) {
        return seatSessionRepository.findByCustomerIdOrderByCheckedInAtDescIdDesc(customerId, Limit.of(limit))
                .stream()
                .map(session -> new CustomerSeatUsage(
                        session.getBranchId(),
                        session.getCheckedInAt(),
                        session.getCheckedOutAt()
                ))
                .toList();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<SeatSessionView> replay(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint
    ) {
        Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, operation, scopeKey, key);

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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), SeatSessionView.class));
    }

    private SeatSessionView completeRequest(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            int status,
            SeatSessionView result
    ) {
        String responseJson = objectMapper.writeValueAsString(result);
        Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
        idempotencyStore.complete(actor, operation, scopeKey, key, fingerprint, status, responseJson, expiresAt);

        return result;
    }

    /** A command's own result, which reports the balance it left behind. */
    private SeatSessionView toCommandView(SeatSession session) {
        return toDetail(session, true);
    }

    /**
     * A read of the record. The pass may belong to another branch (M5-D01), and that branch's balance is shown only
     * while the stay runs: a closed stay must not stay a way to follow the customer's current balance.
     */
    private SeatSessionView toStoredView(SeatSession session) {
        return toDetail(session, isBalanceVisible(session));
    }

    private static boolean isBalanceVisible(SeatSession session) {
        return session.getStatus() == SeatSession.Status.ACTIVE;
    }

    private SeatSessionView toDetail(SeatSession session, boolean balance) {
        long id = session.getId();
        List<SeatSessionMove> history = seatSessionMoveRepository.findBySeatSessionIdOrderByMovedAtAscIdAsc(id);
        Set<Long> seatIds = new HashSet<>(Set.of(session.getSeatId()));
        Set<Long> actors = new HashSet<>();

        if (session.getExceptionActorId() != null) {
            actors.add(session.getExceptionActorId());
        }

        for (SeatSessionMove move : history) {
            seatIds.add(move.getFromSeatId());
            seatIds.add(move.getToSeatId());

            if (move.getActorEmployeeId() != null) {
                actors.add(move.getActorEmployeeId());
            }
        }

        Map<Long, SeatRef> refs = new HashMap<>();
        seatRepository.findAllById(seatIds).forEach(seat -> refs.put(seat.getId(), toSeatRef(seat)));
        Map<Long, String> names = actors.isEmpty() ? Map.of() : employeeAccountService.names(actors);
        List<SeatMove> moveViews = history.stream()
                .map(move -> new SeatMove(
                        refs.get(move.getFromSeatId()),
                        refs.get(move.getToSeatId()),
                        move.getMovedAt(),
                        toActorSummary(move.getActorEmployeeId(), names),
                        move.getReason()
                ))
                .toList();
        long customerId = session.getCustomerId();
        SessionPass pass = passService.sessionPass(session.getPassId());
        CustomerSummary customer = customerService.summaries(List.of(customerId)).get(customerId);

        return toView(session, pass, refs, customer, names, moveViews, balance);
    }

    /** A list item is a detail without its moves (the contract's empty array); the page is read in batches. */
    private List<SeatSessionView> toListItems(List<SeatSession> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> passIds = rows.stream()
                .map(SeatSession::getPassId)
                .toList();
        Map<Long, SessionPass> ownedPasses = passService.sessionPasses(passIds);
        List<Long> customerIds = rows.stream()
                .map(SeatSession::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> summaries = customerService.summaries(customerIds);
        Set<Long> seatIds = rows.stream()
                .map(SeatSession::getSeatId)
                .collect(Collectors.toSet());
        Map<Long, SeatRef> refs = new HashMap<>();
        seatRepository.findAllById(seatIds).forEach(seat -> refs.put(seat.getId(), toSeatRef(seat)));
        List<Long> actorIds = rows.stream()
                .map(SeatSession::getExceptionActorId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);

        return rows.stream()
                .map(session -> toView(
                        session,
                        ownedPasses.get(session.getPassId()),
                        refs,
                        summaries.get(session.getCustomerId()),
                        names,
                        List.of(),
                        isBalanceVisible(session)
                ))
                .toList();
    }

    private static SeatSessionView toView(
            SeatSession session,
            SessionPass pass,
            Map<Long, SeatRef> refs,
            CustomerSummary customer,
            Map<Long, String> names,
            List<SeatMove> moveViews,
            boolean balance
    ) {
        // Minutes are not stored on their own: on a time pass every used minute is either deducted or overage.
        long deductedMinutes = pass.deductsMinutes() ? session.getBilledMinutes() - session.getOverageMinutes() : 0;
        SeatSessionPass sessionPass = new SeatSessionPass(
                pass.id(),
                pass.kind(),
                pass.productName(),
                pass.purchaseBranchId()
        );

        return new SeatSessionView(
                Long.toString(session.getId()),
                ApiVersion.fromDatabase(session.getVersion()),
                session.getStatus().label(),
                Long.toString(session.getBranchId()),
                refs.get(session.getSeatId()),
                customer,
                sessionPass,
                session.getCheckedInAt(),
                session.getCheckedOutAt(),
                session.getBilledMinutes(),
                deductedMinutes,
                session.getBilledKrw(),
                session.getOverageMinutes(),
                session.getOverageKrw(),
                balance ? pass.remainingMinutes() : null,
                balance ? pass.remainingCreditKrw() : null,
                toActorSummary(session.getExceptionActorId(), names),
                session.getExceptionReason(),
                moveViews
        );
    }

    private static SeatUsage toUsage(SeatSession session, AuditEvent checkOut) {
        return new SeatUsage(
                session.getId(),
                session.getPassId(),
                session.getBranchId(),
                session.getCheckedOutAt(),
                session.getBilledMinutes(),
                checkOut == null ? null : checkOut.getActorEmployeeId(),
                checkOut == null ? null : checkOut.getReason()
        );
    }

    private static SeatRef toSeatRef(Seat seat) {
        return new SeatRef(seat.getId().toString(), seat.getZone(), seat.getSeatNo());
    }

    private static ActorSummary toActorSummary(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    /** DATETIME(6) keeps microseconds, so the response shows exactly what is stored. */
    private Instant currentInstant() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ResponseStatusException createNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** The target is picked among the branch's own seats: a missing, foreign or current seat is a bad input, not a 404. */
    private static ApiException createWrongSeat() {
        return ApiException.validation("toSeatId", "이동할 수 있는 같은 지점의 다른 좌석을 선택해 주세요.");
    }

    /** The request-key columns are globally unique and 120 characters, so they hold a hash (contract "멱등 키"). */
    private static String toStoredKey(String prefix, long actor, String operation, String scopeKey, String key) {
        String source = actor + "\n" + operation + "\n" + scopeKey + "\n" + key;

        return prefix + HexFormat.of().formatHex(digest(source));
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
