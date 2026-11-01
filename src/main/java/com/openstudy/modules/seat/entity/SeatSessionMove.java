package com.openstudy.modules.seat.entity;

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
import org.hibernate.annotations.Immutable;

/** One seat move of a stay (#63). A row is only ever added, never changed, so the entity stays immutable. */
@Entity
@Immutable
@Table(name = "seat_session_moves")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatSessionMove {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    @Column(name = "request_key", nullable = false, length = 120)
    private String requestKey;
    @Column(name = "moved_at", nullable = false)
    private Instant movedAt;

    public static SeatSessionMove of(long seatSessionId, long fromSeatId, long toSeatId, long actor, String reason,
            String requestKey, Instant movedAt) {
        var move = new SeatSessionMove();
        move.seatSessionId = seatSessionId; move.fromSeatId = fromSeatId; move.toSeatId = toSeatId;
        move.actorEmployeeId = actor; move.reason = reason; move.requestKey = requestKey; move.movedAt = movedAt;
        return move;
    }
}
