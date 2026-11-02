package com.openstudy.modules.locker.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code LockerAssignment}. charges is null without payments:read or payments:write at the locker's branch. */
public record LockerAssignmentView(String id, long version, String status, String branchId, LockerRef locker, CustomerSummary customer,
        LocalDate startDate, LocalDate endDate, boolean overdue, Instant returnedAt, ActorSummary assignedBy, ActorSummary lastChangedBy,
        String reason, List<LockerChargeView> charges, Instant createdAt, Instant updatedAt) {}
