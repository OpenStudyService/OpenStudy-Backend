package com.openstudy.modules.ticket.controller;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
import com.openstudy.modules.ticket.dto.IssuedPass;
import com.openstudy.modules.ticket.service.PassAdjustmentService;
import com.openstudy.modules.ticket.service.PassService;

/** Passes are issued by POST /api/payments; refunds, usage and the manual adjustment here are the only later changes. */
@RestController
@RequestMapping("/api/passes")
@RequiredArgsConstructor
public class PassController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "expiresAt");
    private static final String DEFAULT_SORT = "createdAt";

    private final PassService passService;
    private final PassAdjustmentService passAdjustmentService;

    @GetMapping
    public PageResponse<IssuedPass> listPasses(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, null, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return passService.list(actorId, scope, query, customerId, status, kind);
    }

    @GetMapping("/{id}")
    public IssuedPass getPass(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return passService.get(actorId(actor), id, scope);
    }

    @PostMapping("/{id}/adjustments")
    public IssuedPass adjustPass(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return passAdjustmentService.adjust(actorId(actor), id, scope, key, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
