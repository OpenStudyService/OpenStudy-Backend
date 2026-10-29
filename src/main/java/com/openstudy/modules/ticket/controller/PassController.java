package com.openstudy.modules.ticket.controller;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.ticket.dto.IssuedPass;
import com.openstudy.modules.ticket.service.PassService;

/** Read-only: passes are issued by POST /api/payments and changed only by refunds (#58) and usage (M5). */
@RestController
@RequestMapping("/api/passes")
@RequiredArgsConstructor
public class PassController {
    private final PassService service;

    @GetMapping
    public PageResponse<IssuedPass> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction,
            @RequestParam(required = false) String customerId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, sort, direction,
                Set.of("createdAt", "expiresAt"), "createdAt"), customerId, status, kind);
    }

    @GetMapping("/{id}")
    public IssuedPass get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
