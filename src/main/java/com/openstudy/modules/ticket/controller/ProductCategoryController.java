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
@RequiredArgsConstructor
public class ProductCategoryController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name", "code");
    private static final String DEFAULT_SORT = "createdAt";

    private final ProductCategoryService productCategoryService;

    @GetMapping
    public PageResponse<ProductCategory> listProductCategories(
            Authentication actor,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return productCategoryService.list(actorId, query, kind, status);
    }

    @PostMapping
    public ResponseEntity<ProductCategory> createProductCategory(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        ProductCategory result = productCategoryService.create(actorId(actor), key, body, requestId);

        return ResponseEntity.created(URI.create("/api/product-categories/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public ProductCategory updateProductCategory(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return productCategoryService.update(actorId(actor), id, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
