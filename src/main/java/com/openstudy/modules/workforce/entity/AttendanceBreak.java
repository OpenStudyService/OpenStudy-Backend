package com.openstudy.modules.workforce.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A break inside an attendance; an open one (no end) makes the attendance "휴게 중". Read only until #84. */
@Entity
@Table(name = "attendance_breaks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceBreak {
    @Id
    private Long id;
    @Column(name = "attendance_id", nullable = false, insertable = false, updatable = false)
    private long attendanceId;
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "ended_at", insertable = false, updatable = false)
    private Instant endedAt;
}
