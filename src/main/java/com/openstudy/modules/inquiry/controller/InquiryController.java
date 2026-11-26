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
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private static final Set<String> SORTS = Set.of("createdAt", "updatedAt");
    private static final String DEFAULT_SORT = "createdAt";
    private static final Set<String> EVENT_SORTS = Set.of("id");
    private static final String DEFAULT_EVENT_SORT = "id";

    private final InquiryService inquiryService;

    @GetMapping
    public PageResponse<InquiryListItem> listInquiries(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORTS, DEFAULT_SORT);
        InquiryFilters filters = InquiryFilters.parse(status, assigneeId, customerId);

        return inquiryService.list(actorId(actor), scope, query, filters);
    }

    @GetMapping("/summary")
    public InquirySummary getInquirySummary(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String customerId
    ) {
        InquiryFilters filters = InquiryFilters.parse(null, assigneeId, customerId);

        return inquiryService.summary(actorId(actor), scope, q, filters);
    }

    @GetMapping("/{id}")
    public InquiryDetail getInquiry(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return inquiryService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<InquiryDetail> createInquiry(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        InquiryDetail result = inquiryService.create(actorId(actor), scope, key, body);

        return ResponseEntity.created(URI.create("/api/inquiries/" + result.id())).body(result);
    }

    @GetMapping("/{id}/events")
    public PageResponse<InquiryEventView> listInquiryEvents(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, EVENT_SORTS, DEFAULT_EVENT_SORT);

        return inquiryService.listEvents(actorId(actor), id, scope, query);
    }

    @PostMapping("/{id}/assignee")
    public InquiryDetail changeInquiryAssignee(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return inquiryService.changeAssignee(actorId(actor), id, scope, body);
    }

    @PostMapping("/{id}/answer")
    public InquiryDetail answerInquiry(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return inquiryService.answer(actorId(actor), id, scope, body);
    }

    @PostMapping("/{id}/status")
    public InquiryDetail changeInquiryStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return inquiryService.changeStatus(actorId(actor), id, scope, body);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
