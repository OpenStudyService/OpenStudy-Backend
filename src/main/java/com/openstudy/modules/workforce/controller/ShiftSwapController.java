package com.openstudy.modules.workforce.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.workforce.dto.response.ShiftSwapView;
import com.openstudy.modules.workforce.dto.response.WorkforceRequestConflictError;
import com.openstudy.modules.workforce.service.SwapService;
import com.openstudy.modules.workforce.service.WorkforceRequestRefusal;

/** Shift swaps (#109, workforce-requests-api.md "대체근무"). There is no DELETE and no edit: cancel and request again. */
@RestController
@RequiredArgsConstructor
public class ShiftSwapController {
    private final SwapService service;

    @GetMapping("/api/shift-swaps")
    public PageResponse<ShiftSwapView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // The order is fixed by the contract (createdAt, then id, both descending).
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), employeeId, mine,
                status, from, to);
    }

    @PostMapping("/api/shifts/{id}/swaps")
    public ResponseEntity<ShiftSwapView> create(Authentication actor, @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.create(actor(actor), id, key, body);
        return ResponseEntity.created(URI.create("/api/shift-swaps/" + result.id())).body(result);
    }

    @GetMapping("/api/shift-swaps/{id}")
    public ShiftSwapView get(Authentication actor, @PathVariable long id) { return service.get(actor(actor), id); }

    @PostMapping("/api/shift-swaps/{id}/approve")
    public ShiftSwapView approve(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.approve(actor(actor), id, body);
    }

    @PostMapping("/api/shift-swaps/{id}/reject")
    public ShiftSwapView reject(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.reject(actor(actor), id, body);
    }

    @PostMapping("/api/shift-swaps/{id}/cancel")
    public ShiftSwapView cancel(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancel(actor(actor), id, body);
    }

    @PostMapping("/api/shift-swaps/{id}/cancel-approval")
    public ShiftSwapView cancelApproval(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancelApproval(actor(actor), id, body);
    }

    /** The contract's WorkforceRequestConflictError: the common error fields plus the target information of the code. */
    @ExceptionHandler(WorkforceRequestRefusal.class)
    ResponseEntity<WorkforceRequestConflictError> refused(WorkforceRequestRefusal refusal, HttpServletRequest request) {
        return ResponseEntity.status(refusal.status()).body(refusal.error(ApiRequestFilter.requestId(request)));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
