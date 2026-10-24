package com.openstudy.modules.employee.dto;

import java.time.Instant;
import java.util.List;

/** Credential-free management projection. Memberships must already be scope-filtered. */
public record ManagedEmployee(String id, long version, Instant createdAt, Instant updatedAt,
        String branchId, String name, String email, String status, String nickname, String avatar,
        boolean owner, List<Membership> memberships) {
    public record Membership(String branchId, String role, String status, List<String> permissions, long version) {}
    public record MembershipResult(long employeeVersion, Membership membership) {}
}
