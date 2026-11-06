package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiTraceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTraceRepository extends JpaRepository<AiTraceEntity, String> {
    Optional<AiTraceEntity> findByRequestRef(String requestRef);
    Optional<AiTraceEntity> findByExecutionId(String executionId);
    List<AiTraceEntity> findAllByOrderByCreatedAtDesc();
    List<AiTraceEntity> findByEmployeeIdOrderByCreatedAtDesc(long employeeId);
}
