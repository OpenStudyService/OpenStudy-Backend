package com.openstudy.modules.facility.controller;

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
import com.openstudy.modules.facility.dto.response.FacilityDetail;
import com.openstudy.modules.facility.dto.response.FacilityListItem;
import com.openstudy.modules.facility.dto.response.FacilitySummary;
import com.openstudy.modules.facility.dto.response.FacilityTypeList;
import com.openstudy.modules.facility.dto.response.FacilityTypeView;
import com.openstudy.modules.facility.service.FacilityFilters;
import com.openstudy.modules.facility.service.FacilityService;
import com.openstudy.modules.facility.service.FacilityTypeService;

/** There is deliberately no DELETE for a facility or a facility type (collaboration-api.md). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FacilityController {
    private static final Set<String> SORTS = Set.of("nextInspectionOn", "name", "createdAt");
    private final FacilityService service;
    private final FacilityTypeService typeService;

    @GetMapping("/facility-types")
    public FacilityTypeList types(Authentication actor) {
        return typeService.list(actor(actor));
    }

    @PostMapping("/facility-types")
    public ResponseEntity<FacilityTypeView> createType(Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = typeService.create(actor(actor), key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/facility-types")).body(result);
    }

    @GetMapping("/facilities")
    public PageResponse<FacilityListItem> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String typeId,
            @RequestParam(required = false) String assigneeId, @RequestParam(required = false) String workStatus,
            @RequestParam(required = false) String inspectionDue, @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction) {
        // The contract's default is the soonest due first; a creation-time list reads newest first like the other lists.
        String order = direction != null ? direction : "createdAt".equals(sort) ? "desc" : "asc";
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, order, SORTS, "nextInspectionOn"),
                FacilityFilters.parse(typeId, assigneeId, workStatus, inspectionDue, status));
    }

    @GetMapping("/facilities/summary")
    public FacilitySummary summary(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q, @RequestParam(required = false) String typeId,
            @RequestParam(required = false) String assigneeId, @RequestParam(required = false) String workStatus,
            @RequestParam(required = false) String inspectionDue) {
        return service.summary(actor(actor), scope, q, FacilityFilters.parse(typeId, assigneeId, workStatus, inspectionDue, null));
    }

    @GetMapping("/facilities/{id}")
    public FacilityDetail get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping("/facilities")
    public ResponseEntity<FacilityDetail> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/facilities/" + result.id())).body(result);
    }

    @PatchMapping("/facilities/{id}")
    public FacilityDetail update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/facilities/{id}/inspection/complete")
    public FacilityDetail complete(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.completeInspection(actor(actor), id, scope, key, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
