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
@Table(name = "ai_export_approvals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiExportApprovalEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "execution_id", length = 64, nullable = false)
    private String executionId;

    @Column(name = "dataset_id", length = 80, nullable = false)
    private String datasetId;

    @Column(name = "requester_employee_id", nullable = false)
    private long requesterEmployeeId;

    @Column(name = "approver_employee_id")
    private Long approverEmployeeId;

    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    @Column(name = "branch_ids_csv", length = 255, nullable = false)
    private String branchIdsCsv;

    @Column(length = 500, nullable = false)
    private String reason;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public AiExportApprovalEntity(
            String id,
            String executionId,
            String datasetId,
            long requesterEmployeeId,
            String scopeKey,
            String branchIdsCsv,
            String reason,
            int rowCount,
            Instant requestedAt,
            Instant expiresAt
    ) {
        this.id = id;
        this.executionId = executionId;
        this.datasetId = datasetId;
        this.requesterEmployeeId = requesterEmployeeId;
        this.scopeKey = scopeKey;
        this.branchIdsCsv = branchIdsCsv;
        this.reason = reason;
        this.rowCount = rowCount;
        this.status = "PENDING";
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    public void decide(long approverEmployeeId, boolean approved, String decisionNote, Instant decidedAt) {
        this.approverEmployeeId = approverEmployeeId;
        this.status = approved ? "APPROVED" : "REJECTED";
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
    }
}
