package com.openstudy.modules.home.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.modules.home.dto.response.HomeSummary;
import com.openstudy.modules.home.service.HomeSummaryService;

/** The operations home (aggregation-api.md "운영 홈 요약"). The screen re-reads it on entry and on request (ADR-COM-001). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HomeController {
    private final HomeSummaryService service;

    @GetMapping("/home/summary")
    public HomeSummary summary(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String mine, @RequestParam(required = false) String assigneeId) {
        return service.summary(Long.parseLong(actor.getName()), scope, mine, assigneeId);
    }
}
