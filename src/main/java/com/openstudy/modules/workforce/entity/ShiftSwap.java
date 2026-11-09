package com.openstudy.modules.workforce.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A request by a shift's own employee to hand it to a colleague, at the proposed time (M12-D04, #109). The shift itself
 * changes only when a manager approves it; the shift_events SWAPPED rows of the approval and of its cancellation carry
 * this request's id. decided_by and decided_at keep who approved or rejected it, or the requester who withdrew it; an
 * approval cancelled later keeps its approver.
 */
@Entity
@Table(name = "shift_swaps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftSwap {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "original_shift_id", nullable = false, updatable = false)
    private long originalShiftId;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private long requestedBy;
    @Column(name = "replacement_employee_id", nullable = false, updatable = false)
    private long replacementEmployeeId;
    @Column(name = "proposed_starts_at", nullable = false, updatable = false)
    private Instant proposedStartsAt;
    @Column(name = "proposed_ends_at", nullable = false, updatable = false)
    private Instant proposedEndsAt;
    @Column(nullable = false, updatable = false, length = 500)
    private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private LeaveRequest.Status status;
    @Column(name = "decided_by")
    private Long decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private long version;

    public ShiftSwap(long originalShiftId, long requestedBy, long replacementEmployeeId, Instant proposedStartsAt, Instant proposedEndsAt,
            String reason) {
        this.originalShiftId = originalShiftId; this.requestedBy = requestedBy; this.replacementEmployeeId = replacementEmployeeId;
        this.proposedStartsAt = proposedStartsAt; this.proposedEndsAt = proposedEndsAt; this.reason = reason;
        this.status = LeaveRequest.Status.REQUESTED;
    }

    public void approve(long actor, Instant at) { decide(LeaveRequest.Status.APPROVED, actor, at); }

    public void reject(long actor, Instant at) { decide(LeaveRequest.Status.REJECTED, actor, at); }

    public void withdraw(long actor, Instant at) { decide(LeaveRequest.Status.CANCELLED, actor, at); }

    /** A manager cancels an approval and the shift is restored; the approver stays in decided_*. */
    public void cancelApproval() {
        if (status != LeaveRequest.Status.APPROVED) throw new IllegalStateException("Only an approved swap is cancelled after approval");
        status = LeaveRequest.Status.CANCELLED;
    }

    private void decide(LeaveRequest.Status to, long actor, Instant at) {
        if (status != LeaveRequest.Status.REQUESTED) throw new IllegalStateException("Shift swap is " + status + ", not REQUESTED");
        status = to; decidedBy = actor; decidedAt = at;
    }
}
