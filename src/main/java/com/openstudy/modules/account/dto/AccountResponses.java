package com.openstudy.modules.account.dto;

import java.time.Instant;

/** Response bodies of account-collab-api.md. None of them carries a password, a hash or a token. */
public final class AccountResponses {
    private AccountResponses() {}

    public record PasswordResetRequested(String employeeId, Instant requestedAt, String delivery) {}

    public record PasswordResetPreview(Instant expiresAt, String emailHint) {}

    public record LoginUnlockResult(String employeeId, boolean wasLocked) {}
}
