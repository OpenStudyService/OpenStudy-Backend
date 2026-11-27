package com.openstudy.modules.ticket.controller;

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
@RequiredArgsConstructor
public class ProductController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name", "priceKrw");
    private static final String DEFAULT_SORT = "createdAt";
    private static final Set<String> HISTORY_SORT_FIELDS = Set.of("changedAt");
    private static final String HISTORY_DEFAULT_SORT = "changedAt";

    private final ProductService productService;

    @GetMapping
    public PageResponse<Product> listProducts(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String availability
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return productService.list(actorId, scope, query, status, kind, categoryId, availability);
    }

    @GetMapping("/{id}")
    public Product getProduct(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return productService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        Product result = productService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/products/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public Product updateProduct(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return productService.update(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/{id}/status")
    public Product changeProductStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return productService.changeStatus(actorId(actor), id, scope, body, requestId);
    }

    @PutMapping("/{id}/branches")
    public Product replaceProductBranches(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return productService.replaceBranches(actorId(actor), id, scope, body, requestId);
    }

    @GetMapping("/{id}/history")
    public PageResponse<ProductRevision> listProductHistory(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(
                page,
                pageSize,
                q,
                sort,
                direction,
                HISTORY_SORT_FIELDS,
                HISTORY_DEFAULT_SORT
        );

        return productService.history(actorId, id, scope, query);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
