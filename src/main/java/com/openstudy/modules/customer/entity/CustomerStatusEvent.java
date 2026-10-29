package com.openstudy.modules.customer.entity;

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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Append-only status history; the reason here is the record of why a customer was restricted or deactivated. */
@Entity
@Table(name = "customer_status_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerStatusEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "previous_status", nullable = false, length = 30, updatable = false)
    private String previousStatus;
    @Column(name = "new_status", nullable = false, length = 30, updatable = false)
    private String newStatus;
    @Column(nullable = false, length = 500, updatable = false)
    private String reason;
    @Column(name = "actor_employee_id", nullable = false, updatable = false)
    private long actorEmployeeId;
    @Generated(event = EventType.INSERT) @Column(name = "changed_at", insertable = false, updatable = false)
    private Instant changedAt;

    public CustomerStatusEvent(long customerId, CustomerStatus previous, CustomerStatus next, String reason, long actor) {
        this.customerId = customerId; this.previousStatus = previous.name(); this.newStatus = next.name();
        this.reason = reason; this.actorEmployeeId = actor;
    }
}
