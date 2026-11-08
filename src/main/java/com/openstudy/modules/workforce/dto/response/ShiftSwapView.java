package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code ShiftSwap}. original* is the shift's current time while requested and the time before the
 * approval afterwards; shiftVersion is the shift's version to send with an approval. The can* fields only guide the screen.
 */
public record ShiftSwapView(String id, long version, String status, String shiftId, String branchId, ActorSummary requester,
        ActorSummary replacement, Instant originalStartsAt, Instant originalEndsAt, Instant proposedStartsAt, Instant proposedEndsAt,
        long shiftVersion, String reason, RequestDecision decision, RequestCancellation cancellation, Instant createdAt,
        boolean canApprove, boolean canReject, boolean canCancel, boolean canCancelApproval) {}
