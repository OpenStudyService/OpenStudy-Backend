package com.openstudy.modules.workforce.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
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
@SemanticEntity(
        id = "attendance_record",
        name = "출퇴근 기록",
        description = "직원 실제 출근·퇴근 시각 및 근태 상태 원장",
        aliases = {"근무시간", "출퇴근", "근태"},
        domain = "attendance"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord {
    public enum Status { WORKING, LEFT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "attendance_record.id", name = "출퇴근 기록 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "employee_id", nullable = false, updatable = false)
    @SemanticField(id = "attendance_record.employee_id", name = "직원 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "attendance_record.employee", targetEntity = "employee", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "출퇴근 직원")
    private long employeeId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "attendance_record.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "attendance_record.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "출퇴근 지점")
    private long branchId;
    @Column(name = "shift_id", updatable = false)
    private Long shiftId;
    @Column(name = "clock_in_at", nullable = false, updatable = false)
    @SemanticField(id = "attendance_record.clock_in_at", name = "출근 시각", role = "TIME", aliases = {"출근"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant clockInAt;
    @Column(name = "clock_out_at")
    @SemanticField(id = "attendance_record.clock_out_at", name = "퇴근 시각", role = "TIME", aliases = {"퇴근"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_NULL"})
    private Instant clockOutAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "attendance_record.status", name = "근태 상태", role = "STATUS", allowedValues = {"WORKING", "LEFT"}, allowedOps = {"EQ", "NE", "IN"})
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
