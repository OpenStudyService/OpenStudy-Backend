package com.openstudy.modules.ticket.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.ticket.dto.Product;
import com.openstudy.modules.ticket.dto.ProductRevision;
import com.openstudy.modules.ticket.service.ProductService;

/** There is deliberately no DELETE mapping: products are stopped and resumed, never physically removed. */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }

    @GetMapping
    public PageResponse<Product> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String categoryId) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, direction,
                Set.of("createdAt", "name", "priceKrw"), "createdAt"), status, kind, categoryId);
    }

    @GetMapping("/{id}")
    public Product get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<Product> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/products/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public Product update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/status")
    public Product status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.changeStatus(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PutMapping("/{id}/branches")
    public Product branches(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.replaceBranches(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @GetMapping("/{id}/history")
    public PageResponse<ProductRevision> history(Authentication actor, @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction) {
        return service.history(actor(actor), id, scope, ListQuery.parse(page, pageSize, q, sort, direction,
                Set.of("changedAt"), "changedAt"));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
