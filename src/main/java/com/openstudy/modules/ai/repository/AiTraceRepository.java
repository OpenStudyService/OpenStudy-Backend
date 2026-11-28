package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiTraceEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AiTraceRepository extends JpaRepository<AiTraceEntity, String> {

    Optional<AiTraceEntity> findByRequestRef(String requestRef);
    Optional<AiTraceEntity> findByExecutionId(String executionId);
    List<AiTraceEntity> findAllByOrderByCreatedAtDesc();
    List<AiTraceEntity> findByEmployeeIdOrderByCreatedAtDesc(long employeeId);

    /**
     * M10-D04: the question text of a trace is kept for 30 days ({@code question_expires_at}); after that only the text
     * is removed. Request IDs, stages, scope and the security-gate record stay.
     */
    @Modifying
    @Query(value = "UPDATE ai_traces SET sanitized_question = NULL "
            + "WHERE question_expires_at <= :now AND sanitized_question IS NOT NULL", nativeQuery = true)
    int clearExpiredQuestions(Instant now);
}
