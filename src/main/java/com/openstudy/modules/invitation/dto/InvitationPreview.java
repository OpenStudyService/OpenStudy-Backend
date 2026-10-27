package com.openstudy.modules.invitation.dto;

import java.time.Instant;

/** Minimum invitation facts for the link holder. No account, token, revision or delivery data. */
public record InvitationPreview(String name, String email, Branch branch, String role, Instant expiresAt,
        String acceptanceMode) {
    public record Branch(String id, String name) {}
}
