package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiConversationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversationEntity, String> {
    List<AiConversationEntity> findByEmployeeIdOrderByCreatedAtDesc(long employeeId);
}
