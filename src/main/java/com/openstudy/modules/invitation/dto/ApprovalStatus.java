package com.openstudy.modules.invitation.dto;

import java.util.List;

public record ApprovalStatus(String accountStatus, List<Item> invitations, boolean requiresLogin) {
    public record Item(String invitationId, String branchId, String branchName, String status,
            String rejectionReason, String updatedAt) {}
}
