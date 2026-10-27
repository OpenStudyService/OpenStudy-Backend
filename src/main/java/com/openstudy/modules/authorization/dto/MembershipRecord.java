package com.openstudy.modules.authorization.dto;

import java.util.List;

/**
 * A branch membership as other modules see it. {@code role} is MANAGER/STAFF and {@code status}
 * PENDING/ACTIVE/REVOKED (the stored codes); {@code dbVersion} is one less than the API version.
 */
public record MembershipRecord(long id, long branchId, String role, String status, List<String> permissions, long dbVersion) {
    public MembershipRecord { permissions = List.copyOf(permissions); }
}
