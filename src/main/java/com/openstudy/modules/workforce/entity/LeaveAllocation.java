package com.openstudy.modules.workforce.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The days one approval took from one grant (M12-D06); restoredAt marks that an approval cancellation gave them back. */
@Entity
@Table(name = "leave_allocations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "leave_request_id", nullable = false, updatable = false)
    private long leaveRequestId;
    @Column(name = "leave_grant_id", nullable = false, updatable = false)
    private long leaveGrantId;
    @Column(name = "allocated_days", nullable = false, updatable = false, precision = 7, scale = 1)
    private BigDecimal allocatedDays;
    @Column(name = "restored_at")
    private Instant restoredAt;

    public LeaveAllocation(long leaveRequestId, long leaveGrantId, BigDecimal allocatedDays) {
        this.leaveRequestId = leaveRequestId; this.leaveGrantId = leaveGrantId; this.allocatedDays = allocatedDays;
    }

    public void restore(Instant at) {
        if (restoredAt != null) throw new IllegalStateException("An allocation is restored only once");
        restoredAt = at;
    }
}
