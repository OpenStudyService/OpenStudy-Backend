package com.openstudy.modules.invitation.dto;

import java.time.Instant;
import java.util.List;

/** Administrative read model; deliberately contains no token, hash, revision or mail link. */
public record Invitation(String id, long version, Instant createdAt, Instant updatedAt, String branchId,
        String name, String email, String role, List<String> permissions, String status, String delivery,
        Instant expiresAt, String employeeId, String rejectionReason) {
    public Invitation { permissions = List.copyOf(permissions); }
}
