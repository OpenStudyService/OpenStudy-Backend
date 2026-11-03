package com.openstudy.modules.workforce.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An actual clock-in, independent of the plan: its shift link is decided once at clock-in and never follows later shift
 * changes (M8-D08). #83 only reads it, for the weekly schedule and a shift's linked attendance; the clock commands of
 * #84 own every write. The generated active_employee_id is left unmapped.
 */
@Entity
@Table(name = "attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord {
    public enum Status { WORKING, LEFT }

    @Id
    private Long id;
    @Column(name = "employee_id", nullable = false, insertable = false, updatable = false)
    private long employeeId;
    @Column(name = "branch_id", nullable = false, insertable = false, updatable = false)
    private long branchId;
    @Column(name = "shift_id", insertable = false, updatable = false)
    private Long shiftId;
    @Column(name = "clock_in_at", nullable = false, insertable = false, updatable = false)
    private Instant clockInAt;
    @Column(name = "clock_out_at", insertable = false, updatable = false)
    private Instant clockOutAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, insertable = false, updatable = false)
    private Status status;
    @Column(insertable = false, updatable = false)
    private long version;
}
