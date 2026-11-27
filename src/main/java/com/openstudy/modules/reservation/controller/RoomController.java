package com.openstudy.modules.reservation.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.reservation.dto.response.RoomView;
import com.openstudy.modules.reservation.service.RoomService;

/** There is deliberately no DELETE for a room: a room is closed by stopping its operation (space-operations-api.md). */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final RoomService roomService;

    @GetMapping
    public PageResponse<RoomView> listRooms(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (roomNo, id), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return roomService.list(actorId, scope, query, status);
    }

    @GetMapping("/{id}")
    public RoomView getRoom(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return roomService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<RoomView> createRoom(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        RoomView result = roomService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/rooms/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public RoomView updateRoom(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return roomService.update(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/{id}/status")
    public RoomView changeRoomStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return roomService.changeStatus(actorId(actor), id, scope, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
