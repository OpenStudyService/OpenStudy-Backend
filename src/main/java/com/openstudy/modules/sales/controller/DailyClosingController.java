package com.openstudy.modules.sales.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.sales.dto.response.DailyClosingView;
import com.openstudy.modules.sales.service.DailyClosingService;

/** Daily closings (#115, operations-extension-api.md "일별 마감"). There is no cancellation or reopening. */
@RestController
@RequiredArgsConstructor
public class DailyClosingController {
    private final DailyClosingService service;

    @GetMapping("/api/daily-closings")
    public PageResponse<DailyClosingView.Summary> list(Authentication actor, @RequestParam(required = false) List<String> branchIds,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize) {
        // The order is fixed by the contract (business date descending, then branch).
        return service.list(actor(actor), branchIds, from, to, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"));
    }

    @PostMapping("/api/daily-closings")
    public ResponseEntity<DailyClosingView> create(Authentication actor, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/daily-closings/" + result.id())).body(result);
    }

    @GetMapping("/api/daily-closings/{id}")
    public DailyClosingView get(Authentication actor, @PathVariable long id) { return service.get(actor(actor), id); }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
