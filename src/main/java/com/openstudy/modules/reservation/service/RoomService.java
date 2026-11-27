package com.openstudy.modules.reservation.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.reservation.dto.response.RoomView;
import com.openstudy.modules.reservation.entity.Room;
import com.openstudy.modules.reservation.entity.RoomState;
import com.openstudy.modules.reservation.repository.RoomRepository;
import com.openstudy.modules.reservation.repository.RoomSearchRepository;

/**
 * Room ledger (FR-ROOM-01, M6-D05). Any valid member of a branch reads; registering, editing and stopping need
 * operations:write there, also while the branch is stopped, because the ledger is an operating setting. A reservation
 * keeps its own rate snapshot and stays when its room is stopped, so nothing here touches reservations.
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final String CREATE = "rooms:create";
    private static final String ENTITY = "rooms";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "status");
    private static final int MAX_CAPACITY = 65_535;
    private static final int MAX_ROOM_NO_LENGTH = 40;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_LABEL_LENGTH = 20;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);

    private final RoomRepository roomRepository;
    private final RoomSearchRepository roomSearchRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<RoomView> list(long actor, String scope, ListQuery query, String status) {
        RoomState filter = status == null ? null : RoomInputs.parseState(status, "status");
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);
        RoomSearchRepository.Page page = roomSearchRepository.find(selected, filter, query.offset(), query.pageSize());
        List<RoomView> items = page.rows()
                .stream()
                .map(RoomService::toView)
                .toList();

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides rooms outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public RoomView get(long actor, long id, String scope) {
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);

        return roomRepository.findById(id)
                .filter(room -> selected.contains(room.getBranchId()))
                .map(RoomService::toView)
                .orElseThrow(RoomService::createNotFound);
    }

    public RoomView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body)
                .only("branchId", "roomNo", "name", "capacity", "ratePerHourKrw", "status");
        long branch = input.id("branchId");
        String roomNo = input.text("roomNo", MAX_ROOM_NO_LENGTH);
        String name = input.text("name", MAX_NAME_LENGTH);
        int capacity = (int) RoomInputs.parseInteger(body, "capacity", 1, MAX_CAPACITY);
        long rate = RoomInputs.parseInteger(body, "ratePerHourKrw", 1, ApiVersion.MAX);
        RoomState stored = input.has("status")
                ? RoomInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status")
                : RoomState.AVAILABLE;
        RoomInputs.requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(branch, roomNo, name, capacity, rate, stored.name());
        byte[] fingerprint = RoomInputs.digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.WRITE_ROOMS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    }

                    return objectMapper.readValue(prior.get().getResponseJson(), RoomView.class);
                }

                idempotencyStore.discard(prior.get());
            }

            if (roomRepository.existsByBranchIdAndRoomNo(branch, roomNo)) {
                throw RoomConflicts.createDuplicateRoomNo();
            }

            Room newRoom = new Room(branch, roomNo, name, capacity, rate, stored);
            Room room = RoomConflicts.translating(() -> roomRepository.saveAndFlush(newRoom));
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, room.getId(), "CREATED", null, null,
                    objectMapper.writeValueAsString(toSnapshot(room)), requestId));
            RoomView result = toView(room);
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, scopeKey, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    /** Missing fields keep their value. Changing the rate or name leaves every reservation's snapshot as it was. */
    public RoomView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        boolean hasImmutableField = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (hasImmutableField) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD",
                    "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        }

        StrictInput input = new StrictInput(body).only("version", "roomNo", "name", "capacity", "ratePerHourKrw");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String roomNo = input.has("roomNo") ? input.text("roomNo", MAX_ROOM_NO_LENGTH) : null;
        String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : null;
        Integer capacity = input.has("capacity")
                ? Integer.valueOf((int) RoomInputs.parseInteger(body, "capacity", 1, MAX_CAPACITY))
                : null;
        Long rate = input.has("ratePerHourKrw")
                ? Long.valueOf(RoomInputs.parseInteger(body, "ratePerHourKrw", 1, ApiVersion.MAX))
                : null;
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> roomRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_ROOMS);
                    Room room = roomRepository.findLocked(id).orElseThrow(RoomService::createNotFound);
                    ApiVersion.requireCurrent(version, room.getVersion(), toReloadPath(id));
                    String newRoomNo = roomNo == null ? room.getRoomNo() : roomNo;
                    String newName = name == null ? room.getName() : name;
                    int newCapacity = capacity == null ? room.getCapacity() : capacity;
                    long newRate = rate == null ? room.getRatePerHourKrw() : rate;
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    putIfChanged("roomNo", room.getRoomNo(), newRoomNo, before, after);
                    putIfChanged("name", room.getName(), newName, before, after);
                    putIfChanged("capacity", room.getCapacity(), newCapacity, before, after);
                    putIfChanged("ratePerHourKrw", room.getRatePerHourKrw(), newRate, before, after);

                    // An identical request is not a change: no version bump, audit row or event.
                    if (before.isEmpty()) {
                        return toView(room);
                    }

                    boolean roomNoTaken = !newRoomNo.equals(room.getRoomNo())
                            && roomRepository.existsByBranchIdAndRoomNoAndIdNot(branch, newRoomNo, id);

                    if (roomNoTaken) {
                        throw RoomConflicts.createDuplicateRoomNo();
                    }

                    room.change(newRoomNo, newName, newCapacity, newRate);
                    RoomConflicts.translating(() -> roomRepository.saveAndFlush(room));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null,
                            objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after),
                            requestId));

                    return toView(room);
                }
        );
    }

    /** Stopping refuses new bookings only; existing reservations stay and a manager cancels them if needed. */
    public RoomView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        RoomState next = RoomInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status");
        String reason = input.text("reason", MAX_REASON_LENGTH);
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> roomRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_ROOMS);
                    Room room = roomRepository.findLocked(id).orElseThrow(RoomService::createNotFound);
                    ApiVersion.requireCurrent(version, room.getVersion(), toReloadPath(id));
                    RoomState previous = room.getStatus();

                    // The same state again is not a change: the current value comes back with no audit row or event.
                    if (previous == next) {
                        return toView(room);
                    }

                    room.changeStatus(next);
                    roomRepository.saveAndFlush(room);
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                            objectMapper.writeValueAsString(Map.of("status", previous.label())),
                            objectMapper.writeValueAsString(Map.of("status", next.label())), requestId));

                    return toView(room);
                }
        );
    }

    static RoomView toView(Room room) {
        return new RoomView(
                room.getId().toString(),
                ApiVersion.fromDatabase(room.getVersion()),
                Long.toString(room.getBranchId()),
                room.getRoomNo(),
                room.getName(),
                room.getCapacity(),
                room.getRatePerHourKrw(),
                room.getStatus().label(),
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }

    private static Map<String, Object> toSnapshot(Room room) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("roomNo", room.getRoomNo());
        values.put("name", room.getName());
        values.put("capacity", room.getCapacity());
        values.put("ratePerHourKrw", room.getRatePerHourKrw());
        values.put("status", room.getStatus().label());

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

    private static ResponseStatusException createNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String toReloadPath(long id) {
        return "/api/rooms/" + id;
    }
}
