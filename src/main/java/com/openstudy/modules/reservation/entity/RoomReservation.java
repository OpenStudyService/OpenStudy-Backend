package com.openstudy.modules.reservation.entity;

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
 * One room booking over [startAt, endAt). The hourly rate, the hours and the amount are the snapshot taken when it was
 * booked; changing the room never touches them (M6-D01). ck_room_reservations_10 ties amount = rate × hours.
 */
@Entity
@Table(name = "room_reservations")
@SemanticEntity(
        id = "room_reservation",
        name = "스터디룸 예약",
        description = "스터디룸 예약 일정 및 상태 원장",
        aliases = {"룸 예약", "스터디룸 예약 현황", "이번 주 예약"},
        domain = "rooms"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "room_reservation.id", name = "예약 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "room_id", nullable = false, updatable = false)
    @SemanticField(id = "room_reservation.room_id", name = "스터디룸 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "room_reservation.room", targetEntity = "room", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "예약된 스터디룸")
    private long roomId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "room_reservation.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "room_reservation.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "예약 지점")
    private long branchId;
    @Column(name = "customer_id", nullable = false, updatable = false)
    @SemanticRelation(id = "room_reservation.customer", targetEntity = "customer", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "예약 회원")
    private long customerId;
    @Column(name = "payment_id", updatable = false)
    @SemanticField(id = "room_reservation.payment_id", name = "결제 ID", role = "FK", allowedOps = {"EQ", "NE", "IN", "IS_NULL"})
    @SemanticRelation(id = "room_reservation.payment", targetEntity = "payment", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "스터디룸 예약 결제")
    private Long paymentId;
    @Column(name = "start_at", nullable = false, updatable = false)
    @SemanticField(id = "room_reservation.start_at", name = "예약 시작 시각", role = "TIME", aliases = {"시작일시", "예약일시"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant startAt;
    @Column(name = "end_at", nullable = false, updatable = false)
    @SemanticField(id = "room_reservation.end_at", name = "예약 종료 시각", role = "TIME", aliases = {"종료일시"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant endAt;
    @Column(name = "rate_per_hour_snapshot_krw", nullable = false, updatable = false)
    private long ratePerHourSnapshotKrw;
    @Column(nullable = false, updatable = false, columnDefinition = "int unsigned")
    private int hours;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    @SemanticField(id = "room_reservation.amount_krw", name = "예약 결제 금액", role = "AMOUNT", unit = "KRW", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN", "SUM"})
    private long amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "room_reservation.status", name = "예약 상태", role = "STATUS", allowedValues = {"PAYMENT_PENDING", "CONFIRMED", "PAYMENT_FAILED", "IN_USE", "COMPLETED", "CANCELLED"}, allowedOps = {"EQ", "NE", "IN"})
    private RoomReservationStatus status;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    @Column(name = "cancel_reason", length = 500)
    @SemanticField(id = "room_reservation.cancel_reason", name = "취소 사유", exposed = false)
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

    /** ck_room_reservations_11 ties these three columns to the status. */
    public void cancel(long actor, String reason, Instant at) {
        status = RoomReservationStatus.CANCELLED; cancelledBy = actor; cancelReason = reason; cancelledAt = at;
    }

    public void startUsage() { status = RoomReservationStatus.IN_USE; }

    public void completeUsage() { status = RoomReservationStatus.COMPLETED; }
}
