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
 * Branch room ledger row. There is no delete: reservations reference the row, so a room is closed by blocking it.
 * The branch never changes. The rate is the current sale price; each reservation keeps its own snapshot.
 */
@Entity
@Table(name = "rooms")
@SemanticEntity(
        id = "room",
        name = "스터디룸",
        description = "지점별 스터디룸 원장",
        aliases = {"룸", "회의실", "그룹룸"},
        domain = "rooms"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "room.id", name = "스터디룸 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "room.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "room.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "스터디룸 소속 지점")
    private long branchId;
    @Column(name = "room_no", nullable = false, length = 40)
    @SemanticField(id = "room.room_no", name = "룸 번호", role = "CODE", allowedOps = {"EQ", "NE", "IN"})
    private String roomNo;
    @Column(nullable = false, length = 120)
    @SemanticField(id = "room.name", name = "스터디룸명", role = "LABEL", allowedOps = {"EQ", "NE", "IN"})
    private String name;
    @Column(nullable = false, columnDefinition = "smallint unsigned")
    @SemanticField(id = "room.capacity", name = "수용 인원", role = "MEASURE", unit = "명", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private int capacity;
    @Column(name = "rate_per_hour_krw", nullable = false)
    @SemanticField(id = "room.rate_per_hour_krw", name = "시간당 요금", role = "AMOUNT", unit = "KRW", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private long ratePerHourKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "room.status", name = "스터디룸 상태", role = "STATUS", allowedValues = {"AVAILABLE", "BLOCKED"}, allowedOps = {"EQ", "NE", "IN"})
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
