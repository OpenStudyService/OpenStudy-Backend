package com.openstudy.modules.invitation.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.service.ApprovalStatusService;

@RestController
public class ApprovalStatusController {
    private final ApprovalStatusService approvals;
    private final SessionBoundaryPolicy sessions;
    public ApprovalStatusController(ApprovalStatusService approvals, SessionBoundaryPolicy sessions) {
        this.approvals = approvals;
        this.sessions = sessions;
    }

    @GetMapping("/api/invitations/approval-status")
    public ResponseEntity<ApprovalStatus> current(Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(approvals.current(
                Long.parseLong(authentication.getName()), sessions.mode(request.getSession(false))));
    }
}
