package com.openstudy.modules.branch.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/** Contract schema {@code BranchClosure} (operations-extension-api.md "지점 휴무일"). */
public record BranchClosureView(String id, long version, String branchId, LocalDate date, String note, ActorSummary createdBy,
        Instant createdAt, Instant updatedAt, boolean canEdit, boolean canDelete) {

    /** Contract schema {@code ActorSummary}. */
    public record ActorSummary(String id, String name) {}
}
