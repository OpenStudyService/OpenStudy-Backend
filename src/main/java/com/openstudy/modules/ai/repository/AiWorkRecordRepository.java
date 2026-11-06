package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiWorkRecordEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiWorkRecordRepository extends JpaRepository<AiWorkRecordEntity, String> {
    List<AiWorkRecordEntity> findByEmployeeIdOrderByPinnedDescCreatedAtDesc(long employeeId);
    List<AiWorkRecordEntity> findAllByOrderByPinnedDescCreatedAtDesc();
}
