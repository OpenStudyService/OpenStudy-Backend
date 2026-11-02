package com.openstudy.modules.locker.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * What one payment bought, written once and never rewritten: period, terms and the rate of the locker at that moment
 * (M6-D02·D03). V1 checks days = terms × 28 and amount = terms × rate. initial_assignment_slot is a generated column that
 * keeps an assignment to one live initial charge, so it is not mapped here.
 */
@Entity
@Table(name = "locker_assignment_charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LockerAssignmentCharge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "assignment_id", nullable = false, updatable = false)
    private long assignmentId;
    @Column(name = "payment_id", nullable = false, updatable = false)
    private long paymentId;
    @Enumerated(EnumType.STRING) @Column(name = "charge_kind", nullable = false, updatable = false)
    private LockerChargeKind kind;
    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;
    @Column(name = "charged_4week_terms", nullable = false, updatable = false, columnDefinition = "int unsigned")
    private int terms;
    @Column(name = "rate_per_4weeks_snapshot_krw", nullable = false, updatable = false)
    private long ratePer4WeeksSnapshotKrw;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    private long amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private LockerChargeStatus status;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "applied_at", updatable = false)
    private Instant appliedAt;

    public LockerAssignmentCharge(long assignmentId, long paymentId, LockerChargeKind kind, LocalDate startDate, LocalDate endDate,
            int terms, long ratePer4WeeksSnapshotKrw, long amountKrw, boolean approved, Instant at) {
        this.assignmentId = assignmentId; this.paymentId = paymentId; this.kind = kind; this.startDate = startDate;
        this.endDate = endDate; this.terms = terms; this.ratePer4WeeksSnapshotKrw = ratePer4WeeksSnapshotKrw;
        this.amountKrw = amountKrw; this.status = approved ? LockerChargeStatus.APPLIED : LockerChargeStatus.FAILED;
        this.appliedAt = approved ? at : null;
    }
}
