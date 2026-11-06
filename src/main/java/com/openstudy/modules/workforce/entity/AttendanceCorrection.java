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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Employee request to correct clock-in/out or break timestamps on an attendance record (`attendance_corrections`).
 */
@Entity
@Table(name = "attendance_corrections")
@SemanticEntity(
        id = "attendance_correction",
        name = "근태 정정 신청",
        description = "출퇴근 및 휴게 시간 정정 신청·승인 내역",
        aliases = {"근태 정정", "출퇴근 정정"},
        domain = "attendance"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceCorrection {
    public enum Kind { CLOCK, BREAK }
    public enum Status { REQUESTED, APPROVED, REJECTED, CANCELLED }

    @Id
    @SemanticField(id = "attendance_correction.id", name = "정정 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;

    @Column(name = "attendance_id", nullable = false, insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.attendance_record_id", name = "출퇴근 기록 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "attendance_correction.attendance_record", targetEntity = "attendance_record", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "정정 대상 출퇴근 기록")
    private long attendanceRecordId;

    @Column(name = "requested_by", nullable = false, insertable = false, updatable = false)
    private long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_kind", nullable = false, insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.kind", name = "정정 구분", role = "CATEGORY", allowedValues = {"CLOCK", "BREAK"}, allowedOps = {"EQ", "NE", "IN"})
    private Kind correctionKind;

    @Column(name = "break_id", insertable = false, updatable = false)
    private Long breakId;

    @Column(name = "before_clock_in_at", insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.before_clock_in_at", name = "정정 전 출근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant beforeClockInAt;

    @Column(name = "before_clock_out_at", insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.before_clock_out_at", name = "정정 전 퇴근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant beforeClockOutAt;

    @Column(name = "after_clock_in_at", insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.requested_clock_in_at", name = "정정 요청 출근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant requestedClockInAt;

    @Column(name = "after_clock_out_at", insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.requested_clock_out_at", name = "정정 요청 퇴근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant requestedClockOutAt;

    @Column(name = "before_break_start_at", insertable = false, updatable = false)
    private Instant beforeBreakStartAt;

    @Column(name = "before_break_end_at", insertable = false, updatable = false)
    private Instant beforeBreakEndAt;

    @Column(name = "after_break_start_at", insertable = false, updatable = false)
    private Instant afterBreakStartAt;

    @Column(name = "after_break_end_at", insertable = false, updatable = false)
    private Instant afterBreakEndAt;

    @Column(nullable = false, length = 500, insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.reason", name = "정정 사유", exposed = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, insertable = false, updatable = false)
    @SemanticField(id = "attendance_correction.status", name = "정정 상태", role = "STATUS", allowedValues = {"REQUESTED", "APPROVED", "REJECTED", "CANCELLED"}, allowedOps = {"EQ", "NE", "IN"})
    private Status status;

    @Column(name = "decided_by", insertable = false, updatable = false)
    private Long decidedBy;

    @Column(name = "decided_at", insertable = false, updatable = false)
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private long version;
}
