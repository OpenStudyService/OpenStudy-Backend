package com.openstudy.modules.seat.entity;

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
 * Branch seat ledger row. There is no delete (seat-usage-api.md): sessions and moves reference the row, so a seat is
 * closed by blocking it. The branch never changes.
 */
@Entity
@Table(name = "seats")
@SemanticEntity(
        id = "seat",
        name = "좌석",
        description = "지점별 운영 좌석 원장",
        aliases = {"빈자리", "좌석현황", "자유석"},
        domain = "seats"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "seat.id", name = "좌석 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "seat.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "seat.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "좌석 소속 지점")
    private long branchId;
    @Column(nullable = false, length = 80)
    private String zone;
    @Column(name = "seat_no", nullable = false, length = 30)
    @SemanticField(id = "seat.seat_no", name = "좌석 번호", role = "CODE", aliases = {"자리 번호"}, allowedOps = {"EQ", "NE", "IN"})
    private String seatNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SeatKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "seat.status", name = "좌석 상태", role = "STATUS", allowedValues = {"AVAILABLE", "OCCUPIED", "BLOCKED"}, aliases = {"빈자리", "사용 중", "점검"}, allowedOps = {"EQ", "NE", "IN"})
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
