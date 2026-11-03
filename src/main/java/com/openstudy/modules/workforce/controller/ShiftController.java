package com.openstudy.modules.workforce.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import com.openstudy.modules.workforce.dto.response.ShiftConflictError;
import com.openstudy.modules.workforce.dto.response.ShiftView;
import com.openstudy.modules.workforce.dto.response.WeeklyShiftSchedule;
import com.openstudy.modules.workforce.service.ShiftConflictException;
import com.openstudy.modules.workforce.service.ShiftService;

/** There is no DELETE: a shift ends by being cancelled and keeps its row and history (FR-SHIFT-04). */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {
    private final ShiftService service;

    @GetMapping
    public PageResponse<ShiftView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // The order is fixed by the contract (startsAt, then id), so there is no sort parameter.
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), employeeId, mine,
                status, from, to);
    }

    @GetMapping("/weekly")
    public WeeklyShiftSchedule weekly(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String weekOf, @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String mine) {
        return service.weekly(actor(actor), scope, weekOf, employeeId, mine);
    }

    @GetMapping("/{id}")
    public ShiftView get(Authentication actor, @PathVariable long id) {
        return service.get(actor(actor), id);
    }

    @PostMapping
    public ResponseEntity<ShiftView> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.create(actor(actor), scope, key, body);
        return ResponseEntity.created(URI.create("/api/shifts/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public ShiftView update(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.update(actor(actor), id, body);
    }

    @PostMapping("/{id}/cancel")
    public ShiftView cancel(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancel(actor(actor), id, body);
    }

    /** The contract's ShiftConflictError: the common error fields plus what is taken, only for SHIFT_CONFLICT. */
    @ExceptionHandler(ShiftConflictException.class)
    ResponseEntity<ShiftConflictError> conflict(ShiftConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ShiftConflictError(ShiftConflictException.CODE, exception.getMessage(),
                ApiRequestFilter.requestId(request), exception.conflicts()));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
