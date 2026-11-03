package com.openstudy.modules.chat.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One employee's place in a channel. The row outlives the participation because messages reference it: leaving sets
 * {@code leftAt}, joining again clears it and moves the read positions (M7-D09), and {@code joinedAt} stays as it was.
 * {@code lastReadMessageId} is the main feed's position. {@code threadReadBaselineMessageId} covers every thread at once:
 * replies up to it are read, whichever thread they are in. Only the V6 migration and joining again set it, never reading.
 */
@Entity
@Table(name = "conversation_members")
@IdClass(ConversationMemberId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationMember {
    @Id @Column(name = "conversation_id")
    private long conversationId;
    @Id @Column(name = "employee_id")
    private long employeeId;
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
    @Column(name = "left_at")
    private Instant leftAt;
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;
    @Column(name = "last_read_at")
    private Instant lastReadAt;
    @Column(name = "thread_read_baseline_message_id")
    private Long threadReadBaselineMessageId;

    public ConversationMember(long conversationId, long employeeId, Instant joinedAt) {
        this.conversationId = conversationId; this.employeeId = employeeId; this.joinedAt = joinedAt;
    }

    public boolean active() { return leftAt == null; }

    public void leave(Instant at) { this.leftAt = at; }

    /**
     * M7-D09: everything sent so far counts as read, the main feed and every thread, while the earlier messages stay open
     * to the reader. {@code latest} is the channel's newest message; an empty channel leaves the positions as they are.
     */
    public void rejoin(Long latest, Instant at) {
        this.leftAt = null;
        if (latest == null) return;
        advanceReadPosition(latest, at);
        this.threadReadBaselineMessageId = latest;
    }

    /** The position only moves forward; an equal or earlier message changes nothing. */
    public void advanceReadPosition(long messageId, Instant at) {
        if (lastReadMessageId != null && messageId <= lastReadMessageId) return;
        this.lastReadMessageId = messageId; this.lastReadAt = at;
    }
}
