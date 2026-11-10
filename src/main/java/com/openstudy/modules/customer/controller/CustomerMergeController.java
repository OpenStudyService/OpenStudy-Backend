package com.openstudy.modules.customer.controller;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.customer.dto.response.CustomerDetail;
import com.openstudy.modules.customer.dto.response.CustomerDuplicateGroup;
import com.openstudy.modules.customer.dto.response.CustomerMergeList;
import com.openstudy.modules.customer.service.CustomerMergeService;

/** Duplicate customers and their merge (#114, operations-extension-api.md "중복 고객 확인·병합"). There is no undo. */
@RestController
@RequiredArgsConstructor
public class CustomerMergeController {
    private final CustomerMergeService service;

    @GetMapping("/api/customer-duplicates")
    public PageResponse<CustomerDuplicateGroup> duplicates(Authentication actor, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // The order is fixed by the contract (group size, then phone).
        return service.duplicates(actor(actor), ListQuery.parse(page, pageSize, null, null, null, Set.of("phone"), "phone"));
    }

    @PostMapping("/api/customers/{id}/merge")
    public CustomerDetail merge(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.merge(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @GetMapping("/api/customers/{id}/merges")
    public CustomerMergeList merges(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return new CustomerMergeList(service.history(actor(actor), id, scope));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
