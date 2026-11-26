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
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api")
@RequiredArgsConstructor
public class CustomerMergeController {

    /** The order is fixed by the contract (group size, then phone). */
    private static final Set<String> SORT_FIELDS = Set.of("phone");
    private static final String DEFAULT_SORT = "phone";

    private final CustomerMergeService customerMergeService;

    @GetMapping("/customer-duplicates")
    public PageResponse<CustomerDuplicateGroup> listCustomerDuplicates(
            Authentication actor,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return customerMergeService.findDuplicates(actorId(actor), query);
    }

    @PostMapping("/customers/{id}/merge")
    public CustomerDetail mergeCustomer(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return customerMergeService.merge(actorId(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @GetMapping("/customers/{id}/merges")
    public CustomerMergeList listCustomerMerges(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return new CustomerMergeList(customerMergeService.findHistory(actorId(actor), id, scope));
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
