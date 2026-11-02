package com.openstudy.modules.locker.controller;

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
import com.openstudy.modules.locker.dto.response.LockerOccupancy;
import com.openstudy.modules.locker.dto.response.LockerRateResult;
import com.openstudy.modules.locker.dto.response.LockerView;
import com.openstudy.modules.locker.service.LockerService;

/** There is deliberately no DELETE for a locker: a locker is closed by stopping its operation (space-operations-api.md). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LockerController {
    private final LockerService service;

    @GetMapping("/lockers")
    public PageResponse<LockerView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String assignmentState) {
        // The order is fixed by the contract (lockerNo, id), so there is no sort parameter.
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), assignmentState);
    }

    @GetMapping("/lockers/occupancy")
    public LockerOccupancy occupancy(Authentication actor, @RequestParam(defaultValue = "all") String scope) {
        return service.occupancy(actor(actor), scope);
    }

    @GetMapping("/lockers/{id}")
    public LockerView get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping("/lockers")
    public ResponseEntity<LockerView> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/lockers/" + result.id())).body(result);
    }

    @PatchMapping("/lockers/{id}")
    public LockerView update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/lockers/{id}/status")
    public LockerView status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.changeStatus(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/branches/{id}/locker-rate")
    public LockerRateResult applyRate(Authentication actor, @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.applyRate(actor(actor), id, key, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
