package com.openstudy.modules.workforce.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A break inside an attendance; an open one (no end) makes the attendance "휴게 중". No key keeps a second one from
 * opening, so it is written only by a holder of its attendance row lock (workforce-api.md "휴게·퇴근").
 */
@Entity
@Table(name = "attendance_breaks")
@SemanticEntity(
        id = "attendance_break",
        name = "출근 휴게 구간",
        description = "출근 중 휴게 시작·종료 구간",
        aliases = {"휴게", "휴게시간"},
        domain = "attendance"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceBreak {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "attendance_break.id", name = "휴게 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "attendance_id", nullable = false, updatable = false)
    @SemanticField(id = "attendance_break.attendance_id", name = "출퇴근 기록 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "attendance_break.attendance_record", targetEntity = "attendance_record", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "소속 출퇴근 기록")
    private long attendanceId;
    @Column(name = "started_at", nullable = false, updatable = false)
    @SemanticField(id = "attendance_break.break_start_at", name = "휴게 시작 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant startedAt;
    @Column(name = "ended_at")
    @SemanticField(id = "attendance_break.break_end_at", name = "휴게 종료 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_NULL"})
    private Instant endedAt;

    public AttendanceBreak(long attendanceId, Instant startedAt) {
        this.attendanceId = attendanceId; this.startedAt = startedAt;
    }

    public void end(Instant at) {
        if (endedAt != null) throw new IllegalStateException("A break ends only once");
        endedAt = at;
    }
}
