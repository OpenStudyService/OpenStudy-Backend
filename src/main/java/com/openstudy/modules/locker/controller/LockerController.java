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

/**
 * There is deliberately no DELETE for a locker: a locker is closed by stopping its operation (space-operations-api.md).
 * The branch rate lives under /branches, so the class maps only /api.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LockerController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final LockerService lockerService;

    @GetMapping("/lockers")
    public PageResponse<LockerView> listLockers(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String assignmentState
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (lockerNo, id), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return lockerService.list(actorId, scope, query, assignmentState);
    }

    @GetMapping("/lockers/occupancy")
    public LockerOccupancy getLockerOccupancy(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return lockerService.occupancy(actorId(actor), scope);
    }

    @GetMapping("/lockers/{id}")
    public LockerView getLocker(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return lockerService.get(actorId(actor), id, scope);
    }

    @PostMapping("/lockers")
    public ResponseEntity<LockerView> createLocker(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        LockerView result = lockerService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/lockers/" + result.id())).body(result);
    }

    @PatchMapping("/lockers/{id}")
    public LockerView updateLocker(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return lockerService.update(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/lockers/{id}/status")
    public LockerView changeLockerStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return lockerService.changeStatus(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/branches/{id}/locker-rate")
    public LockerRateResult applyBranchLockerRate(
            Authentication actor,
            @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return lockerService.applyRate(actorId(actor), id, key, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
