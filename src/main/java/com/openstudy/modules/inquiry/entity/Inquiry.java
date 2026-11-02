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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Customer inquiry registered by proxy. There is no delete and the subject, body, branch, customer and creator never
 * change (collaboration-api.md). A re-answer replaces the answer; the previous one is kept in inquiry_events.
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(nullable = false, length = 200, updatable = false)
    private String subject;
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private InquirySource source;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private InquiryStatus status;
    @Column(name = "assigned_employee_id")
    private Long assignedEmployeeId;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Column(name = "answered_by")
    private Long answeredBy;
    @Column(name = "answer_body", columnDefinition = "text")
    private String answerBody;
    @Column(name = "answered_at")
    private Instant answeredAt;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked on every inquiry command. */
    @Version
    private long version;

    public Inquiry(long branchId, long customerId, String subject, String body, InquirySource source, InquiryStatus status,
            Long assignee, long createdBy) {
        this.branchId = branchId; this.customerId = customerId; this.subject = subject; this.body = body;
        this.source = source; this.status = status; this.assignedEmployeeId = assignee; this.createdBy = createdBy;
    }

    public void assign(Long assignee) { this.assignedEmployeeId = assignee; }

    public void changeStatus(InquiryStatus status) { this.status = status; }

    public void answer(String answer, long by, Instant at) {
        this.answerBody = answer; this.answeredBy = by; this.answeredAt = at; this.status = InquiryStatus.ANSWERED;
    }
}
