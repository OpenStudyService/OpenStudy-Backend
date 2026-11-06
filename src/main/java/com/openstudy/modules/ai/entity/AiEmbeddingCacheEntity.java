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
@Table(name = "ai_embedding_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiEmbeddingCacheEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", length = 80, nullable = false)
    private String modelId;

    @Column(name = "text_hash", length = 64, nullable = false)
    private String textHash;

    @Column(nullable = false)
    private short dimensions;

    @Column(name = "vector_json", columnDefinition = "mediumtext", nullable = false)
    private String vectorJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AiEmbeddingCacheEntity(String modelId, String textHash, short dimensions, String vectorJson, Instant createdAt) {
        this.modelId = modelId;
        this.textHash = textHash;
        this.dimensions = dimensions;
        this.vectorJson = vectorJson;
        this.createdAt = createdAt;
    }
}
