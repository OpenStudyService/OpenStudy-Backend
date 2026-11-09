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
import com.openstudy.modules.workforce.dto.response.LeaveBalanceView;
import com.openstudy.modules.workforce.dto.response.LeaveGrantDetailView;
import com.openstudy.modules.workforce.dto.response.LeaveGrantView;
import com.openstudy.modules.workforce.dto.response.LeaveRequestView;
import com.openstudy.modules.workforce.dto.response.WorkforceRequestConflictError;
import com.openstudy.modules.workforce.service.LeaveService;
import com.openstudy.modules.workforce.service.WorkforceRequestRefusal;

/**
 * Leave grants, balances and requests (#108, workforce-requests-api.md). There is no DELETE and no request edit: a
 * request ends by being rejected or cancelled and keeps its row; to change it, cancel it and request again.
 */
@RestController
@RequiredArgsConstructor
public class LeaveController {
    private final LeaveService service;

    @GetMapping("/api/leave-grants")
    public PageResponse<LeaveGrantView> grants(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String grantYear, @RequestParam(required = false) String includeExpired) {
        // The order is fixed by the contract (validThrough, then id), so there is no sort parameter.
        return service.listGrants(actor(actor), scope, query(page, pageSize), employeeId, mine, grantYear, includeExpired);
    }

    @PostMapping("/api/leave-grants")
    public ResponseEntity<LeaveGrantView> grant(Authentication actor, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        var result = service.createGrant(actor(actor), key, body);
        return ResponseEntity.created(URI.create("/api/leave-grants/" + result.id())).body(result);
    }

    @GetMapping("/api/leave-grants/{id}")
    public LeaveGrantDetailView grant(Authentication actor, @PathVariable long id) { return service.getGrant(actor(actor), id); }

    @PostMapping("/api/leave-grants/{id}/adjust")
    public LeaveGrantDetailView adjust(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.adjustGrant(actor(actor), id, body);
    }

    @GetMapping("/api/me/leave-balance")
    public LeaveBalanceView myBalance(Authentication actor) { return service.myBalance(actor(actor)); }

    @GetMapping("/api/employees/{employeeId}/leave-balance")
    public LeaveBalanceView balance(Authentication actor, @PathVariable long employeeId) {
        return service.employeeBalance(actor(actor), employeeId);
    }

    @GetMapping("/api/leave-requests")
    public PageResponse<LeaveRequestView> requests(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // The order is fixed by the contract (startsOn, then id, both descending).
        return service.listRequests(actor(actor), scope, query(page, pageSize), employeeId, mine, status, from, to);
    }

    @PostMapping("/api/leave-requests")
    public ResponseEntity<LeaveRequestView> request(Authentication actor, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body) {
        var result = service.createRequest(actor(actor), key, body);
        return ResponseEntity.created(URI.create("/api/leave-requests/" + result.id())).body(result);
    }

    @GetMapping("/api/leave-requests/{id}")
    public LeaveRequestView request(Authentication actor, @PathVariable long id) { return service.getRequest(actor(actor), id); }

    @PostMapping("/api/leave-requests/{id}/approve")
    public LeaveRequestView approve(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.approve(actor(actor), id, body);
    }

    @PostMapping("/api/leave-requests/{id}/reject")
    public LeaveRequestView reject(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.reject(actor(actor), id, body);
    }

    @PostMapping("/api/leave-requests/{id}/cancel")
    public LeaveRequestView cancel(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancel(actor(actor), id, body);
    }

    @PostMapping("/api/leave-requests/{id}/cancel-approval")
    public LeaveRequestView cancelApproval(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancelApproval(actor(actor), id, body);
    }

    /** The contract's WorkforceRequestConflictError: the common error fields plus the target information of the code. */
    @ExceptionHandler(WorkforceRequestRefusal.class)
    ResponseEntity<WorkforceRequestConflictError> refused(WorkforceRequestRefusal refusal, HttpServletRequest request) {
        return ResponseEntity.status(refusal.status()).body(refusal.error(ApiRequestFilter.requestId(request)));
    }

    private static ListQuery query(long page, int pageSize) {
        return ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id");
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
