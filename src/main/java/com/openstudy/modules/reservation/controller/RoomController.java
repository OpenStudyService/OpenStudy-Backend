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
    private final RoomService service;

    @GetMapping
    public PageResponse<RoomView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        // The order is fixed by the contract (roomNo, id), so there is no sort parameter.
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), status);
    }

    @GetMapping("/{id}")
    public RoomView get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<RoomView> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/rooms/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public RoomView update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/status")
    public RoomView status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.changeStatus(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
