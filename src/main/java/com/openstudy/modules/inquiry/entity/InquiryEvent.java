package com.openstudy.modules.inquiry.entity;

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
 * Append-only processing history (FR-INQ-03). A change of assignee keeps the status, so old and new status are equal and
 * the message says what changed; a re-answer carries the replaced answer in the message.
 */
@Entity
@Table(name = "inquiry_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InquiryEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "inquiry_id", nullable = false, updatable = false)
    private long inquiryId;
    @Column(name = "actor_employee_id", nullable = false, updatable = false)
    private long actorEmployeeId;
    @Enumerated(EnumType.STRING) @Column(name = "old_status", length = 30, updatable = false)
    private InquiryStatus oldStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false, length = 30, updatable = false)
    private InquiryStatus newStatus;
    @Column(columnDefinition = "text", updatable = false)
    private String message;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public InquiryEvent(long inquiryId, long actor, InquiryStatus oldStatus, InquiryStatus newStatus, String message) {
        this.inquiryId = inquiryId; this.actorEmployeeId = actor; this.oldStatus = oldStatus;
        this.newStatus = newStatus; this.message = message;
    }
}
