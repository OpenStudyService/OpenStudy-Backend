package com.openstudy.modules.auth.dto;

import java.util.List;

import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.employee.dto.EmployeeMembership;

public record CurrentUser(User user, List<SessionBranch> branches, String asOf, String snapshotVersion, String sessionMode) {
    public record User(String id, String branchId, long version, String createdAt, String updatedAt,
            String name, String email, String status, String nickname, String avatar,
            List<EmployeeMembership> memberships, boolean owner) {
    }
}
