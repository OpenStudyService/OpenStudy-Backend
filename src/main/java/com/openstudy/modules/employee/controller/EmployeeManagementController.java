package com.openstudy.modules.employee.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.openstudy.common.web.*;
import com.openstudy.modules.employee.dto.ManagedEmployee;
import com.openstudy.modules.employee.dto.ManagedEmployee.MembershipResult;
import com.openstudy.modules.employee.service.EmployeeManagementService;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeManagementController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name");
    private static final String DEFAULT_SORT = "createdAt";

    private final EmployeeManagementService employeeManagementService;

    @GetMapping
    public PageResponse<ManagedEmployee> listEmployees(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return employeeManagementService.list(actorId(actor), scope, query, status);
    }

    @GetMapping("/{id}")
    public ManagedEmployee getEmployee(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return employeeManagementService.get(actorId(actor), id, scope);
    }

    @PatchMapping("/{id}")
    public ManagedEmployee updateEmployee(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return employeeManagementService.update(actorId(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @PutMapping("/{employeeId}/memberships/{branchId}")
    public MembershipResult updateMembership(
            Authentication actor,
            @PathVariable long employeeId,
            @PathVariable long branchId,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return employeeManagementService.updateMembership(
                actorId(actor),
                employeeId,
                branchId,
                scope,
                body,
                ApiRequestFilter.requestId(request)
        );
    }

    private static long actorId(Authentication actor) {
        return Long.parseLong(actor.getName());
    }
}
