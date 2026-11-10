package com.openstudy.modules.account.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ClientAddressResolver;
import com.openstudy.modules.account.dto.AccountResponses;
import com.openstudy.modules.account.service.AccountCredentialService;

/** Password change, administrator reset, reset-link use and login unlock (#119, account-collab-api.md). */
@RestController
@RequiredArgsConstructor
public class AccountCredentialController {
    private final AccountCredentialService service;
    private final ClientAddressResolver addresses;

    @PostMapping("/api/me/password")
    public ResponseEntity<Void> change(Authentication actor, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        var session = request.getSession(false);
        service.changePassword(actor(actor), body, session == null ? null : session.getId(), ApiRequestFilter.requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/employees/{id}/password-reset")
    public ResponseEntity<AccountResponses.PasswordResetRequested> reset(Authentication actor, @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        empty(body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestReset(actor(actor), id, ApiRequestFilter.requestId(request)));
    }

    @PostMapping("/api/employees/{id}/login-unlock")
    public AccountResponses.LoginUnlockResult unlock(Authentication actor, @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        empty(body);
        return service.unlock(actor(actor), id, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/api/password-resets/preview")
    public AccountResponses.PasswordResetPreview preview(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        return service.preview(body, addresses.resolve(request));
    }

    @PostMapping("/api/password-resets/complete")
    public ResponseEntity<Void> complete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        noQuery(request);
        service.complete(body, addresses.resolve(request), ApiRequestFilter.requestId(request));
        return ResponseEntity.noContent().build();
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty())
            throw ApiException.validation(request.getParameterNames().nextElement(), "허용되지 않은 쿼리입니다.");
    }

    /** No body or an empty JSON object. */
    private static void empty(Map<String, Object> body) {
        if (body != null && !body.isEmpty()) throw ApiException.validation(body.keySet().iterator().next(), "허용되지 않은 필드입니다.");
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
