package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiPlanCacheEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPlanCacheRepository extends JpaRepository<AiPlanCacheEntity, Long> {

    Optional<AiPlanCacheEntity> findByQuestionKey(String questionKey);

    /** The live plans of one kind and version, most recently used first. */
    List<AiPlanCacheEntity> findByKindAndVersionKeyAndExpiresAtAfterOrderByLastUsedAtDesc(
            String kind,
            String versionKey,
            Instant now,
            Pageable page
    );

    long countByKind(String kind);

    List<AiPlanCacheEntity> findByKindOrderByLastUsedAtAsc(String kind, Pageable page);

    @Modifying
    @Query("DELETE FROM AiPlanCacheEntity e WHERE e.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
