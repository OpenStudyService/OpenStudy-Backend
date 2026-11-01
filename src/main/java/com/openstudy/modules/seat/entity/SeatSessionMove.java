package com.openstudy.modules.seat.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Read model of {@code seat_session_moves} for the session detail. Seat moves (#63) make it writable. */
@Entity
@Immutable
@Table(name = "seat_session_moves")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatSessionMove {
    @Id
    private Long id;
    @Column(name = "seat_session_id", nullable = false)
    private long seatSessionId;
    @Column(name = "from_seat_id", nullable = false)
    private long fromSeatId;
    @Column(name = "to_seat_id", nullable = false)
    private long toSeatId;
    @Column(name = "actor_employee_id")
    private Long actorEmployeeId;
    @Column(length = 500)
    private String reason;
    @Column(name = "moved_at", nullable = false)
    private Instant movedAt;
}
