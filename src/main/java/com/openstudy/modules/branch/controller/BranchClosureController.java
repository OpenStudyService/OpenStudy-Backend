package com.openstudy.modules.branch.controller;

import java.net.URI;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.branch.dto.response.BranchClosureCreated;
import com.openstudy.modules.branch.dto.response.BranchClosureList;
import com.openstudy.modules.branch.dto.response.BranchClosureView;
import com.openstudy.modules.branch.service.BranchClosureService;

/** Branch closures (#112, operations-extension-api.md "지점 휴무일"). */
@RestController
@RequiredArgsConstructor
public class BranchClosureController {
    private final BranchClosureService service;

    @GetMapping("/api/branches/{id}/closures")
    public BranchClosureList list(Authentication actor, @PathVariable long id, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.list(actor(actor), id, from, to);
    }

    @PostMapping("/api/branches/{id}/closures")
    public ResponseEntity<BranchClosureCreated> create(Authentication actor, @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = service.create(actor(actor), id, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/branches/" + id + "/closures")).body(result);
    }

    @PatchMapping("/api/branch-closures/{id}")
    public BranchClosureView update(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.update(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @DeleteMapping("/api/branch-closures/{id}")
    public ResponseEntity<Void> delete(Authentication actor, @PathVariable long id, @RequestParam(required = false) String version,
            HttpServletRequest request) {
        service.delete(actor(actor), id, version, ApiRequestFilter.requestId(request));
        return ResponseEntity.noContent().build();
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
