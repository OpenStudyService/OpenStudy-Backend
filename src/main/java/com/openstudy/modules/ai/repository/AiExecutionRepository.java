package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiExecutionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExecutionRepository extends JpaRepository<AiExecutionEntity, String> {
    Optional<AiExecutionEntity> findByRequestRef(String requestRef);
    List<AiExecutionEntity> findByEmployeeIdOrderByCreatedAtDesc(long employeeId);
}
