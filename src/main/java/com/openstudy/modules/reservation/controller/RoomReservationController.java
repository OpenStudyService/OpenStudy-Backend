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
    private final RoomReservationService service;

    @GetMapping
    public PageResponse<RoomReservationView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String roomId, @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // The order is fixed by the contract (latest startAt first), so there is no sort parameter.
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), status,
                roomId, customerId, from, to);
    }

    @GetMapping("/calendar")
    public RoomReservationCalendar calendar(Authentication actor, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String roomId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.calendar(actor(actor), branchId, roomId, from, to);
    }

    @GetMapping("/{id}")
    public RoomReservationView get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<RoomReservationView> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/room-reservations/" + result.id())).body(result);
    }

    @PostMapping("/{id}/cancel")
    public RoomReservationView cancel(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.cancel(actor(actor), id, scope, key, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/start")
    public RoomReservationView start(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.start(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/complete")
    public RoomReservationView complete(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.complete(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
