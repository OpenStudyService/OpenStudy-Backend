package com.openstudy.modules.locker.entity;

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
 * A locker held over [startDate, endDate): endDate is free for the next customer. The table has no branch column, so an
 * assignment belongs to the branch of its locker. No constraint stops two periods from overlapping; the locker row lock
 * does (space-operations-api.md). The endDate moves only with an approved extension and stays put on an early return.
 */
@Entity
@Table(name = "locker_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LockerAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "locker_id", nullable = false, updatable = false)
    private long lockerId;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "returned_at")
    private Instant returnedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private LockerAssignmentStatus status;
    @Column(name = "assigned_by", nullable = false, updatable = false)
    private long assignedBy;
    @Column(name = "last_changed_by")
    private Long lastChangedBy;
    @Column(length = 500)
    private String reason;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;

    /** ACTIVE when the Mock payment was approved. A failed payment leaves a record that holds no period. */
    public static LockerAssignment paid(long lockerId, long customerId, LocalDate startDate, LocalDate endDate, boolean approved, long actor) {
        var assignment = new LockerAssignment();
        assignment.lockerId = lockerId; assignment.customerId = customerId; assignment.startDate = startDate;
        assignment.endDate = endDate; assignment.assignedBy = actor;
        assignment.status = approved ? LockerAssignmentStatus.ACTIVE : LockerAssignmentStatus.PAYMENT_FAILED;
        return assignment;
    }

    /** Past its period and not returned, as of today: the end date itself already belongs to the next customer. */
    public boolean overdue(LocalDate today) {
        return status == LockerAssignmentStatus.ACTIVE && !endDate.isAfter(today);
    }

    /** Half-open periods overlap when each starts before the other ends. */
    public boolean overlaps(LocalDate start, LocalDate end) {
        return startDate.isBefore(end) && start.isBefore(endDate);
    }

    public void extendTo(LocalDate endDate, long actor) {
        if (status != LockerAssignmentStatus.ACTIVE) throw new IllegalStateException("Only an active assignment can be extended");
        this.endDate = endDate; this.lastChangedBy = actor;
    }

    /** The end date stays: the paid period is a record of what was bought, and nothing is refunded (M6-D03). */
    public void returnLocker(long actor, String reason, Instant at) {
        if (status != LockerAssignmentStatus.ACTIVE) throw new IllegalStateException("Only an active assignment can be returned");
        status = LockerAssignmentStatus.RETURNED; returnedAt = at; lastChangedBy = actor; this.reason = reason;
    }
}
