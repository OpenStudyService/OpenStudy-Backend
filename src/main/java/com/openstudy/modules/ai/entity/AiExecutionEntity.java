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

    /** #96: the client's key for this question; a resubmission with the same key replays this execution. */
    @Column(name = "client_request_key", length = 100)
    private String clientRequestKey;

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



    /**
     * Conditions of the question that the analysis ID alone does not carry, kept so a reopened question keeps its
     * meaning (M10-D12 keeps the validated spec, not the result): the refund basis it asked for, or null for the default
     * processed-date basis, and whether it asked for zero-filled days.
     */
    @Column(name = "refund_basis", length = 40)
    private String refundBasis;

    @Column(name = "zero_fill_requested", nullable = false)
    private boolean zeroFillRequested;

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
            Instant createdAt,
            String refundBasis,
            boolean zeroFillRequested
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
        this.refundBasis = refundBasis;
        this.zeroFillRequested = zeroFillRequested;
    }

    public void assignClientRequestKey(String key) {
        this.clientRequestKey = key;
    }

    public void markCanceled() {
        this.status = "CANCELED";
    }
}
