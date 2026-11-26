package com.openstudy.modules.customer.controller;

import java.net.URI;
import java.util.Map;
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
import com.openstudy.modules.customer.repository.CustomerSearchRepository.Sort;
import com.openstudy.modules.customer.service.CustomerService;

/** There is deliberately no DELETE mapping: customers are deactivated, never removed (FR-MEM-06). */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private static final String DEFAULT_SORT = "registeredAt";

    private final CustomerService customerService;

    @GetMapping
    public PageResponse<CustomerListItem> listCustomers(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, Sort.keys(), DEFAULT_SORT);

        return customerService.list(actorId(actor), scope, query, status);
    }

    @GetMapping("/{id}")
    public CustomerDetail getCustomer(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return customerService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<CustomerDetail> createCustomer(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        CustomerDetail result = customerService.create(
                actorId(actor),
                scope,
                key,
                body,
                ApiRequestFilter.requestId(request)
        );

        return ResponseEntity.created(URI.create("/api/customers/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public CustomerDetail updateCustomer(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return customerService.update(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/status")
    public CustomerDetail changeCustomerStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return customerService.changeStatus(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
