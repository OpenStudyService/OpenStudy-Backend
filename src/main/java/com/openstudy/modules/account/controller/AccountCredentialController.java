package com.openstudy.modules.account.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ClientAddressResolver;
import com.openstudy.modules.account.dto.AccountResponses;
import com.openstudy.modules.account.service.AccountCredentialService;

/** Password change, administrator reset, reset-link use and login unlock (#119, account-collab-api.md). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountCredentialController {

    private final AccountCredentialService accountCredentialService;
    private final ClientAddressResolver clientAddressResolver;

    @PostMapping("/me/password")
    public ResponseEntity<Void> changeMyPassword(
            Authentication authentication,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        rejectQuery(request);

        HttpSession session = request.getSession(false);
        String keepSessionId = session == null ? null : session.getId();
        String requestId = ApiRequestFilter.requestId(request);

        accountCredentialService.changePassword(actorId(authentication), body, keepSessionId, requestId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/employees/{id}/password-reset")
    public ResponseEntity<AccountResponses.PasswordResetRequested> requestPasswordReset(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        rejectQuery(request);
        rejectBodyFields(body);

        String requestId = ApiRequestFilter.requestId(request);

        AccountResponses.PasswordResetRequested requested =
                accountCredentialService.requestReset(actorId(authentication), id, requestId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(requested);
    }

    @PostMapping("/employees/{id}/login-unlock")
    public AccountResponses.LoginUnlockResult unlockEmployeeLogin(
            Authentication authentication,
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        rejectQuery(request);
        rejectBodyFields(body);

        String requestId = ApiRequestFilter.requestId(request);

        return accountCredentialService.unlockLogin(actorId(authentication), id, requestId);
    }

    @PostMapping("/password-resets/preview")
    public AccountResponses.PasswordResetPreview previewPasswordReset(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        rejectQuery(request);

        String source = clientAddressResolver.resolve(request);

        return accountCredentialService.previewReset(body, source);
    }

    @PostMapping("/password-resets/complete")
    public ResponseEntity<Void> completePasswordReset(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        rejectQuery(request);

        String source = clientAddressResolver.resolve(request);
        String requestId = ApiRequestFilter.requestId(request);

        accountCredentialService.completeReset(body, source, requestId);

        return ResponseEntity.noContent().build();
    }

    private static void rejectQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiException.validation(
                    request.getParameterNames().nextElement(),
                    "허용되지 않은 쿼리입니다."
            );
        }
    }

    /** No body or an empty JSON object. */
    private static void rejectBodyFields(Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw ApiException.validation(
                    body.keySet().iterator().next(),
                    "허용되지 않은 필드입니다."
            );
        }
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
