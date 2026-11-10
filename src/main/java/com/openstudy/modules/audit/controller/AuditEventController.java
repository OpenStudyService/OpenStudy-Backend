package com.openstudy.modules.audit.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.audit.dto.response.AuditEventViews;
import com.openstudy.modules.audit.service.AuditEventSearchService;

/** Audit search and detail (#122, account-collab-api.md "감사 로그 검색·상세"). OWNER only. */
@RestController
@RequiredArgsConstructor
public class AuditEventController {
    private final AuditEventSearchService service;

    /** The whole query map goes to the service, which rejects any parameter the contract does not list. */
    @GetMapping("/api/audit-events")
    public AuditEventViews.Page search(Authentication actor, HttpServletRequest request) {
        return service.search(actor(actor), request.getParameterMap());
    }

    @GetMapping("/api/audit-events/{id}")
    public AuditEventViews.Detail get(Authentication actor, @PathVariable long id, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty())
            throw ApiException.validation(request.getParameterNames().nextElement(), "허용되지 않은 쿼리입니다.");
        return service.get(actor(actor), id);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
