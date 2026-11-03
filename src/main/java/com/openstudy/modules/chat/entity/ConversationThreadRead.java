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
 * One employee's position in one thread. A row exists only once the reader moved past {@link
 * ConversationMember#getThreadReadBaselineMessageId()}, which covers every thread until then.
 */
@Entity
@Table(name = "conversation_thread_reads")
@IdClass(ConversationThreadReadId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationThreadRead {
    @Id @Column(name = "conversation_id")
    private long conversationId;
    @Id @Column(name = "employee_id")
    private long employeeId;
    @Id @Column(name = "parent_message_id")
    private long parentMessageId;
    @Column(name = "last_read_message_id", nullable = false)
    private long lastReadMessageId;
    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt;

    public ConversationThreadRead(long conversationId, long employeeId, long parentMessageId, long lastReadMessageId, Instant lastReadAt) {
        this.conversationId = conversationId; this.employeeId = employeeId; this.parentMessageId = parentMessageId;
        this.lastReadMessageId = lastReadMessageId; this.lastReadAt = lastReadAt;
    }

    /** The position only moves forward; an equal or earlier message changes nothing. */
    public void advance(long messageId, Instant at) {
        if (messageId <= lastReadMessageId) return;
        this.lastReadMessageId = messageId; this.lastReadAt = at;
    }
}
