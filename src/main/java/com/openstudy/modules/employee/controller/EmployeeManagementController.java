package com.openstudy.modules.employee.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.openstudy.common.web.*;
import com.openstudy.modules.employee.dto.ManagedEmployee;
import com.openstudy.modules.employee.dto.ManagedEmployee.MembershipResult;
import com.openstudy.modules.employee.service.EmployeeManagementService;

@RestController
@RequestMapping("/api/employees")
public class EmployeeManagementController {
    private final EmployeeManagementService service;
    public EmployeeManagementController(EmployeeManagementService service) { this.service=service; }
    @GetMapping
    public PageResponse<ManagedEmployee> list(Authentication actor,
            @RequestParam(defaultValue="all") String scope, @RequestParam(defaultValue="1") long page,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String q,
            @RequestParam(required=false) String sort, @RequestParam(required=false) String direction,
            @RequestParam(required=false) String status) {
        return service.list(Long.parseLong(actor.getName()),scope,ListQuery.parse(page,pageSize,q,sort,direction,Set.of("createdAt","name"),"createdAt"),status);
    }
    @GetMapping("/{id}")
    public ManagedEmployee get(Authentication actor,@PathVariable long id,@RequestParam(defaultValue="all") String scope) {
        return service.get(Long.parseLong(actor.getName()),id,scope);
    }
    @PatchMapping("/{id}")
    public ManagedEmployee update(Authentication actor,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return service.update(Long.parseLong(actor.getName()),id,body,ApiRequestFilter.requestId(request));
    }
    @PutMapping("/{employeeId}/memberships/{branchId}")
    public MembershipResult membership(Authentication actor,@PathVariable long employeeId,@PathVariable long branchId,
            @RequestParam(defaultValue="all") String scope,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return service.membership(Long.parseLong(actor.getName()),employeeId,branchId,scope,body,ApiRequestFilter.requestId(request));
    }
}
