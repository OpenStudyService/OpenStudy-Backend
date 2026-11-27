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

/**
 * There is deliberately no DELETE for a facility or a facility type (collaboration-api.md). Facilities and facility
 * types are two top-level paths, so the class maps only /api.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FacilityController {

    private static final Set<String> SORT_FIELDS = Set.of("nextInspectionOn", "name", "createdAt");
    private static final String DEFAULT_SORT = "nextInspectionOn";
    private static final String CREATED_AT = "createdAt";
    private static final String ASCENDING = "asc";
    private static final String DESCENDING = "desc";

    private final FacilityService facilityService;
    private final FacilityTypeService facilityTypeService;

    @GetMapping("/facility-types")
    public FacilityTypeList listFacilityTypes(Authentication actor) {
        return facilityTypeService.list(actorId(actor));
    }

    @PostMapping("/facility-types")
    public ResponseEntity<FacilityTypeView> createFacilityType(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        FacilityTypeView result = facilityTypeService.create(actorId(actor), key, body, requestId);

        return ResponseEntity.created(URI.create("/api/facility-types")).body(result);
    }

    @GetMapping("/facilities")
    public PageResponse<FacilityListItem> listFacilities(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String typeId,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String workStatus,
            @RequestParam(required = false) String inspectionDue,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        String order = toDirection(sort, direction);
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, order, SORT_FIELDS, DEFAULT_SORT);
        FacilityFilters filters = FacilityFilters.parse(typeId, assigneeId, workStatus, inspectionDue, status);

        return facilityService.list(actorId, scope, query, filters);
    }

    @GetMapping("/facilities/summary")
    public FacilitySummary getFacilitySummary(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String typeId,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String workStatus,
            @RequestParam(required = false) String inspectionDue
    ) {
        long actorId = actorId(actor);
        FacilityFilters filters = FacilityFilters.parse(typeId, assigneeId, workStatus, inspectionDue, null);

        return facilityService.summary(actorId, scope, q, filters);
    }

    @GetMapping("/facilities/{id}")
    public FacilityDetail getFacility(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return facilityService.get(actorId(actor), id, scope);
    }

    @PostMapping("/facilities")
    public ResponseEntity<FacilityDetail> createFacility(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        FacilityDetail result = facilityService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/facilities/" + result.id())).body(result);
    }

    @PatchMapping("/facilities/{id}")
    public FacilityDetail updateFacility(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return facilityService.update(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/facilities/{id}/inspection/complete")
    public FacilityDetail completeFacilityInspection(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return facilityService.completeInspection(actorId(actor), id, scope, key, body, requestId);
    }

    /** The contract's default is the soonest due first; a creation-time list reads newest first like the other lists. */
    private static String toDirection(String sort, String direction) {
        if (direction != null) {
            return direction;
        }

        return CREATED_AT.equals(sort) ? DESCENDING : ASCENDING;
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
