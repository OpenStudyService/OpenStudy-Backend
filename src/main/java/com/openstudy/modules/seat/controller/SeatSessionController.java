package com.openstudy.modules.seat.controller;

import java.net.URI;
import java.util.Map;
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
import com.openstudy.modules.seat.dto.response.SeatSessionView;
import com.openstudy.modules.seat.service.SeatSessionService;

/** Staff check-in and check-out. There is no DELETE: a session stays as history (seat-usage-api.md). */
@RestController
@RequestMapping("/api/seat-sessions")
@RequiredArgsConstructor
public class SeatSessionController {
    private final SeatSessionService service;

    @PostMapping
    public ResponseEntity<SeatSessionView> checkIn(Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.checkIn(actor(actor), key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/seat-sessions/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public SeatSessionView get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping("/{id}/check-out")
    public SeatSessionView checkOut(Authentication actor, @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.checkOut(actor(actor), id, key, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
