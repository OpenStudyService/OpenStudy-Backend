package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiEmbeddingCacheEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiEmbeddingCacheRepository extends JpaRepository<AiEmbeddingCacheEntity, Long> {
    Optional<AiEmbeddingCacheEntity> findByModelIdAndTextHash(String modelId, String textHash);
}
