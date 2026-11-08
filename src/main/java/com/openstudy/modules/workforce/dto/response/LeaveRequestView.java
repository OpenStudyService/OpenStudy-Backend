package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code LeaveRequest}. The can* fields only guide the screen; every write is checked again. */
public record LeaveRequestView(String id, long version, String status, ActorSummary employee, String branchId, LocalDate startsOn,
        LocalDate endsOn, BigDecimal requestedDays, String reason, RequestDecision decision, RequestCancellation cancellation,
        List<LeaveAllocationView> allocations, Instant createdAt, boolean canApprove, boolean canReject, boolean canCancel,
        boolean canCancelApproval) {}
