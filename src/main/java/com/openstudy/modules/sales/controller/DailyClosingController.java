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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.sales.dto.response.DailyClosingView;
import com.openstudy.modules.sales.service.DailyClosingService;

/** Daily closings (#115, operations-extension-api.md "일별 마감"). There is no cancellation or reopening. */
@RestController
@RequestMapping("/api/daily-closings")
@RequiredArgsConstructor
public class DailyClosingController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final DailyClosingService dailyClosingService;

    @GetMapping
    public PageResponse<DailyClosingView.Summary> listDailyClosings(
            Authentication actor,
            @RequestParam(required = false) List<String> branchIds,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (business date descending, then branch).
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return dailyClosingService.list(actorId, branchIds, from, to, query);
    }

    @PostMapping
    public ResponseEntity<DailyClosingView> createDailyClosing(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        DailyClosingView result = dailyClosingService.create(actorId(actor), key, body, requestId);

        return ResponseEntity.created(URI.create("/api/daily-closings/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public DailyClosingView getDailyClosing(Authentication actor, @PathVariable long id) {
        return dailyClosingService.get(actorId(actor), id);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
