package com.openstudy.modules.workforce.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Days of leave granted to one employee by an origin branch (M12-D02), valid on [validFrom, validThrough]. The
 * remaining days change only together with a leave_balance_events row (M12-D07), so the grant equals its ledger.
 */
@Entity
@Table(name = "leave_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private long employeeId;
    @Column(name = "origin_branch_id", nullable = false, updatable = false)
    private long originBranchId;
    @Column(name = "grant_year", nullable = false, updatable = false, columnDefinition = "smallint unsigned")
    private int grantYear;
    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;
    @Column(name = "valid_through", nullable = false, updatable = false)
    private LocalDate validThrough;
    @Column(name = "granted_days", nullable = false, updatable = false, precision = 7, scale = 1)
    private BigDecimal grantedDays;
    @Column(name = "remaining_days", nullable = false, precision = 7, scale = 1)
    private BigDecimal remainingDays;
    @Column(nullable = false, updatable = false, length = 500)
    private String reason;
    @Column(name = "granted_by", nullable = false, updatable = false)
    private long grantedBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private long version;

    public LeaveGrant(long employeeId, long originBranchId, int grantYear, LocalDate validFrom, LocalDate validThrough,
            BigDecimal grantedDays, String reason, long grantedBy) {
        this.employeeId = employeeId; this.originBranchId = originBranchId; this.grantYear = grantYear;
        this.validFrom = validFrom; this.validThrough = validThrough; this.grantedDays = grantedDays;
        this.remainingDays = grantedDays; this.reason = reason; this.grantedBy = grantedBy;
    }

    /** By how many days the remaining balance moves: negative for a use, positive for a restore or an increase. */
    public void move(BigDecimal delta) {
        var next = remainingDays.add(delta);
        if (next.signum() < 0) throw new IllegalStateException("A grant's remaining days cannot become negative");
        remainingDays = next;
    }

    /** Covers every day of [first, last]. */
    public boolean covers(LocalDate first, LocalDate last) { return !validFrom.isAfter(first) && !validThrough.isBefore(last); }
}
