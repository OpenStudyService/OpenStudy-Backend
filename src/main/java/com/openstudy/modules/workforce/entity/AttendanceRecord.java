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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An actual clock-in, independent of the plan: its shift link is decided once at clock-in and never follows later shift
 * changes (M8-D08). Only the employee's own clock commands write it (#84), always holding its row FOR UPDATE.
 * <p>The version also stands for the breaks, which have none of their own (workforce-api.md "상태 전이"): a break start or
 * end changes no column of this row, so the version is counted by hand rather than by {@code @Version}, which only moves
 * with a changed column. The generated active_employee_id, unique while WORKING (uk_attendance_records_1), is left
 * unmapped; the lookups by it are native.</p>
 */
@Entity
@Table(name = "attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord {
    public enum Status { WORKING, LEFT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private long employeeId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "shift_id", updatable = false)
    private Long shiftId;
    @Column(name = "clock_in_at", nullable = false, updatable = false)
    private Instant clockInAt;
    @Column(name = "clock_out_at")
    private Instant clockOutAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status;
    @Column(nullable = false)
    private long version;

    /** A clock-in at a server time already cut to whole seconds (M8-D07). */
    public AttendanceRecord(long employeeId, long branchId, Long shiftId, Instant clockInAt) {
        this.employeeId = employeeId; this.branchId = branchId; this.shiftId = shiftId; this.clockInAt = clockInAt;
        this.status = Status.WORKING;
    }

    public boolean working() { return status == Status.WORKING; }

    /** A break started or ended: the record's version is the one the client sends back for the next command. */
    public void touch() { version++; }

    public void clockOut(Instant at) {
        if (!working()) throw new IllegalStateException("An attendance is closed only once");
        clockOutAt = at; status = Status.LEFT; version++;
    }
}
