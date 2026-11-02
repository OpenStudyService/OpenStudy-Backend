package com.openstudy.modules.reservation.entity;

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
 * The actual use of a reservation, one row each (uk_room_usages_1). The times are when staff processed it, not the booked
 * period (FR-ROOM-08). The actor columns become null if the employee row is ever removed (fk_room_usages_2·3).
 */
@Entity
@Table(name = "room_usages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomUsage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private long reservationId;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "started_by", updatable = false)
    private Long startedBy;
    @Column(name = "ended_by")
    private Long endedBy;

    public RoomUsage(long reservationId, Instant startedAt, long actor) {
        this.reservationId = reservationId; this.startedAt = startedAt; this.startedBy = actor;
    }

    public void end(Instant at, long actor) {
        endedAt = at; endedBy = actor;
    }
}
