package com.openstudy.modules.reservation.entity;

import java.time.Instant;

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
 * One room booking over [startAt, endAt). The hourly rate, the hours and the amount are the snapshot taken when it was
 * booked; changing the room never touches them (M6-D01). ck_room_reservations_10 ties amount = rate × hours.
 */
@Entity
@Table(name = "room_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_id", nullable = false, updatable = false)
    private long roomId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;
    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;
    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;
    @Column(name = "rate_per_hour_snapshot_krw", nullable = false, updatable = false)
    private long ratePerHourSnapshotKrw;
    @Column(nullable = false, updatable = false, columnDefinition = "int unsigned")
    private int hours;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    private long amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RoomReservationStatus status;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
    /** Server-made key, unique across every reservation (contract "트랜잭션 경계"). */
    @Column(name = "request_key", nullable = false, length = 120, updatable = false)
    private String requestKey;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients as version + 1. */
    @Version
    private long version;

    /** A booking decided by its Mock payment: confirmed, or kept as a failed attempt that holds no time. */
    public static RoomReservation booked(Room room, long customerId, long paymentId, Instant startAt, Instant endAt,
            int hours, long amountKrw, boolean approved, String requestKey, long actor) {
        var reservation = new RoomReservation();
        reservation.roomId = room.getId(); reservation.branchId = room.getBranchId(); reservation.customerId = customerId;
        reservation.paymentId = paymentId; reservation.startAt = startAt; reservation.endAt = endAt;
        reservation.ratePerHourSnapshotKrw = room.getRatePerHourKrw(); reservation.hours = hours;
        reservation.amountKrw = amountKrw; reservation.requestKey = requestKey; reservation.createdBy = actor;
        reservation.status = approved ? RoomReservationStatus.CONFIRMED : RoomReservationStatus.PAYMENT_FAILED;
        return reservation;
    }
}
