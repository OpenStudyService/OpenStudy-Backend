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
@Table(name = "ai_work_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiWorkRecordEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "execution_id", length = 64, nullable = false)
    private String executionId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 1000, nullable = false)
    private String question;

    @Column(length = 64, nullable = false)
    private String domain;

    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    @Column(name = "branch_ids_csv", length = 255, nullable = false)
    private String branchIdsCsv;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "snapshot_json", columnDefinition = "mediumtext", nullable = false)
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AiWorkRecordEntity(
            String id,
            String executionId,
            long employeeId,
            String title,
            String question,
            String domain,
            String scopeKey,
            String branchIdsCsv,
            boolean pinned,
            String snapshotJson,
            Instant now
    ) {
        this.id = id;
        this.executionId = executionId;
        this.employeeId = employeeId;
        this.title = title;
        this.question = question;
        this.domain = domain;
        this.scopeKey = scopeKey;
        this.branchIdsCsv = branchIdsCsv;
        this.pinned = pinned;
        this.archived = false;
        this.snapshotJson = snapshotJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateState(boolean pinned, boolean archived, Instant now) {
        this.pinned = pinned;
        this.archived = archived;
        this.updatedAt = now;
    }
}
