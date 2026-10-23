package com.openstudy.modules.employee.dto;

import java.time.Instant;

// Credentials stay on the server and are never part of an HTTP response or session principal.
public record EmployeeAccount(long id, String email, String passwordHash, String name, String nickname,
        String avatar, String globalRole, String status, long version, Instant createdAt, Instant updatedAt) {
    public boolean canLogin() {
        return "ACTIVE".equals(status) || "PENDING".equals(status);
    }

    public boolean owner() {
        return "OWNER".equals(globalRole);
    }

    @Override
    public String toString() {
        return "EmployeeAccount[id=" + id + "]";
    }
}
