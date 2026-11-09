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
 * A planned shift over [startsAt, endsAt). Its branch never changes, and its employee only through an approved shift
 * swap (M12-D04, workforce-requests-api.md): moving it otherwise means cancelling and registering again
 * (workforce-api.md "수정"). No constraint stops two shifts of one employee from overlapping; the employee row lock
 * does, across every branch (M8-D05).
 */
@Entity
@Table(name = "shifts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shift {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "employee_id", nullable = false)
    private long employeeId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Column(name = "planned_break_minutes", nullable = false, columnDefinition = "smallint unsigned")
    private int plannedBreakMinutes;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ShiftStatus status;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;

    public Shift(long branchId, long employeeId, Instant startsAt, Instant endsAt, int plannedBreakMinutes, long createdBy) {
        this.branchId = branchId; this.employeeId = employeeId; this.startsAt = startsAt; this.endsAt = endsAt;
        this.plannedBreakMinutes = plannedBreakMinutes; this.createdBy = createdBy; this.status = ShiftStatus.SCHEDULED;
    }

    public boolean cancelled() { return status == ShiftStatus.CANCELLED; }

    public void reschedule(Instant startsAt, Instant endsAt, int plannedBreakMinutes) {
        if (cancelled()) throw new IllegalStateException("A cancelled shift cannot be changed");
        this.startsAt = startsAt; this.endsAt = endsAt; this.plannedBreakMinutes = plannedBreakMinutes;
    }

    /** An approved swap, or its cancellation: who works it and when, together (M12-D04·D09). */
    public void reassign(long employeeId, Instant startsAt, Instant endsAt) {
        if (cancelled()) throw new IllegalStateException("A cancelled shift cannot be swapped");
        this.employeeId = employeeId; this.startsAt = startsAt; this.endsAt = endsAt;
    }

    public void cancel() {
        if (cancelled()) throw new IllegalStateException("A shift is cancelled only once");
        status = ShiftStatus.CANCELLED;
    }
}
