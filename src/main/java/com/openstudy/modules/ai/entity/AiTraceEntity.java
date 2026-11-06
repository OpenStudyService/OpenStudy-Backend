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
@Table(name = "ai_traces")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTraceEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "request_ref", length = 64, nullable = false)
    private String requestRef;

    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    @Column(name = "sanitized_question", length = 1000)
    private String sanitizedQuestion;

    @Column(length = 40, nullable = false)
    private String status;

    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs;

    @Column(name = "stages_json", columnDefinition = "mediumtext", nullable = false)
    private String stagesJson;

    @Column(name = "retrieval_json", columnDefinition = "mediumtext", nullable = false)
    private String retrievalJson;

    @Column(name = "security_gate_json", columnDefinition = "text", nullable = false)
    private String securityGateJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "question_expires_at", nullable = false)
    private Instant questionExpiresAt;

    public AiTraceEntity(
            String id,
            String requestRef,
            String executionId,
            long employeeId,
            String scopeKey,
            String sanitizedQuestion,
            String status,
            long totalDurationMs,
            String stagesJson,
            String retrievalJson,
            String securityGateJson,
            Instant createdAt,
            Instant questionExpiresAt
    ) {
        this.id = id;
        this.requestRef = requestRef;
        this.executionId = executionId;
        this.employeeId = employeeId;
        this.scopeKey = scopeKey;
        this.sanitizedQuestion = sanitizedQuestion;
        this.status = status;
        this.totalDurationMs = totalDurationMs;
        this.stagesJson = stagesJson;
        this.retrievalJson = retrievalJson;
        this.securityGateJson = securityGateJson;
        this.createdAt = createdAt;
        this.questionExpiresAt = questionExpiresAt;
    }
}
