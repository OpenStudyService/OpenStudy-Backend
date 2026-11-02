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

/** One held hour of a confirmed reservation. uk_room_reservation_slots_1 is the last defence against double booking. */
@Entity
@Table(name = "room_reservation_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomReservationSlot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private long reservationId;
    @Column(name = "room_id", nullable = false, updatable = false)
    private long roomId;
    @Column(name = "slot_start_at", nullable = false, updatable = false)
    private Instant slotStartAt;

    public RoomReservationSlot(long reservationId, long roomId, Instant slotStartAt) {
        this.reservationId = reservationId; this.roomId = roomId; this.slotStartAt = slotStartAt;
    }
}
