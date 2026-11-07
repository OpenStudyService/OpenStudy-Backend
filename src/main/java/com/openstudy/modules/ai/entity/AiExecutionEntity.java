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
@Table(name = "ai_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiExecutionEntity {
    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "request_ref", length = 64, nullable = false, unique = true)
    private String requestRef;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "scope_key", length = 64, nullable = false)
    private String scopeKey;

    @Column(name = "branch_ids_csv", length = 255, nullable = false)
    private String branchIdsCsv;

    @Column(length = 1000, nullable = false)
    private String question;

    @Column(name = "poc_code", length = 32)
    private String pocCode;

    @Column(name = "analysis_id", length = 120)
    private String analysisId;

    @Column(name = "intent_domain", length = 64)
    private String intentDomain;

    @Column(length = 40, nullable = false)
    private String status;

    @Column(name = "result_category", length = 80)
    private String resultCategory;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "query_spec_json", columnDefinition = "text")
    private String querySpecJson;

    @Column(name = "view_spec_json", columnDefinition = "text")
    private String viewSpecJson;



    @Column(name = "as_of_utc", nullable = false)
    private Instant asOfUtc;

    @Column(name = "snapshot_version", length = 64, nullable = false)
    private String snapshotVersion;

    @Column(name = "policy_version", length = 64, nullable = false)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AiExecutionEntity(
            String id,
            String requestRef,
            String conversationId,
            long employeeId,
            String scopeKey,
            String branchIdsCsv,
            String question,
            String pocCode,
            String analysisId,
            String intentDomain,
            String status,
            String resultCategory,
            String errorCode,
            String querySpecJson,
            String viewSpecJson,
            Instant asOfUtc,
            String snapshotVersion,
            String policyVersion,
            Instant createdAt
    ) {
        this.id = id;
        this.requestRef = requestRef;
        this.conversationId = conversationId;
        this.employeeId = employeeId;
        this.scopeKey = scopeKey;
        this.branchIdsCsv = branchIdsCsv;
        this.question = question;
        this.pocCode = pocCode;
        this.analysisId = analysisId;
        this.intentDomain = intentDomain;
        this.status = status;
        this.resultCategory = resultCategory;
        this.errorCode = errorCode;
        this.querySpecJson = querySpecJson;
        this.viewSpecJson = viewSpecJson;
        this.asOfUtc = asOfUtc;
        this.snapshotVersion = snapshotVersion;
        this.policyVersion = policyVersion;
        this.createdAt = createdAt;
    }

    public void markCanceled() {
        this.status = "CANCELED";
    }
}
