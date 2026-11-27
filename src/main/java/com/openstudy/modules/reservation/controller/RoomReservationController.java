package com.openstudy.modules.reservation.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.openstudy.modules.reservation.dto.response.RoomReservationCalendar;
import com.openstudy.modules.reservation.dto.response.RoomReservationView;
import com.openstudy.modules.reservation.service.RoomReservationService;

/** There is no DELETE or PATCH: a reservation changes by being cancelled and booked again (M6-D04). */
@RestController
@RequestMapping("/api/room-reservations")
@RequiredArgsConstructor
public class RoomReservationController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final RoomReservationService roomReservationService;

    @GetMapping
    public PageResponse<RoomReservationView> listRoomReservations(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (latest startAt first), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return roomReservationService.list(actorId, scope, query, status, roomId, customerId, from, to);
    }

    @GetMapping("/calendar")
    public RoomReservationCalendar getRoomReservationCalendar(
            Authentication actor,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return roomReservationService.calendar(actorId(actor), branchId, roomId, from, to);
    }

    @GetMapping("/{id}")
    public RoomReservationView getRoomReservation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return roomReservationService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<RoomReservationView> createRoomReservation(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        RoomReservationView result = roomReservationService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/room-reservations/" + result.id())).body(result);
    }

    @PostMapping("/{id}/cancel")
    public RoomReservationView cancelRoomReservation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return roomReservationService.cancel(actorId(actor), id, scope, key, body, requestId);
    }

    @PostMapping("/{id}/start")
    public RoomReservationView startRoomUsage(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return roomReservationService.start(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/{id}/complete")
    public RoomReservationView completeRoomUsage(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return roomReservationService.complete(actorId(actor), id, scope, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
