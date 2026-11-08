package com.openstudy.modules.workforce.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
 * One immutable row of a grant's balance ledger. event_key is unique, so a use or a restore of one request reaches a
 * grant once even if a state check were ever bypassed (M12-D07).
 */
@Entity
@Table(name = "leave_balance_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveBalanceEvent {
    public enum Type {
        GRANT("부여"), ADJUST("보정"), USE("사용"), RESTORE("복원");

        public final String label;
        Type(String label) { this.label = label; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "leave_grant_id", nullable = false, updatable = false)
    private long leaveGrantId;
    @Column(name = "leave_request_id", updatable = false)
    private Long leaveRequestId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, updatable = false)
    private Type eventType;
    @Column(name = "delta_days", nullable = false, updatable = false, precision = 7, scale = 1)
    private BigDecimal deltaDays;
    @Column(updatable = false, length = 500)
    private String reason;
    @Column(name = "actor_employee_id", nullable = false, updatable = false)
    private long actorEmployeeId;
    @Column(name = "event_key", nullable = false, updatable = false, length = 120)
    private String eventKey;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LeaveBalanceEvent(long leaveGrantId, Long leaveRequestId, Type eventType, BigDecimal deltaDays, String reason,
            long actorEmployeeId, String eventKey) {
        this.leaveGrantId = leaveGrantId; this.leaveRequestId = leaveRequestId; this.eventType = eventType;
        this.deltaDays = deltaDays; this.reason = reason; this.actorEmployeeId = actorEmployeeId; this.eventKey = eventKey;
    }
}
