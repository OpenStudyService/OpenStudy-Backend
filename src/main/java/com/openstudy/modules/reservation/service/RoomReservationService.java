package com.openstudy.modules.reservation.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
import com.openstudy.common.exception.OperationsConflict;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.branch.service.BranchClosureService;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.reservation.dto.response.ActorSummary;
import com.openstudy.modules.reservation.dto.response.CustomerRoomUsage;
import com.openstudy.modules.reservation.dto.response.ReservationBilling;
import com.openstudy.modules.reservation.dto.response.RoomRef;
import com.openstudy.modules.reservation.dto.response.RoomReservationCalendar;
import com.openstudy.modules.reservation.dto.response.RoomReservationView;
import com.openstudy.modules.reservation.dto.response.RoomUsageView;
import com.openstudy.modules.reservation.entity.Room;
import com.openstudy.modules.reservation.entity.RoomReservation;
import com.openstudy.modules.reservation.entity.RoomReservationSlot;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;
import com.openstudy.modules.reservation.entity.RoomState;
import com.openstudy.modules.reservation.entity.RoomUsage;
import com.openstudy.modules.reservation.repository.RoomRepository;
import com.openstudy.modules.reservation.repository.RoomReservationRepository;
import com.openstudy.modules.reservation.repository.RoomReservationSearchRepository;
import com.openstudy.modules.reservation.repository.RoomReservationSlotRepository;
import com.openstudy.modules.reservation.repository.RoomUsageRepository;

/**
 * Booking with prepayment and its reads (FR-ROOM-02/03/05/06, FR-PAY-07, M6-D01·D05), cancellation with its full refund
 * (FR-ROOM-04, M6-D04) and the use of a room (FR-ROOM-08). The payment, the reservation and the held slots commit together
 * or not at all. The amount comes only from the room row read under its shared lock: no request field carries money,
 * hours or a rate. Every check of the contract's tables runs under the locks, in its order, and the time conflict is
 * decided before any payment is written.
 */
@Service
@RequiredArgsConstructor
public class RoomReservationService {

    private static final String CREATE = "room-reservations:create";
    private static final String CANCEL = "room-reservations:cancel";
    private static final String ENTITY = "room-reservations";
    private static final int MAX_HOURS = 24;
    private static final int MAX_PARTY = 65_535;
    private static final Duration MAX_CALENDAR = Duration.ofDays(62);
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final String PAYMENT_KEY_PREFIX = "RPAY-";
    private static final String RESERVATION_KEY_PREFIX = "RSV-";
    private static final String REFUND_KEY_PREFIX = "RFD-";
    private static final String ACTIVE_BRANCH = "ACTIVE";
    private static final String APPROVED_OUTCOME = "APPROVED";
    private static final String FAILED_OUTCOME = "FAILED";
    private static final int MAX_INSTANT_LENGTH = 64;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);

    private final RoomReservationRepository roomReservationRepository;
    private final RoomReservationSearchRepository roomReservationSearchRepository;
    private final RoomReservationSlotRepository roomReservationSlotRepository;
    private final RoomUsageRepository roomUsageRepository;
    private final RoomRepository roomRepository;
    private final CustomerService customerService;
    private final BranchClosureService branchClosureService;
    private final PaymentService paymentService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public RoomReservationView create(
            long actor,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only(
                "branchId",
                "roomId",
                "customerId",
                "startAt",
                "endAt",
                "partySize",
                "method",
                "mockOutcome"
        );
        long branch = input.id("branchId");
        long roomId = input.id("roomId");
        long customerId = input.id("customerId");
        Instant start = TimeRange.instant(input.text("startAt", MAX_INSTANT_LENGTH), "startAt");
        Instant end = TimeRange.instant(input.text("endAt", MAX_INSTANT_LENGTH), "endAt");
        int partySize = (int) RoomInputs.parseInteger(body, "partySize", 1, MAX_PARTY);
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome")
                || APPROVED_OUTCOME.equals(input.choice("mockOutcome", APPROVED_OUTCOME, FAILED_OUTCOME));
        RoomInputs.requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(
                branch,
                roomId,
                customerId,
                start.toString(),
                end.toString(),
                partySize,
                method,
                approved
        );
        byte[] fingerprint = RoomInputs.digest(objectMapper.writeValueAsString(fingerprintInput));

        // WRITE_PAYMENT_HISTORY, not NEW_PAYMENT: a stopped branch must answer BRANCH_NOT_ACTIVE at its place in the table.
        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_PAYMENT_HISTORY,
                () -> {},
                state -> {
                    Optional<RoomReservationView> replayed = replay(actor, CREATE, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Room room = roomRepository.findLockedShared(roomId)
                            .filter(found -> authorizationService.permits(
                                    state,
                                    found.getBranchId(),
                                    BranchAction.READ
                            ))
                            .orElseThrow(RoomReservationService::createNotFound);

                    if (room.getBranchId() != branch) {
                        throw ApiException.unprocessable("ROOM_BRANCH_MISMATCH", "요청한 지점의 룸이 아닙니다.");
                    }

                    if (!ACTIVE_BRANCH.equals(state.branches().get(branch))) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 예약할 수 없습니다.");
                    }

                    if (room.getStatus() == RoomState.BLOCKED) {
                        throw ApiException.unprocessable("ROOM_NOT_AVAILABLE", "운영 중단된 룸은 예약할 수 없습니다.");
                    }

                    // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    int hours = countHours(start, end, now);
                    // M13-D01: a closure refuses only a new reservation that starts on the closed day (Asia/Seoul).
                    LocalDate startDay = LocalDate.ofInstant(start, BranchClosureService.SEOUL);

                    if (branchClosureService.closed(branch, startDay)) {
                        throw OperationsConflict.of("BRANCH_CLOSED_DAY", "휴무일에는 예약할 수 없습니다.", "closedDate",
                                startDay.toString());
                    }

                    if (partySize > room.getCapacity()) {
                        throw ApiException.unprocessable("RESERVATION_CAPACITY_EXCEEDED", "인원이 룸 정원을 넘습니다.");
                    }

                    customerService.requirePurchasable(state, customerId);

                    if (roomReservationRepository.existsOverlapping(roomId, RoomReservationStatus.ACTIVE, start, end)) {
                        throw RoomConflicts.createTimeConflict();
                    }

                    long amount = calculateAmount(room, hours);
                    String paymentKey = RoomInputs.toStoredKey(PAYMENT_KEY_PREFIX, actor, CREATE, scopeKey, key);
                    long paymentId = RoomConflicts.translating(() -> paymentService.chargeRoom(actor, customerId,
                            branch, method, amount, approved, paymentKey, now, requestId));
                    String reservationKey = RoomInputs.toStoredKey(RESERVATION_KEY_PREFIX, actor, CREATE, scopeKey,
                            key);
                    RoomReservation newReservation = RoomReservation.booked(room, customerId, paymentId, start, end,
                            hours, amount, approved, reservationKey, actor);
                    RoomReservation reservation = RoomConflicts.translating(
                            () -> roomReservationRepository.saveAndFlush(newReservation)
                    );

                    if (approved) {
                        List<RoomReservationSlot> slots = createSlots(reservation);
                        RoomConflicts.translating(() -> roomReservationSlotRepository.saveAllAndFlush(slots));
                    }

                    String snapshot = objectMapper.writeValueAsString(toSnapshot(reservation));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, reservation.getId(),
                            reservation.getStatus().name(), null, null, snapshot, requestId));
                    RoomReservationView result = toViews(List.of(reservation), state, now).getFirst();
                    completeRequest(actor, CREATE, scopeKey, key, fingerprint, CREATED, result);

                    return result;
                }
        );
    }

    /**
     * The full refund of a confirmed reservation before its start (M6-D04). The refund event, both status changes, the
     * freed slots, audit, events and the idempotent result commit together. A stopped branch may still refund (FR-BR-08),
     * and the reservation stays as a record, so the same hour is simply booked again with a new key.
     */
    public RoomReservationView cancel(
            long actor,
            long id,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.text("reason", MAX_REASON_LENGTH);
        RoomInputs.requireValidKey(key);
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = RoomInputs.digest(objectMapper.writeValueAsString(Arrays.asList(id, version, reason)));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> roomReservationRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY);
                    // Before the version check: the retry of a cancellation that went through carries the version it
                    // replaced.
                    Optional<RoomReservationView> replayed = replay(actor, CANCEL, scopeKey, key, fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    RoomReservation reservation = roomReservationRepository.findLocked(id)
                            .orElseThrow(RoomReservationService::createNotFound);
                    ApiVersion.requireCurrent(version, reservation.getVersion(), toReloadPath(id));

                    if (reservation.getStatus() != RoomReservationStatus.CONFIRMED) {
                        throw ApiException.conflict("RESERVATION_NOT_CANCELLABLE", "확정 상태의 예약만 취소할 수 있습니다.");
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

                    if (!now.isBefore(reservation.getStartAt())) {
                        throw ApiException.conflict("RESERVATION_NOT_CANCELLABLE", "시작 시각이 지난 예약은 취소할 수 없습니다.");
                    }

                    String refundKey = RoomInputs.toStoredKey(REFUND_KEY_PREFIX, actor, CANCEL, scopeKey, key);
                    long refundId = paymentService.refundRoom(actor, reservation.getPaymentId(), reason, refundKey,
                            now, requestId);
                    String before = reservation.getStatus().name();
                    reservation.cancel(actor, reason, now);
                    roomReservationRepository.saveAndFlush(reservation);
                    roomReservationSlotRepository.deleteAllOf(id);

                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("status", reservation.getStatus().name());
                    after.put("paymentId", Long.toString(reservation.getPaymentId()));
                    after.put("refundId", Long.toString(refundId));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, reservation.getStatus().name(),
                            reason, objectMapper.writeValueAsString(Map.of("status", before)),
                            objectMapper.writeValueAsString(after), requestId));
                    RoomReservationView result = toViews(List.of(reservation), state, now).getFirst();
                    completeRequest(actor, CANCEL, scopeKey, key, fingerprint, OK, result);

                    return result;
                }
        );
    }

    /** Any employee of the branch. The start is when staff processes it, so the booked period is not compared. */
    public RoomReservationView start(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        long version = new StrictInput(body).only("version").version("version");
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> roomReservationRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    RoomReservation reservation = roomReservationRepository.findLocked(id)
                            .orElseThrow(RoomReservationService::createNotFound);
                    ApiVersion.requireCurrent(version, reservation.getVersion(), toReloadPath(id));

                    if (reservation.getStatus() != RoomReservationStatus.CONFIRMED) {
                        throw ApiException.conflict("INVALID_STATE", "확정 상태의 예약만 이용을 시작할 수 있습니다.");
                    }

                    if (!ACTIVE_BRANCH.equals(state.branches().get(branch))) {
                        throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 이용을 시작할 수 없습니다.");
                    }

                    // A write lock, because two starts in one room must wait for each other: shared locks would not.
                    Room room = roomRepository.findLocked(reservation.getRoomId()).orElseThrow();

                    if (room.getStatus() == RoomState.BLOCKED) {
                        throw ApiException.conflict("ROOM_BLOCKED", "운영 중단된 룸은 이용을 시작할 수 없습니다.");
                    }

                    if (roomReservationRepository.existsByRoomIdAndStatus(room.getId(), RoomReservationStatus.IN_USE)) {
                        throw ApiException.conflict("ROOM_IN_USE", "같은 룸에 이용 중인 예약이 있습니다.");
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    roomUsageRepository.saveAndFlush(new RoomUsage(id, now, actor));

                    return applyUsageChange(actor, reservation, reservation::startUsage, Map::of, state, now,
                            requestId);
                }
        );
    }

    /**
     * Any employee of the branch, also while the branch is stopped or the room is out of service (FR-BR-08). Completing
     * early frees the hours not started yet. The rest are past, and a new booking must start after now, so a slot left
     * behind can never refuse one.
     */
    public RoomReservationView complete(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        long version = new StrictInput(body).only("version").version("version");
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> roomReservationRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    RoomReservation reservation = roomReservationRepository.findLocked(id)
                            .orElseThrow(RoomReservationService::createNotFound);
                    ApiVersion.requireCurrent(version, reservation.getVersion(), toReloadPath(id));

                    if (reservation.getStatus() != RoomReservationStatus.IN_USE) {
                        throw ApiException.conflict("INVALID_STATE", "이용 중인 예약만 완료할 수 있습니다.");
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    RoomUsage usage = roomUsageRepository.findByReservationId(id)
                            .orElseThrow(() -> new IllegalStateException("A started reservation has a usage"));
                    usage.end(now, actor);
                    roomUsageRepository.saveAndFlush(usage);
                    Supplier<Map<String, Object>> releaseSlots = () -> Map.of(
                            "releasedSlots",
                            roomReservationSlotRepository.deleteStartingAfter(id, now)
                    );

                    return applyUsageChange(actor, reservation, reservation::completeUsage, releaseSlots, state, now,
                            requestId);
                }
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomReservationView> list(
            long actor,
            String scope,
            ListQuery query,
            String status,
            String roomId,
            String customerId,
            String from,
            String to
    ) {
        RoomReservationStatus filterStatus = status == null ? null : RoomInputs.parseReservationStatus(status);
        Long room = roomId == null ? null : Long.valueOf(RoomInputs.parseId(roomId, "roomId"));
        Long customer = customerId == null ? null : Long.valueOf(RoomInputs.parseId(customerId, "customerId"));
        TimeRange range = TimeRange.parse(from, to);
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        RoomReservationSearchRepository.Filter filter = new RoomReservationSearchRepository.Filter(
                selected,
                filterStatus,
                room,
                customer,
                range
        );
        RoomReservationSearchRepository.Page page = roomReservationSearchRepository.find(
                filter,
                query.offset(),
                query.pageSize()
        );
        List<RoomReservationView> items = toViews(page.rows(), state, clock.instant());

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** A calendar names one branch, so an unreadable branch is the common 403; a room outside that branch is 404. */
    @Transactional(readOnly = true)
    public RoomReservationCalendar calendar(long actor, String branchId, String roomId, String from, String to) {
        if (branchId == null) {
            throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        }

        long branch = RoomInputs.parseId(branchId, "branchId");
        Long room = roomId == null ? null : Long.valueOf(RoomInputs.parseId(roomId, "roomId"));

        if (from == null) {
            throw ApiException.validation("from", "시작 시각을 입력해 주세요.");
        }

        if (to == null) {
            throw ApiException.validation("to", "종료 시각을 입력해 주세요.");
        }

        TimeRange range = TimeRange.parse(from, to);

        if (Duration.between(range.from(), range.to()).compareTo(MAX_CALENDAR) > 0) {
            throw ApiException.validation("to", "기간은 최대 62일입니다.");
        }

        AuthorizationState state = authorizationService.current(actor);
        authorizationService.require(state, Set.of(branch), BranchAction.READ);
        boolean roomOutsideBranch = room != null
                && roomRepository.findBranch(room).filter(owner -> owner == branch).isEmpty();

        if (roomOutsideBranch) {
            throw createNotFound();
        }

        RoomReservationSearchRepository.Filter filter = new RoomReservationSearchRepository.Filter(
                Set.of(branch),
                null,
                room,
                null,
                range
        );
        List<RoomReservation> rows = roomReservationSearchRepository.findCalendar(filter);
        List<LocalDate> closedDates = branchClosureService.closedDates(branch, range.from(), range.to());
        List<RoomReservationView> items = toViews(rows, state, clock.instant());

        return new RoomReservationCalendar(range.from(), range.to(), closedDates, items);
    }

    /** 404 hides reservations outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public RoomReservationView get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        RoomReservation reservation = roomReservationRepository.findById(id)
                .filter(found -> selected.contains(found.getBranchId()))
                .orElseThrow(RoomReservationService::createNotFound);

        return toViews(List.of(reservation), state, clock.instant()).getFirst();
    }

    /**
     * One customer's reservations at these branches, for a caller that has gated the customer and the branches. Through
     * the same mapping as every other response, so a history cannot disagree with the ledger; amounts and payment
     * references stay subject to {@code state}, branch by branch.
     */
    @Transactional(readOnly = true)
    public List<RoomReservationView> ofCustomer(AuthorizationState state, long customerId, Set<Long> branches) {
        if (branches.isEmpty()) {
            return List.of();
        }

        List<RoomReservation> rows = roomReservationRepository.findByCustomerFamily(customerId, branches);

        return toViews(rows, state, clock.instant());
    }

    /** The customer's latest actual uses at any branch, reduced to place and time for the cross-branch summary (M6-D06). */
    @Transactional(readOnly = true)
    public List<CustomerRoomUsage> recentUsages(long customerId, int limit) {
        return roomUsageRepository.findLatestOf(customerId, Limit.of(limit));
    }

    /**
     * Whole hours between two on-the-hour instants. Both ends are tested in UTC: Korea's offset is a whole number of
     * hours, so the answer is the same there, and a nanosecond beyond the column's precision is refused, never rounded.
     */
    static int countHours(Instant start, Instant end, Instant now) {
        boolean isValidPeriod = isOnTheHour(start)
                && isOnTheHour(end)
                && end.isAfter(start)
                && start.isAfter(now);

        if (!isValidPeriod) {
            throw createTimeInvalid();
        }

        long hours = Duration.between(start, end).toHours();

        if (hours > MAX_HOURS) {
            throw createTimeInvalid();
        }

        return (int) hours;
    }

    /** The status change of a use, what follows from it, its audit row and event, answered in the shape of every response. */
    private RoomReservationView applyUsageChange(
            long actor,
            RoomReservation reservation,
            Runnable change,
            Supplier<Map<String, Object>> effects,
            AuthorizationState state,
            Instant now,
            String requestId
    ) {
        String before = reservation.getStatus().name();
        change.run();
        roomReservationRepository.saveAndFlush(reservation);
        Map<String, Object> after = new LinkedHashMap<>(effects.get());
        after.put("status", reservation.getStatus().name());
        auditTrail.record(AuditEvent.change(actor, reservation.getBranchId(), ENTITY, reservation.getId(),
                reservation.getStatus().name(), null, objectMapper.writeValueAsString(Map.of("status", before)),
                objectMapper.writeValueAsString(after), requestId));

        return toViews(List.of(reservation), state, now).getFirst();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<RoomReservationView> replay(
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

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), RoomReservationView.class));
    }

    private void completeRequest(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            int status,
            RoomReservationView result
    ) {
        String responseJson = objectMapper.writeValueAsString(result);
        Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
        idempotencyStore.complete(actor, operation, scopeKey, key, fingerprint, status, responseJson, expiresAt);
    }

    private static boolean isOnTheHour(Instant at) {
        return at.getNano() == 0 && Math.floorMod(at.getEpochSecond(), SECONDS_PER_HOUR) == 0;
    }

    private static ApiException createTimeInvalid() {
        return ApiException.unprocessable("RESERVATION_TIME_INVALID",
                "정시에 시작하고 1시간 단위로 24시간 이내여야 하며, 시작 시각이 지나지 않아야 합니다.");
    }

    /** rate × hours from the room as locked now. A sum clients could not read exactly is refused, never stored. */
    private static long calculateAmount(Room room, int hours) {
        long amount = Math.multiplyExact(room.getRatePerHourKrw(), hours);

        if (amount > ApiVersion.MAX) {
            throw ApiException.validation("endAt", "결제 금액이 허용 범위를 넘습니다.");
        }

        return amount;
    }

    private static List<RoomReservationSlot> createSlots(RoomReservation reservation) {
        List<RoomReservationSlot> held = new ArrayList<>(reservation.getHours());

        for (int hour = 0; hour < reservation.getHours(); hour++) {
            Instant slotStartAt = reservation.getStartAt().plus(Duration.ofHours(hour));
            held.add(new RoomReservationSlot(reservation.getId(), reservation.getRoomId(), slotStartAt));
        }

        return held;
    }

    private static Map<String, Object> toSnapshot(RoomReservation reservation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", reservation.getStatus().name());
        values.put("roomId", Long.toString(reservation.getRoomId()));
        values.put("customerId", Long.toString(reservation.getCustomerId()));
        values.put("paymentId", Long.toString(reservation.getPaymentId()));
        values.put("startAt", reservation.getStartAt().toString());
        values.put("endAt", reservation.getEndAt().toString());
        values.put("ratePerHourKrw", reservation.getRatePerHourSnapshotKrw());
        values.put("hours", reservation.getHours());
        values.put("amountKrw", reservation.getAmountKrw());

        return values;
    }

    /** The one mapping behind every response, so the list, the calendar and the detail cannot disagree (FR-ROOM-05). */
    private List<RoomReservationView> toViews(List<RoomReservation> rows, AuthorizationState state, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> roomIds = rows.stream()
                .map(RoomReservation::getRoomId)
                .distinct()
                .toList();
        Map<Long, Room> byId = new HashMap<>();
        roomRepository.findAllById(roomIds).forEach(room -> byId.put(room.getId(), room));
        List<Long> customerIds = rows.stream()
                .map(RoomReservation::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> people = customerService.summaries(customerIds);
        List<Long> reservationIds = rows.stream()
                .map(RoomReservation::getId)
                .toList();
        Map<Long, RoomUsage> used = new HashMap<>();
        roomUsageRepository.findByReservationIdIn(reservationIds)
                .forEach(usage -> used.put(usage.getReservationId(), usage));
        // One lookup for every person on these rows; a started-by or ended-by whose employee is gone is null.
        List<Long> actorIds = Stream.of(
                        rows.stream().map(RoomReservation::getCreatedBy),
                        rows.stream().map(RoomReservation::getCancelledBy),
                        used.values().stream().map(RoomUsage::getStartedBy),
                        used.values().stream().map(RoomUsage::getEndedBy)
                )
                .flatMap(ids -> ids)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);
        List<Long> billedPaymentIds = rows.stream()
                .filter(row -> canSeeBilling(state, row.getBranchId()))
                .map(RoomReservation::getPaymentId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> paymentLabels = paymentService.statusLabels(billedPaymentIds);

        return rows.stream()
                .map(row -> toView(row, byId.get(row.getRoomId()), people, used, names, paymentLabels, state, now))
                .toList();
    }

    private RoomReservationView toView(
            RoomReservation row,
            Room room,
            Map<Long, CustomerSummary> people,
            Map<Long, RoomUsage> used,
            Map<Long, String> names,
            Map<Long, String> paymentLabels,
            AuthorizationState state,
            Instant now
    ) {
        boolean canCancel = row.getStatus() == RoomReservationStatus.CONFIRMED
                && now.isBefore(row.getStartAt())
                && authorizationService.permits(state, row.getBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);

        return new RoomReservationView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getStatus().label(),
                Long.toString(row.getBranchId()),
                new RoomRef(room.getId().toString(), room.getRoomNo(), room.getName()),
                people.get(row.getCustomerId()),
                row.getStartAt(),
                row.getEndAt(),
                toBilling(row, paymentLabels),
                toUsageView(used.get(row.getId()), names),
                row.getCancelledAt(),
                toActorSummary(row.getCancelledBy(), names),
                row.getCancelReason(),
                toActorSummary(row.getCreatedBy(), names),
                row.getCreatedAt(),
                canCancel
        );
    }

    /** Amounts and payment references follow the reservation's own branch, row by row (contract "결제 정보 노출"). */
    private boolean canSeeBilling(AuthorizationState state, long branch) {
        return authorizationService.permits(state, branch, BranchAction.READ_PAYMENTS)
                || authorizationService.permits(state, branch, BranchAction.WRITE_PAYMENT_HISTORY);
    }

    /** Labels exist only for the rows the reader may bill, so a missing label means "show nothing". */
    private static ReservationBilling toBilling(RoomReservation row, Map<Long, String> paymentLabels) {
        if (row.getPaymentId() == null || !paymentLabels.containsKey(row.getPaymentId())) {
            return null;
        }

        return new ReservationBilling(
                row.getRatePerHourSnapshotKrw(),
                row.getHours(),
                row.getAmountKrw(),
                Long.toString(row.getPaymentId()),
                paymentLabels.get(row.getPaymentId())
        );
    }

    private static RoomUsageView toUsageView(RoomUsage usage, Map<Long, String> names) {
        if (usage == null) {
            return null;
        }

        return new RoomUsageView(
                usage.getStartedAt(),
                usage.getEndedAt(),
                toActorSummary(usage.getStartedBy(), names),
                toActorSummary(usage.getEndedBy(), names)
        );
    }

    private static ActorSummary toActorSummary(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    private static ResponseStatusException createNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String toReloadPath(long id) {
        return "/api/room-reservations/" + id;
    }
}
