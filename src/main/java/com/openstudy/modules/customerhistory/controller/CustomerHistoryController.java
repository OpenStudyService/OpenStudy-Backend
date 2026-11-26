package com.openstudy.modules.customerhistory.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.customerhistory.dto.response.CrossBranchSummary;
import com.openstudy.modules.customerhistory.dto.response.SpaceHistory;
import com.openstudy.modules.customerhistory.service.CrossBranchSummaryService;
import com.openstudy.modules.customerhistory.service.CustomerSpaceHistoryService;

@RestController
@RequestMapping("/api/customers/{id}")
@RequiredArgsConstructor
public class CustomerHistoryController {

    private final CrossBranchSummaryService crossBranchSummaryService;
    private final CustomerSpaceHistoryService customerSpaceHistoryService;

    /** A POST so that the reason stays out of the URL; the answer is a read, so 200. */
    @PostMapping("/cross-branch-summary")
    public CrossBranchSummary getCrossBranchSummary(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return crossBranchSummaryService.summarize(actorId(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @GetMapping("/space-history")
    public SpaceHistory getCustomerSpaceHistory(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return customerSpaceHistoryService.findHistory(actorId(actor), id, scope);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
