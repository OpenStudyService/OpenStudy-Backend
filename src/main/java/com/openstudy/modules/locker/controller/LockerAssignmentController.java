package com.openstudy.modules.locker.controller;

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
import com.openstudy.modules.locker.dto.response.LockerAssignmentView;
import com.openstudy.modules.locker.service.LockerAssignmentService;

/** There is no DELETE or PATCH: an assignment ends by being returned, and it grows only by a paid extension (M6-D03). */
@RestController
@RequestMapping("/api/locker-assignments")
@RequiredArgsConstructor
public class LockerAssignmentController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final LockerAssignmentService lockerAssignmentService;

    @GetMapping
    public PageResponse<LockerAssignmentView> listLockerAssignments(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String lockerId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (latest createdAt first), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return lockerAssignmentService.list(actorId, scope, query, lockerId, customerId, status);
    }

    @GetMapping("/{id}")
    public LockerAssignmentView getLockerAssignment(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return lockerAssignmentService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<LockerAssignmentView> createLockerAssignment(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        LockerAssignmentView result = lockerAssignmentService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/locker-assignments/" + result.id())).body(result);
    }

    @PostMapping("/{id}/extend")
    public LockerAssignmentView extendLockerAssignment(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return lockerAssignmentService.extend(actorId(actor), id, scope, key, body, requestId);
    }

    @PostMapping("/{id}/return")
    public LockerAssignmentView returnLocker(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return lockerAssignmentService.returnLocker(actorId(actor), id, scope, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
