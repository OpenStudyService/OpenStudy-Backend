package com.openstudy.modules.inquiry.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.inquiry.dto.response.InquiryDetail;
import com.openstudy.modules.inquiry.dto.response.InquiryEventView;
import com.openstudy.modules.inquiry.dto.response.InquiryListItem;
import com.openstudy.modules.inquiry.dto.response.InquirySummary;
import com.openstudy.modules.inquiry.service.InquiryFilters;
import com.openstudy.modules.inquiry.service.InquiryService;

/** There is deliberately no DELETE and no edit of the subject or body of an inquiry (collaboration-api.md). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InquiryController {
    private static final Set<String> SORTS = Set.of("createdAt", "updatedAt");
    private final InquiryService service;

    @GetMapping("/inquiries")
    public PageResponse<InquiryListItem> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigneeId, @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, direction, SORTS, "createdAt"),
                InquiryFilters.parse(status, assigneeId, customerId));
    }

    @GetMapping("/inquiries/summary")
    public InquirySummary summary(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String customerId) {
        return service.summary(actor(actor), scope, q, InquiryFilters.parse(null, assigneeId, customerId));
    }

    @GetMapping("/inquiries/{id}")
    public InquiryDetail get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping("/inquiries")
    public ResponseEntity<InquiryDetail> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.create(actor(actor), scope, key, body);
        return ResponseEntity.created(URI.create("/api/inquiries/" + result.id())).body(result);
    }

    @GetMapping("/inquiries/{id}/events")
    public PageResponse<InquiryEventView> events(Authentication actor, @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.history(actor(actor), id, scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"));
    }

    @PostMapping("/inquiries/{id}/assignee")
    public InquiryDetail assignee(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.changeAssignee(actor(actor), id, scope, body);
    }

    @PostMapping("/inquiries/{id}/answer")
    public InquiryDetail answer(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.answer(actor(actor), id, scope, body);
    }

    @PostMapping("/inquiries/{id}/status")
    public InquiryDetail status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.changeStatus(actor(actor), id, scope, body);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
