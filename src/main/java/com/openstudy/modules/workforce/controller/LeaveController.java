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
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api")
@RequiredArgsConstructor
public class LeaveController {

    private static final String GRANT_LOCATION = "/api/leave-grants/";
    private static final String REQUEST_LOCATION = "/api/leave-requests/";
    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final LeaveService leaveService;

    @GetMapping("/leave-grants")
    public PageResponse<LeaveGrantView> listLeaveGrants(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String grantYear,
            @RequestParam(required = false) String includeExpired
    ) {
        // The order is fixed by the contract (validThrough, then id), so there is no sort parameter.
        return leaveService.listGrants(
                actorId(actor),
                scope,
                parseQuery(page, pageSize),
                employeeId,
                mine,
                grantYear,
                includeExpired
        );
    }

    @PostMapping("/leave-grants")
    public ResponseEntity<LeaveGrantView> createLeaveGrant(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        LeaveGrantView result = leaveService.createGrant(actorId(actor), key, body);

        return ResponseEntity.created(URI.create(GRANT_LOCATION + result.id()))
                .body(result);
    }

    @GetMapping("/leave-grants/{id}")
    public LeaveGrantDetailView getLeaveGrant(Authentication actor, @PathVariable long id) {
        return leaveService.getGrant(actorId(actor), id);
    }

    @PostMapping("/leave-grants/{id}/adjust")
    public LeaveGrantDetailView adjustLeaveGrant(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return leaveService.adjustGrant(actorId(actor), id, body);
    }

    @GetMapping("/me/leave-balance")
    public LeaveBalanceView getMyLeaveBalance(Authentication actor) {
        return leaveService.myBalance(actorId(actor));
    }

    @GetMapping("/employees/{employeeId}/leave-balance")
    public LeaveBalanceView getEmployeeLeaveBalance(Authentication actor, @PathVariable long employeeId) {
        return leaveService.employeeBalance(actorId(actor), employeeId);
    }

    @GetMapping("/leave-requests")
    public PageResponse<LeaveRequestView> listLeaveRequests(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        // The order is fixed by the contract (startsOn, then id, both descending).
        return leaveService.listRequests(
                actorId(actor),
                scope,
                parseQuery(page, pageSize),
                employeeId,
                mine,
                status,
                from,
                to
        );
    }

    @PostMapping("/leave-requests")
    public ResponseEntity<LeaveRequestView> createLeaveRequest(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        LeaveRequestView result = leaveService.createRequest(actorId(actor), key, body);

        return ResponseEntity.created(URI.create(REQUEST_LOCATION + result.id()))
                .body(result);
    }

    @GetMapping("/leave-requests/{id}")
    public LeaveRequestView getLeaveRequest(Authentication actor, @PathVariable long id) {
        return leaveService.getRequest(actorId(actor), id);
    }

    @PostMapping("/leave-requests/{id}/approve")
    public LeaveRequestView approveLeaveRequest(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return leaveService.approve(actorId(actor), id, body);
    }

    @PostMapping("/leave-requests/{id}/reject")
    public LeaveRequestView rejectLeaveRequest(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return leaveService.reject(actorId(actor), id, body);
    }

    @PostMapping("/leave-requests/{id}/cancel")
    public LeaveRequestView cancelLeaveRequest(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return leaveService.cancel(actorId(actor), id, body);
    }

    @PostMapping("/leave-requests/{id}/cancel-approval")
    public LeaveRequestView cancelLeaveApproval(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return leaveService.cancelApproval(actorId(actor), id, body);
    }

    /** The contract's WorkforceRequestConflictError: the common error fields plus the target information of the code. */
    @ExceptionHandler(WorkforceRequestRefusal.class)
    ResponseEntity<WorkforceRequestConflictError> handleRefusal(
            WorkforceRequestRefusal refusal,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(refusal.status())
                .body(refusal.error(ApiRequestFilter.requestId(request)));
    }

    private static ListQuery parseQuery(long page, int pageSize) {
        return ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
