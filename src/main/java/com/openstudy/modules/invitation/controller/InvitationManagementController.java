package com.openstudy.modules.invitation.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
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
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.service.InvitationManagementService;

@RestController
@RequestMapping("/api/invitations")
public class InvitationManagementController {
    private final InvitationManagementService service;
    public InvitationManagementController(InvitationManagementService service) { this.service = service; }

    @GetMapping
    public PageResponse<Invitation> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String status) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, direction,
                Set.of("createdAt", "expiresAt", "name"), "createdAt"), status);
    }

    @GetMapping("/{id}")
    public Invitation get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<Invitation> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(actor(actor), scope, body, key, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/invitations/" + result.id())).body(result);
    }

    @PostMapping("/{id}/reissue")
    public Invitation reissue(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.reissue(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/cancel")
    public Invitation cancel(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.cancel(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/reject")
    public Invitation reject(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.reject(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/approve")
    public Invitation approve(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.approve(actor(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
