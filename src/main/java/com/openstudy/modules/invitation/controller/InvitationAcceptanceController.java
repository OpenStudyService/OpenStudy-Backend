package com.openstudy.modules.invitation.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvitationAcceptanceController {

    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String NO_REFERRER = "no-referrer";

    private final InvitationAcceptanceService invitationAcceptanceService;
    private final SessionBoundaryPolicy sessionBoundaryPolicy;

    @PostMapping("/invitations/preview")
    public ResponseEntity<InvitationPreview> previewInvitation(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY, NO_REFERRER)
                .body(invitationAcceptanceService.preview(body));
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<AcceptanceResult> acceptInvitation(
            Authentication authentication,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        boolean signedIn = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        HttpSession session = request.getSession(false);
        Long sessionEmployee = signedIn ? Long.parseLong(authentication.getName()) : null;
        String sessionMode = signedIn && session != null ? sessionBoundaryPolicy.mode(session) : null;

        AcceptanceResult result = invitationAcceptanceService.accept(
                body,
                sessionEmployee,
                sessionMode,
                ApiRequestFilter.requestId(request)
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY, NO_REFERRER)
                .body(result);
    }
}
