package com.openstudy.modules.seat.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read model of {@code seat_sessions} for the seat ledger: only what a seat needs to show who is using it. Check-in,
 * check-out and moves (#62, #63) extend this entity with the remaining columns and make it writable.
 */
@Entity
@Immutable
@Table(name = "seat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatSession {
    public enum Status { ACTIVE, CLOSED }

    @Id
    private Long id;
    @Column(name = "customer_id", nullable = false)
    private long customerId;
    @Column(name = "branch_id", nullable = false)
    private long branchId;
    @Column(name = "seat_id", nullable = false)
    private long seatId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status;
    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;
}
