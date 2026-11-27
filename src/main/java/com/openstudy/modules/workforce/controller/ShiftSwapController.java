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
import com.openstudy.modules.workforce.dto.response.ShiftSwapView;
import com.openstudy.modules.workforce.dto.response.WorkforceRequestConflictError;
import com.openstudy.modules.workforce.service.SwapService;
import com.openstudy.modules.workforce.service.WorkforceRequestRefusal;

/** Shift swaps (#109, workforce-requests-api.md "대체근무"). There is no DELETE and no edit: cancel and request again. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShiftSwapController {

    private static final String SWAP_LOCATION = "/api/shift-swaps/";
    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final SwapService swapService;

    @GetMapping("/shift-swaps")
    public PageResponse<ShiftSwapView> listShiftSwaps(
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
        // The order is fixed by the contract (createdAt, then id, both descending).
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return swapService.list(actorId(actor), scope, query, employeeId, mine, status, from, to);
    }

    @PostMapping("/shifts/{id}/swaps")
    public ResponseEntity<ShiftSwapView> createShiftSwap(
            Authentication actor,
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        ShiftSwapView result = swapService.create(actorId(actor), id, key, body);

        return ResponseEntity.created(URI.create(SWAP_LOCATION + result.id()))
                .body(result);
    }

    @GetMapping("/shift-swaps/{id}")
    public ShiftSwapView getShiftSwap(Authentication actor, @PathVariable long id) {
        return swapService.get(actorId(actor), id);
    }

    @PostMapping("/shift-swaps/{id}/approve")
    public ShiftSwapView approveShiftSwap(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return swapService.approve(actorId(actor), id, body);
    }

    @PostMapping("/shift-swaps/{id}/reject")
    public ShiftSwapView rejectShiftSwap(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return swapService.reject(actorId(actor), id, body);
    }

    @PostMapping("/shift-swaps/{id}/cancel")
    public ShiftSwapView cancelShiftSwap(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return swapService.cancel(actorId(actor), id, body);
    }

    @PostMapping("/shift-swaps/{id}/cancel-approval")
    public ShiftSwapView cancelShiftSwapApproval(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return swapService.cancelApproval(actorId(actor), id, body);
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

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
