package com.openstudy.modules.seat.entity;

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
 * Branch seat ledger row. There is no delete (seat-usage-api.md): sessions and moves reference the row, so a seat is
 * closed by blocking it. The branch never changes.
 */
@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(nullable = false, length = 80)
    private String zone;
    @Column(name = "seat_no", nullable = false, length = 30)
    private String seatNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SeatKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SeatState status;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    /** Exposed to clients as version + 1 and checked on every change. */
    @Version
    private long version;

    public Seat(long branchId, String zone, String seatNo, SeatKind kind, SeatState status) {
        this.branchId = branchId; this.zone = zone; this.seatNo = seatNo; this.kind = kind; this.status = status;
    }

    public void change(String zone, String seatNo, SeatKind kind) {
        this.zone = zone; this.seatNo = seatNo; this.kind = kind;
    }

    public void changeStatus(SeatState status) { this.status = status; }
}
