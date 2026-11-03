package com.openstudy.modules.workforce.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** Append-only shift history (FR-SHIFT-04), one row per change in the same transaction. The CANCELLED row is the cancellation record. */
@Entity
@Table(name = "shift_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftEvent {
    public enum Type { CREATED, CHANGED, CANCELLED, SWAPPED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "shift_id", nullable = false, updatable = false)
    private long shiftId;
    @Column(name = "actor_employee_id", nullable = false, updatable = false)
    private long actorEmployeeId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, updatable = false)
    private Type eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_json", updatable = false)
    private String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_json", updatable = false)
    private String afterJson;
    @Column(length = 500, updatable = false)
    private String reason;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public ShiftEvent(long shiftId, long actor, Type eventType, String beforeJson, String afterJson, String reason) {
        this.shiftId = shiftId; this.actorEmployeeId = actor; this.eventType = eventType;
        this.beforeJson = beforeJson; this.afterJson = afterJson; this.reason = reason;
    }
}
