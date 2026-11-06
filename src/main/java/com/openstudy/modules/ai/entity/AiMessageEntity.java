package com.openstudy.modules.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ai_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Column(length = 32, nullable = false)
    private String role;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AiMessageEntity(String conversationId, String role, String content, String executionId, Instant createdAt) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.executionId = executionId;
        this.createdAt = createdAt;
    }
}
