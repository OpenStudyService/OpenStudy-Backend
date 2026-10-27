package com.openstudy.modules.invitation.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.invitation.dto.AcceptanceResult;
import com.openstudy.modules.invitation.dto.InvitationPreview;
import com.openstudy.modules.invitation.service.InvitationAcceptanceService;

/** Public link endpoints. The token travels in the JSON body only, never in a URL or Referer. */
@RestController
@RequestMapping("/api/invitations")
public class InvitationAcceptanceController {
    private final InvitationAcceptanceService service;
    private final SessionBoundaryPolicy sessions;
    public InvitationAcceptanceController(InvitationAcceptanceService service, SessionBoundaryPolicy sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping("/preview")
    public ResponseEntity<InvitationPreview> preview(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(service.preview(body));
    }

    @PostMapping("/accept")
    public ResponseEntity<AcceptanceResult> accept(Authentication authentication, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        var session = request.getSession(false);
        var result = service.accept(body, signedIn ? Long.parseLong(authentication.getName()) : null,
                signedIn && session != null ? sessions.mode(session) : null, ApiRequestFilter.requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(result);
    }
}
