package com.openstudy.modules.reservation.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final RoomRepository repository;
    private final RoomSearchRepository search;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<RoomView> list(long actor, String scope, ListQuery query, String status) {
        RoomState filter = status == null ? null : RoomInputs.state(status, "status");
        var page = search.find(policy.scope(policy.current(actor), scope, BranchAction.READ), filter, query.offset(), query.pageSize());
        return new PageResponse<>(page.rows().stream().map(RoomService::view).toList(),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides rooms outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public RoomView get(long actor, long id, String scope) {
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        return repository.findById(id).filter(room -> selected.contains(room.getBranchId())).map(RoomService::view)
                .orElseThrow(RoomService::notFound);
    }

    public RoomView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "roomNo", "name", "capacity", "ratePerHourKrw", "status");
        long branch = input.id("branchId");
        String roomNo = input.text("roomNo", 40);
        String name = input.text("name", 120);
        int capacity = (int) RoomInputs.integer(body, "capacity", 1, MAX_CAPACITY);
        long rate = RoomInputs.integer(body, "ratePerHourKrw", 1, ApiVersion.MAX);
        RoomState stored = input.has("status") ? RoomInputs.state(input.text("status", 20), "status") : RoomState.AVAILABLE;
        RoomInputs.validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = RoomInputs.digest(json.writeValueAsString(Arrays.asList(branch, roomNo, name, capacity, rate, stored.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_ROOMS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            var now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), RoomView.class);
                }
                requests.discard(prior.get());
            }
            if (repository.existsByBranchIdAndRoomNo(branch, roomNo)) throw RoomConflicts.duplicateRoomNo();
            var room = RoomConflicts.translating(() -> repository.saveAndFlush(new Room(branch, roomNo, name, capacity, rate, stored)));
            audit.record(AuditEvent.change(actor, branch, ENTITY, room.getId(), "CREATED", null, null,
                    json.writeValueAsString(snapshot(room)), requestId));
            var result = view(room);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Missing fields keep their value. Changing the rate or name leaves every reservation's snapshot as it was. */
    public RoomView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        var input = new StrictInput(body).only("version", "roomNo", "name", "capacity", "ratePerHourKrw");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String roomNo = input.has("roomNo") ? input.text("roomNo", 40) : null;
        String name = input.has("name") ? input.text("name", 120) : null;
        Integer capacity = input.has("capacity") ? Integer.valueOf((int) RoomInputs.integer(body, "capacity", 1, MAX_CAPACITY)) : null;
        Long rate = input.has("ratePerHourKrw") ? Long.valueOf(RoomInputs.integer(body, "ratePerHourKrw", 1, ApiVersion.MAX)) : null;
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> repository.branchOf(id).filter(selected::contains), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_ROOMS);
            var room = repository.locked(id).orElseThrow(RoomService::notFound);
            ApiVersion.requireCurrent(version, room.getVersion(), reload(id));
            String newRoomNo = roomNo == null ? room.getRoomNo() : roomNo;
            String newName = name == null ? room.getName() : name;
            int newCapacity = capacity == null ? room.getCapacity() : capacity;
            long newRate = rate == null ? room.getRatePerHourKrw() : rate;
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("roomNo", room.getRoomNo(), newRoomNo, before, after);
            compare("name", room.getName(), newName, before, after);
            compare("capacity", room.getCapacity(), newCapacity, before, after);
            compare("ratePerHourKrw", room.getRatePerHourKrw(), newRate, before, after);
            // An identical request is not a change: no version bump, audit row or event.
            if (before.isEmpty()) return view(room);
            if (!newRoomNo.equals(room.getRoomNo()) && repository.existsByBranchIdAndRoomNoAndIdNot(branch, newRoomNo, id))
                throw RoomConflicts.duplicateRoomNo();
            room.change(newRoomNo, newName, newCapacity, newRate);
            RoomConflicts.translating(() -> repository.saveAndFlush(room));
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null, json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            return view(room);
        });
    }

    /** Stopping refuses new bookings only; existing reservations stay and a manager cancels them if needed. */
    public RoomView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        RoomState next = RoomInputs.state(input.text("status", 20), "status");
        String reason = input.text("reason", 500);
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> repository.branchOf(id).filter(selected::contains), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_ROOMS);
            var room = repository.locked(id).orElseThrow(RoomService::notFound);
            ApiVersion.requireCurrent(version, room.getVersion(), reload(id));
            RoomState previous = room.getStatus();
            // The same state again is not a change: the current value comes back with no audit row or event.
            if (previous == next) return view(room);
            room.changeStatus(next);
            repository.saveAndFlush(room);
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                    json.writeValueAsString(Map.of("status", previous.label())),
                    json.writeValueAsString(Map.of("status", next.label())), requestId));
            return view(room);
        });
    }

    static RoomView view(Room room) {
        return new RoomView(room.getId().toString(), ApiVersion.fromDatabase(room.getVersion()), Long.toString(room.getBranchId()),
                room.getRoomNo(), room.getName(), room.getCapacity(), room.getRatePerHourKrw(), room.getStatus().label(),
                room.getCreatedAt(), room.getUpdatedAt());
    }

    private static Map<String, Object> snapshot(Room room) {
        var values = new LinkedHashMap<String, Object>();
        values.put("roomNo", room.getRoomNo());
        values.put("name", room.getName());
        values.put("capacity", room.getCapacity());
        values.put("ratePerHourKrw", room.getRatePerHourKrw());
        values.put("status", room.getStatus().label());
        return values;
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        if (before.equals(after)) return;
        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    private static String reload(long id) { return "/api/rooms/" + id; }
}
