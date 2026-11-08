package com.openstudy.modules.workforce.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
 * A leave request over whole Seoul days [startsOn, endsOn] (M12, workforce-requests-api.md). M8 reads the approved ones
 * as busy days for shifts (M8-D06). The branch is where the request is decided (M12-D02, V11); the balance it uses is
 * per employee. decided_by and decided_at keep who approved or rejected it, or who withdrew it before a decision; an
 * approval cancelled later keeps its approver, and the cancellation is read from the RESTORE ledger rows.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequest {
    public enum Status {
        REQUESTED("신청"), APPROVED("승인"), REJECTED("반려"), CANCELLED("취소");

        public final String label;
        Status(String label) { this.label = label; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private long employeeId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "request_year", nullable = false, updatable = false, columnDefinition = "smallint unsigned")
    private int requestYear;
    @Column(name = "starts_on", nullable = false, updatable = false)
    private LocalDate startsOn;
    @Column(name = "ends_on", nullable = false, updatable = false)
    private LocalDate endsOn;
    @Column(name = "requested_days", nullable = false, updatable = false, precision = 7, scale = 1)
    private BigDecimal requestedDays;
    @Column(nullable = false, updatable = false, length = 500)
    private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status;
    @Column(name = "decided_by")
    private Long decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;

    public LeaveRequest(long employeeId, long branchId, LocalDate startsOn, LocalDate endsOn, BigDecimal requestedDays, String reason) {
        this.employeeId = employeeId; this.branchId = branchId; this.requestYear = startsOn.getYear();
        this.startsOn = startsOn; this.endsOn = endsOn; this.requestedDays = requestedDays; this.reason = reason;
        this.status = Status.REQUESTED;
    }

    public void approve(long actor, Instant at) { decide(Status.REQUESTED, Status.APPROVED, actor, at); }

    public void reject(long actor, Instant at) { decide(Status.REQUESTED, Status.REJECTED, actor, at); }

    /** The requester withdraws it before any decision; who and when go into decided_*. */
    public void withdraw(long actor, Instant at) { decide(Status.REQUESTED, Status.CANCELLED, actor, at); }

    /** A manager cancels an approval; the approver stays in decided_*. */
    public void cancelApproval() {
        if (status != Status.APPROVED) throw new IllegalStateException("Only an approved leave is cancelled after approval");
        status = Status.CANCELLED;
    }

    private void decide(Status from, Status to, long actor, Instant at) {
        if (status != from) throw new IllegalStateException("Leave request is " + status + ", not " + from);
        status = to; decidedBy = actor; decidedAt = at;
    }
}
