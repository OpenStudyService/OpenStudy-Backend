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
 * Branch room ledger row. There is no delete: reservations reference the row, so a room is closed by blocking it.
 * The branch never changes. The rate is the current sale price; each reservation keeps its own snapshot.
 */
@Entity
@Table(name = "rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "room_no", nullable = false, length = 40)
    private String roomNo;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, columnDefinition = "smallint unsigned")
    private int capacity;
    @Column(name = "rate_per_hour_krw", nullable = false)
    private long ratePerHourKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RoomState status;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients as version + 1 and checked on every change. */
    @Version
    private long version;

    public Room(long branchId, String roomNo, String name, int capacity, long ratePerHourKrw, RoomState status) {
        this.branchId = branchId; this.roomNo = roomNo; this.name = name; this.capacity = capacity;
        this.ratePerHourKrw = ratePerHourKrw; this.status = status;
    }

    public void change(String roomNo, String name, int capacity, long ratePerHourKrw) {
        this.roomNo = roomNo; this.name = name; this.capacity = capacity; this.ratePerHourKrw = ratePerHourKrw;
    }

    public void changeStatus(RoomState status) { this.status = status; }
}
