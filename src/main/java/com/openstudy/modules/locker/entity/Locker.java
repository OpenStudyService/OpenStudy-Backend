package com.openstudy.modules.locker.entity;

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

/**
 * Branch locker ledger row. There is no delete: assignments reference the row, so a locker is closed by blocking it.
 * The branch never changes. The rate is the current sale price (M6-D02); each charge keeps its own snapshot.
 */
@Entity
@Table(name = "lockers")
@SemanticEntity(
        id = "locker",
        name = "사물함",
        description = "지점별 사물함 원장",
        aliases = {"락커"},
        domain = "lockers"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Locker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "locker.id", name = "사물함 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "locker.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "locker.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "사물함 소속 지점")
    private long branchId;
    @Column(name = "locker_no", nullable = false, length = 40)
    @SemanticField(id = "locker.locker_no", name = "사물함 번호", role = "CODE", allowedOps = {"EQ", "NE", "IN"})
    private String lockerNo;
    @Column(name = "rate_per_4weeks_krw", nullable = false)
    @SemanticField(id = "locker.rate_per_4weeks_krw", name = "4주 요금", role = "AMOUNT", unit = "KRW", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private long ratePer4WeeksKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "locker.status", name = "사물함 상태", role = "STATUS", allowedValues = {"AVAILABLE", "BLOCKED"}, allowedOps = {"EQ", "NE", "IN"})
    private LockerState status;
    /** Exposed to clients as version + 1 and checked on every change. */
    @Version
    private long version;

    public Locker(long branchId, String lockerNo, long ratePer4WeeksKrw, LockerState status) {
        this.branchId = branchId; this.lockerNo = lockerNo; this.ratePer4WeeksKrw = ratePer4WeeksKrw; this.status = status;
    }

    public void change(String lockerNo, long ratePer4WeeksKrw) {
        this.lockerNo = lockerNo; this.ratePer4WeeksKrw = ratePer4WeeksKrw;
    }

    public void changeStatus(LockerState status) { this.status = status; }
}
