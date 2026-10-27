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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.service.ProductCategoryService;

@RestController
@RequestMapping("/api/product-categories")
public class ProductCategoryController {
    private final ProductCategoryService service;
    public ProductCategoryController(ProductCategoryService service) { this.service = service; }

    @GetMapping
    public PageResponse<ProductCategory> list(Authentication actor, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String status) {
        return service.list(actor(actor), ListQuery.parse(page, pageSize, q, sort, direction,
                Set.of("createdAt", "name", "code"), "createdAt"), kind, status);
    }

    @PostMapping
    public ResponseEntity<ProductCategory> create(Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/product-categories/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public ProductCategory update(Authentication actor, @PathVariable long id,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
