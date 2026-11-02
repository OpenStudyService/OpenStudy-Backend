package com.openstudy.modules.ticket.entity;

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

/**
 * Immutable row of the pass balance ledger. A USE row's key is fixed to the seat session, so uk_pass_balance_events_1
 * is the last line of defence against charging one session twice (seat-usage-api.md "원장과 반복 퇴실").
 */
@Entity
@Table(name = "pass_balance_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PassBalanceEvent {
    public enum Kind { USE, ADJUST, RESTORE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "pass_id", nullable = false, updatable = false)
    private long passId;
    @Column(name = "seat_session_id", updatable = false)
    private Long seatSessionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private Kind kind;
    @Column(name = "delta_minutes", nullable = false, updatable = false)
    private long deltaMinutes;
    @Column(name = "delta_krw", nullable = false, updatable = false)
    private long deltaKrw;
    @Column(name = "actor_employee_id", updatable = false)
    private Long actorEmployeeId;
    @Column(length = 500, updatable = false)
    private String reason;
    @Column(name = "event_key", nullable = false, length = 120, updatable = false)
    private String eventKey;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** One USE row per seat session: the amounts are what was actually deducted, stored as negative deltas. */
    public static PassBalanceEvent use(long passId, long seatSessionId, long minutes, long krw, long actor, String reason, Instant at) {
        if (minutes < 0 || krw < 0 || minutes + krw == 0) throw new IllegalArgumentException("A USE row records a deduction");
        var event = new PassBalanceEvent();
        event.passId = passId; event.seatSessionId = seatSessionId; event.kind = Kind.USE;
        event.deltaMinutes = -minutes; event.deltaKrw = -krw; event.actorEmployeeId = actor; event.reason = reason;
        event.eventKey = "USE-" + seatSessionId; event.createdAt = at;
        return event;
    }

    /** One ADJUST row per effective correction: after minus before, so a validity-only change has both deltas at zero. */
    public static PassBalanceEvent adjust(long passId, long deltaMinutes, long deltaKrw, long actor, String reason, String eventKey,
            Instant at) {
        var event = new PassBalanceEvent();
        event.passId = passId; event.kind = Kind.ADJUST; event.deltaMinutes = deltaMinutes; event.deltaKrw = deltaKrw;
        event.actorEmployeeId = actor; event.reason = reason; event.eventKey = eventKey; event.createdAt = at;
        return event;
    }
}
