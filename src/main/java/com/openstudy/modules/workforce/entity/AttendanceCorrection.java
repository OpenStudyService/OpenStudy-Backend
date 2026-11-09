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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * An employee's request to correct the clock-in/out or one break of their own closed attendance (M12-D01, #107). The
 * before_* columns keep the values at request time and after_* the corrected ones; only the columns of the kind are set.
 * The attendance itself changes only when a manager approves. decided_by and decided_at keep who approved or rejected
 * it, or the requester who withdrew it; an approved correction is never cancelled (M12-D08). A rejection's reason is
 * kept in audit_events, which V1 gives a reason column (2026-11-09 user decision).
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

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "attendance_correction.id", name = "정정 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    @SemanticField(id = "attendance_correction.attendance_record_id", name = "출퇴근 기록 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "attendance_correction.attendance_record", targetEntity = "attendance_record", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "정정 대상 출퇴근 기록")
    private long attendanceRecordId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_kind", nullable = false, updatable = false)
    @SemanticField(id = "attendance_correction.kind", name = "정정 구분", role = "CATEGORY", allowedValues = {"CLOCK", "BREAK"}, allowedOps = {"EQ", "NE", "IN"})
    private Kind correctionKind;

    @Column(name = "break_id", updatable = false)
    private Long breakId;

    @Column(name = "before_clock_in_at", updatable = false)
    @SemanticField(id = "attendance_correction.before_clock_in_at", name = "정정 전 출근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant beforeClockInAt;

    @Column(name = "before_clock_out_at", updatable = false)
    @SemanticField(id = "attendance_correction.before_clock_out_at", name = "정정 전 퇴근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant beforeClockOutAt;

    @Column(name = "after_clock_in_at", updatable = false)
    @SemanticField(id = "attendance_correction.requested_clock_in_at", name = "정정 요청 출근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant requestedClockInAt;

    @Column(name = "after_clock_out_at", updatable = false)
    @SemanticField(id = "attendance_correction.requested_clock_out_at", name = "정정 요청 퇴근 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant requestedClockOutAt;

    @Column(name = "before_break_start_at", updatable = false)
    private Instant beforeBreakStartAt;

    @Column(name = "before_break_end_at", updatable = false)
    private Instant beforeBreakEndAt;

    @Column(name = "after_break_start_at", updatable = false)
    private Instant afterBreakStartAt;

    @Column(name = "after_break_end_at", updatable = false)
    private Instant afterBreakEndAt;

    @Column(nullable = false, length = 500, updatable = false)
    @SemanticField(id = "attendance_correction.reason", name = "정정 사유", exposed = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @SemanticField(id = "attendance_correction.status", name = "정정 상태", role = "STATUS", allowedValues = {"REQUESTED", "APPROVED", "REJECTED", "CANCELLED"}, allowedOps = {"EQ", "NE", "IN"})
    private Status status;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Generated(event = EventType.INSERT) @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    /** A clock correction: both times, from the attendance's current ones. */
    public static AttendanceCorrection clock(long attendance, long requester, Instant beforeIn, Instant beforeOut, Instant afterIn,
            Instant afterOut, String reason) {
        var correction = new AttendanceCorrection(attendance, requester, Kind.CLOCK, null, reason);
        correction.beforeClockInAt = beforeIn; correction.beforeClockOutAt = beforeOut;
        correction.requestedClockInAt = afterIn; correction.requestedClockOutAt = afterOut;
        return correction;
    }

    /** A correction of one existing break of the attendance. */
    public static AttendanceCorrection pause(long attendance, long requester, long breakId, Instant beforeStart, Instant beforeEnd,
            Instant afterStart, Instant afterEnd, String reason) {
        var correction = new AttendanceCorrection(attendance, requester, Kind.BREAK, breakId, reason);
        correction.beforeBreakStartAt = beforeStart; correction.beforeBreakEndAt = beforeEnd;
        correction.afterBreakStartAt = afterStart; correction.afterBreakEndAt = afterEnd;
        return correction;
    }

    private AttendanceCorrection(long attendance, long requester, Kind kind, Long breakId, String reason) {
        this.attendanceRecordId = attendance; this.requestedBy = requester; this.correctionKind = kind; this.breakId = breakId;
        this.reason = reason; this.status = Status.REQUESTED;
    }

    public void approve(long actor, Instant at) { decide(Status.APPROVED, actor, at); }

    public void reject(long actor, Instant at) { decide(Status.REJECTED, actor, at); }

    public void withdraw(long actor, Instant at) { decide(Status.CANCELLED, actor, at); }

    private void decide(Status to, long actor, Instant at) {
        if (status != Status.REQUESTED) throw new IllegalStateException("Attendance correction is " + status + ", not REQUESTED");
        status = to; decidedBy = actor; decidedAt = at;
    }
}
