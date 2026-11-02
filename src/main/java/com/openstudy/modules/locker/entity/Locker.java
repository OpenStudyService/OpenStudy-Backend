package com.openstudy.modules.locker.entity;

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
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Locker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "locker_no", nullable = false, length = 40)
    private String lockerNo;
    @Column(name = "rate_per_4weeks_krw", nullable = false)
    private long ratePer4WeeksKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
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
