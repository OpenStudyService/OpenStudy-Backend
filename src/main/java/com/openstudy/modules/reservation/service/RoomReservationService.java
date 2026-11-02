package com.openstudy.modules.reservation.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import java.util.stream.Stream;
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
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.reservation.dto.response.ActorSummary;
import com.openstudy.modules.reservation.dto.response.ReservationBilling;
import com.openstudy.modules.reservation.dto.response.RoomRef;
import com.openstudy.modules.reservation.dto.response.RoomReservationCalendar;
import com.openstudy.modules.reservation.dto.response.RoomReservationView;
import com.openstudy.modules.reservation.entity.Room;
import com.openstudy.modules.reservation.entity.RoomReservation;
import com.openstudy.modules.reservation.entity.RoomReservationSlot;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;
import com.openstudy.modules.reservation.entity.RoomState;
import com.openstudy.modules.reservation.repository.RoomRepository;
import com.openstudy.modules.reservation.repository.RoomReservationRepository;
import com.openstudy.modules.reservation.repository.RoomReservationSearchRepository;
import com.openstudy.modules.reservation.repository.RoomReservationSlotRepository;

/**
 * Booking with prepayment and its reads (FR-ROOM-02/03/05/06, FR-PAY-07, M6-D01·D05). The payment, the reservation and
 * the held slots commit together or not at all. The amount comes only from the room row read under its shared lock: no
 * request field carries money, hours or a rate. Every check of the contract's table runs under the locks, in its order,
 * and the time conflict is decided before any payment is written.
 */
@Service
@RequiredArgsConstructor
public class RoomReservationService {
    private static final String CREATE = "room-reservations:create";
    private static final String ENTITY = "room-reservations";
    private static final int MAX_HOURS = 24;
    private static final int MAX_PARTY = 65_535;
    private static final Duration MAX_CALENDAR = Duration.ofDays(62);
    private final RoomReservationRepository reservations;
    private final RoomReservationSearchRepository search;
    private final RoomReservationSlotRepository slots;
    private final RoomRepository rooms;
    private final CustomerService customers;
    private final PaymentService payments;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    public RoomReservationView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "roomId", "customerId", "startAt", "endAt", "partySize", "method", "mockOutcome");
        long branch = input.id("branchId");
        long roomId = input.id("roomId");
        long customerId = input.id("customerId");
        Instant start = TimeRange.instant(input.text("startAt", 64), "startAt");
        Instant end = TimeRange.instant(input.text("endAt", 64), "endAt");
        int partySize = (int) RoomInputs.integer(body, "partySize", 1, MAX_PARTY);
        String method = input.choice("method", "현금", "카드", "계좌이체", "기타");
        boolean approved = !input.has("mockOutcome") || "APPROVED".equals(input.choice("mockOutcome", "APPROVED", "FAILED"));
        RoomInputs.validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = RoomInputs.digest(json.writeValueAsString(Arrays.asList(branch, roomId, customerId, start.toString(),
                end.toString(), partySize, method, approved)));
        // WRITE_PAYMENT_HISTORY, not NEW_PAYMENT: a stopped branch must answer BRANCH_NOT_ACTIVE at its place in the table.
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY, () -> {}, state -> {
            var replayed = replay(actor, scopeKey, key, fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var room = rooms.lockedShared(roomId).filter(found -> policy.permits(state, found.getBranchId(), BranchAction.READ))
                    .orElseThrow(RoomReservationService::notFound);
            if (room.getBranchId() != branch)
                throw ApiException.unprocessable("ROOM_BRANCH_MISMATCH", "요청한 지점의 룸이 아닙니다.");
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에서는 예약할 수 없습니다.");
            if (room.getStatus() == RoomState.BLOCKED)
                throw ApiException.unprocessable("ROOM_NOT_AVAILABLE", "운영 중단된 룸은 예약할 수 없습니다.");
            // DATETIME(6) keeps microseconds, so the response shows exactly what is stored.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            int hours = hours(start, end, now);
            if (partySize > room.getCapacity())
                throw ApiException.unprocessable("RESERVATION_CAPACITY_EXCEEDED", "인원이 룸 정원을 넘습니다.");
            customers.requirePurchasable(state, customerId);
            if (reservations.existsOverlapping(roomId, RoomReservationStatus.ACTIVE, start, end)) throw RoomConflicts.timeConflict();
            long amount = amount(room, hours);
            long paymentId = RoomConflicts.translating(() -> payments.chargeRoom(actor, customerId, branch, method, amount, approved,
                    RoomInputs.storedKey("RPAY-", actor, CREATE, scopeKey, key), now, requestId));
            var reservation = RoomConflicts.translating(() -> reservations.saveAndFlush(RoomReservation.booked(room, customerId, paymentId,
                    start, end, hours, amount, approved, RoomInputs.storedKey("RSV-", actor, CREATE, scopeKey, key), actor)));
            if (approved) RoomConflicts.translating(() -> slots.saveAllAndFlush(slots(reservation)));
            audit.record(AuditEvent.change(actor, branch, ENTITY, reservation.getId(), reservation.getStatus().name(), null, null,
                    json.writeValueAsString(snapshot(reservation)), requestId));
            outbox.invalidate(branch, ENTITY, reservation.getId());
            var result = views(List.of(reservation), state, now).getFirst();
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomReservationView> list(long actor, String scope, ListQuery query, String status, String roomId,
            String customerId, String from, String to) {
        var filterStatus = status == null ? null : RoomInputs.reservationStatus(status);
        Long room = roomId == null ? null : Long.valueOf(RoomInputs.id(roomId, "roomId"));
        Long customer = customerId == null ? null : Long.valueOf(RoomInputs.id(customerId, "customerId"));
        var range = TimeRange.parse(from, to);
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var page = search.find(new RoomReservationSearchRepository.Filter(selected, filterStatus, room, customer, range),
                query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state, clock.instant()), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** A calendar names one branch, so an unreadable branch is the common 403; a room outside that branch is 404. */
    @Transactional(readOnly = true)
    public RoomReservationCalendar calendar(long actor, String branchId, String roomId, String from, String to) {
        if (branchId == null) throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        long branch = RoomInputs.id(branchId, "branchId");
        Long room = roomId == null ? null : Long.valueOf(RoomInputs.id(roomId, "roomId"));
        if (from == null) throw ApiException.validation("from", "시작 시각을 입력해 주세요.");
        if (to == null) throw ApiException.validation("to", "종료 시각을 입력해 주세요.");
        var range = TimeRange.parse(from, to);
        if (Duration.between(range.from(), range.to()).compareTo(MAX_CALENDAR) > 0)
            throw ApiException.validation("to", "기간은 최대 62일입니다.");
        var state = policy.current(actor);
        policy.require(state, Set.of(branch), BranchAction.READ);
        if (room != null && rooms.branchOf(room).filter(owner -> owner == branch).isEmpty()) throw notFound();
        var rows = search.calendar(new RoomReservationSearchRepository.Filter(Set.of(branch), null, room, null, range));
        return new RoomReservationCalendar(range.from(), range.to(), views(rows, state, clock.instant()));
    }

    /** 404 hides reservations outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public RoomReservationView get(long actor, long id, String scope) {
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var reservation = reservations.findById(id).filter(found -> selected.contains(found.getBranchId()))
                .orElseThrow(RoomReservationService::notFound);
        return views(List.of(reservation), state, clock.instant()).getFirst();
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<RoomReservationView> replay(long actor, String scopeKey, String key, byte[] fingerprint) {
        var prior = requests.lock(actor, CREATE, scopeKey, key);
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), RoomReservationView.class));
    }

    /**
     * Whole hours between two on-the-hour instants. Both ends are tested in UTC: Korea's offset is a whole number of
     * hours, so the answer is the same there, and a nanosecond beyond the column's precision is refused, never rounded.
     */
    static int hours(Instant start, Instant end, Instant now) {
        if (!onTheHour(start) || !onTheHour(end) || !end.isAfter(start) || !start.isAfter(now)) throw timeInvalid();
        long hours = Duration.between(start, end).toHours();
        if (hours > MAX_HOURS) throw timeInvalid();
        return (int) hours;
    }

    private static boolean onTheHour(Instant at) {
        return at.getNano() == 0 && Math.floorMod(at.getEpochSecond(), 3600L) == 0;
    }

    private static ApiException timeInvalid() {
        return ApiException.unprocessable("RESERVATION_TIME_INVALID", "정시에 시작하고 1시간 단위로 24시간 이내여야 하며, 시작 시각이 지나지 않아야 합니다.");
    }

    /** rate × hours from the room as locked now. A sum clients could not read exactly is refused, never stored. */
    private static long amount(Room room, int hours) {
        long amount = Math.multiplyExact(room.getRatePerHourKrw(), hours);
        if (amount > ApiVersion.MAX) throw ApiException.validation("endAt", "결제 금액이 허용 범위를 넘습니다.");
        return amount;
    }

    private static List<RoomReservationSlot> slots(RoomReservation reservation) {
        var held = new ArrayList<RoomReservationSlot>(reservation.getHours());
        for (int hour = 0; hour < reservation.getHours(); hour++) {
            held.add(new RoomReservationSlot(reservation.getId(), reservation.getRoomId(),
                    reservation.getStartAt().plus(Duration.ofHours(hour))));
        }
        return held;
    }

    private static Map<String, Object> snapshot(RoomReservation reservation) {
        var values = new LinkedHashMap<String, Object>();
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
    private List<RoomReservationView> views(List<RoomReservation> rows, AuthorizationState state, Instant now) {
        if (rows.isEmpty()) return List.of();
        Map<Long, Room> byId = new HashMap<>();
        rooms.findAllById(rows.stream().map(RoomReservation::getRoomId).distinct().toList()).forEach(room -> byId.put(room.getId(), room));
        var people = customers.summaries(rows.stream().map(RoomReservation::getCustomerId).toList());
        var names = employees.names(Stream.concat(rows.stream().map(RoomReservation::getCreatedBy),
                rows.stream().map(RoomReservation::getCancelledBy).filter(Objects::nonNull)).toList());
        var paymentLabels = payments.statusLabels(rows.stream().filter(row -> billingVisible(state, row.getBranchId()))
                .map(RoomReservation::getPaymentId).filter(Objects::nonNull).toList());
        return rows.stream().map(row -> {
            var room = byId.get(row.getRoomId());
            boolean canCancel = row.getStatus() == RoomReservationStatus.CONFIRMED && now.isBefore(row.getStartAt())
                    && policy.permits(state, row.getBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);
            return new RoomReservationView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), row.getStatus().label(),
                    Long.toString(row.getBranchId()), new RoomRef(room.getId().toString(), room.getRoomNo(), room.getName()),
                    people.get(row.getCustomerId()), row.getStartAt(), row.getEndAt(), billing(row, paymentLabels), null, row.getCancelledAt(),
                    actor(row.getCancelledBy(), names), row.getCancelReason(), actor(row.getCreatedBy(), names), row.getCreatedAt(), canCancel);
        }).toList();
    }

    /** Amounts and payment references follow the reservation's own branch, row by row (contract "결제 정보 노출"). */
    private boolean billingVisible(AuthorizationState state, long branch) {
        return policy.permits(state, branch, BranchAction.READ_PAYMENTS)
                || policy.permits(state, branch, BranchAction.WRITE_PAYMENT_HISTORY);
    }

    /** Labels exist only for the rows the reader may bill, so a missing label means "show nothing". */
    private static ReservationBilling billing(RoomReservation row, Map<Long, String> paymentLabels) {
        if (row.getPaymentId() == null || !paymentLabels.containsKey(row.getPaymentId())) return null;
        return new ReservationBilling(row.getRatePerHourSnapshotKrw(), row.getHours(), row.getAmountKrw(),
                Long.toString(row.getPaymentId()), paymentLabels.get(row.getPaymentId()));
    }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
