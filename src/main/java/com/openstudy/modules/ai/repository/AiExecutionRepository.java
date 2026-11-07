package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiExecutionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExecutionRepository extends JpaRepository<AiExecutionEntity, String> {
    Optional<AiExecutionEntity> findByRequestRef(String requestRef);
    List<AiExecutionEntity> findByEmployeeIdOrderByCreatedAtDesc(long employeeId);

    /** The saved questions of one conversation in the order they were asked. */
    List<AiExecutionEntity> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    void deleteByConversationId(String conversationId);
}
