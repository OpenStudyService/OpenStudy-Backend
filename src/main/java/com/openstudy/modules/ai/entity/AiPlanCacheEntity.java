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

/**
 * A cached query plan of an AI question: the registered analysis the model picked, or the QuerySpec·ViewSpec the
 * generation path compiled. Never a result; every reuse runs again under the requester's permissions.
 */
@Entity
@Table(name = "ai_plan_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiPlanCacheEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "version_key", nullable = false, length = 64)
    private String versionKey;

    @Column(name = "question_key", nullable = false, length = 64)
    private String questionKey;

    @Column(name = "question_text", nullable = false, length = 500)
    private String questionText;

    @Column(name = "vector_json", columnDefinition = "mediumtext", nullable = false)
    private String vectorJson;

    @Column(name = "analysis_id", length = 80)
    private String analysisId;

    @Column(name = "intent_domain", length = 30)
    private String intentDomain;

    @Column(name = "refund_basis_override", length = 40)
    private String refundBasisOverride;

    @Column(name = "zero_fill_override", nullable = false)
    private boolean zeroFillOverride;

    @Column(name = "query_spec_json", columnDefinition = "mediumtext")
    private String querySpecJson;

    @Column(name = "view_spec_json", columnDefinition = "mediumtext")
    private String viewSpecJson;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public AiPlanCacheEntity(String kind, String versionKey, String questionKey, String questionText, String vectorJson,
            Instant now, Instant expiresAt) {
        this.kind = kind;
        this.versionKey = versionKey;
        this.questionKey = questionKey;
        this.questionText = questionText;
        this.vectorJson = vectorJson;
        this.createdAt = now;
        this.lastUsedAt = now;
        this.expiresAt = expiresAt;
    }

    public void pick(String analysisId, String intentDomain, String refundBasisOverride, boolean zeroFillOverride) {
        this.analysisId = analysisId;
        this.intentDomain = intentDomain;
        this.refundBasisOverride = refundBasisOverride;
        this.zeroFillOverride = zeroFillOverride;
    }

    public void specs(String querySpecJson, String viewSpecJson, String intentDomain) {
        this.querySpecJson = querySpecJson;
        this.viewSpecJson = viewSpecJson;
        this.intentDomain = intentDomain;
    }

    /** A plan made again for the same question replaces the old one and starts a new lifetime. */
    public void renew(String vectorJson, Instant now, Instant expiresAt) {
        this.vectorJson = vectorJson;
        this.createdAt = now;
        this.lastUsedAt = now;
        this.expiresAt = expiresAt;
        this.hitCount = 0;
    }

    public void used(Instant now) {
        this.hitCount++;
        this.lastUsedAt = now;
    }
}
