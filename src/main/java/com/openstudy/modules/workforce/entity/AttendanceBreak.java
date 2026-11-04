package com.openstudy.modules.workforce.entity;

import java.time.Instant;

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
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceBreak {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "attendance_id", nullable = false, updatable = false)
    private long attendanceId;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;

    public AttendanceBreak(long attendanceId, Instant startedAt) {
        this.attendanceId = attendanceId; this.startedAt = startedAt;
    }

    public void end(Instant at) {
        if (endedAt != null) throw new IllegalStateException("A break ends only once");
        endedAt = at;
    }
}
