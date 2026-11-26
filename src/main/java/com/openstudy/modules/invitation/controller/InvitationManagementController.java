package com.openstudy.modules.invitation.controller;

import java.net.URI;
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
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.service.InvitationManagementService;

@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
public class InvitationManagementController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "expiresAt", "name");
    private static final String DEFAULT_SORT = "createdAt";

    private final InvitationManagementService invitationManagementService;

    @GetMapping
    public PageResponse<Invitation> listInvitations(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return invitationManagementService.list(actorId(actor), scope, query, status);
    }

    @GetMapping("/{id}")
    public Invitation getInvitation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return invitationManagementService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<Invitation> createInvitation(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        Invitation result = invitationManagementService.create(
                actorId(actor),
                scope,
                body,
                key,
                ApiRequestFilter.requestId(request)
        );

        return ResponseEntity.created(URI.create("/api/invitations/" + result.id())).body(result);
    }

    @PostMapping("/{id}/reissue")
    public Invitation reissueInvitation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return invitationManagementService.reissue(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/{id}/cancel")
    public Invitation cancelInvitation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return invitationManagementService.cancel(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/reject")
    public Invitation rejectInvitation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return invitationManagementService.reject(actorId(actor), id, scope, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/approve")
    public Invitation approveInvitation(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return invitationManagementService.approve(actorId(actor), id, scope, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
