package com.openstudy.modules.chat.entity;

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
 * Immutable: there is no edit or delete. The ID is the only order. A task card keeps its own {@code relatedTaskId}, which
 * this module neither writes nor exposes until #77.
 */
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;
    @Column(name = "sender_employee_id", nullable = false, updatable = false)
    private long senderEmployeeId;
    @Column(name = "parent_message_id", updatable = false)
    private Long parentMessageId;
    @Column(name = "related_task_id", updatable = false)
    private Long relatedTaskId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private MessageKind kind;
    @Column(columnDefinition = "text", updatable = false)
    private String body;
    @Column(name = "client_message_key", nullable = false, length = 100, updatable = false)
    private String clientMessageKey;
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    public static Message text(long conversation, long sender, Long parent, String body, String clientKey, Instant sentAt) {
        var message = new Message();
        message.conversationId = conversation; message.senderEmployeeId = sender; message.parentMessageId = parent;
        message.kind = MessageKind.TEXT; message.body = body; message.clientMessageKey = clientKey; message.sentAt = sentAt;
        return message;
    }
}
