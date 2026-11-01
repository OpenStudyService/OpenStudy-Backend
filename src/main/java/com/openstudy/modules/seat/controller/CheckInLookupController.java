package com.openstudy.modules.seat.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.seat.dto.response.CheckInLookupResult;
import com.openstudy.modules.seat.service.CheckInLookupService;

/** POST, so a phone number never lands in a URL or an access log (seat-usage-api.md). */
@RestController
@RequiredArgsConstructor
public class CheckInLookupController {
    private final CheckInLookupService service;

    @PostMapping("/api/customer-lookups/check-in")
    public CheckInLookupResult lookup(Authentication actor, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.lookup(Long.parseLong(actor.getName()), body, ApiRequestFilter.requestId(request));
    }
}
