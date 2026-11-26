package com.openstudy.modules.notice.controller;

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
import com.openstudy.modules.notice.dto.response.NoticeDetail;
import com.openstudy.modules.notice.dto.response.NoticeListItem;
import com.openstudy.modules.notice.service.NoticeService;

/** There is deliberately no DELETE and no read receipt for a notice (collaboration-api.md). The list order is fixed. */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final NoticeService noticeService;

    @GetMapping
    public PageResponse<NoticeListItem> listNotices(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, null, null, SORT_FIELDS, DEFAULT_SORT);

        return noticeService.list(actorId(actor), scope, query, status);
    }

    @PostMapping
    public ResponseEntity<NoticeDetail> createNotice(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        NoticeDetail result = noticeService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/notices/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public NoticeDetail getNotice(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return noticeService.get(actorId(actor), id, scope);
    }

    @PatchMapping("/{id}")
    public NoticeDetail updateNotice(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return noticeService.update(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/status")
    public NoticeDetail changeNoticeStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return noticeService.changeStatus(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
