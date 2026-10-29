package com.openstudy.modules.customer.controller;

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
import com.openstudy.modules.customer.dto.response.CustomerDetail;
import com.openstudy.modules.customer.dto.response.CustomerListItem;
import com.openstudy.modules.customer.service.CustomerService;

/** There is deliberately no DELETE mapping: customers are deactivated, never removed (FR-MEM-06). */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService service;

    @GetMapping
    public PageResponse<CustomerListItem> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String status) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, direction,
                Set.of("registeredAt", "name"), "registeredAt"), status);
    }

    @GetMapping("/{id}")
    public CustomerDetail get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<CustomerDetail> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/customers/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public CustomerDetail update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/status")
    public CustomerDetail status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.changeStatus(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
