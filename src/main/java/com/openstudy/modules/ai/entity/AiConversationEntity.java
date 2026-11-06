package com.openstudy.modules.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ai_conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiConversationEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    @Column(length = 200)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AiConversationEntity(String id, long employeeId, String scopeKey, String title, Instant now) {
        this.id = id;
        this.employeeId = employeeId;
        this.scopeKey = scopeKey;
        this.title = title;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(String title, Instant now) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        this.updatedAt = now;
    }
}
