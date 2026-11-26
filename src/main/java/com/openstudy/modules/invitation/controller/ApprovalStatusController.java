package com.openstudy.modules.invitation.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.service.ApprovalStatusService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApprovalStatusController {

    private final ApprovalStatusService approvalStatusService;
    private final SessionBoundaryPolicy sessionBoundaryPolicy;

    @GetMapping("/invitations/approval-status")
    public ResponseEntity<ApprovalStatus> getApprovalStatus(Authentication authentication, HttpServletRequest request) {
        long employeeId = Long.parseLong(authentication.getName());
        String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(approvalStatusService.current(employeeId, sessionMode));
    }
}
