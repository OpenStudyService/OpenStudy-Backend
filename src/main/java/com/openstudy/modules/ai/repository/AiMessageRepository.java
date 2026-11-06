package com.openstudy.modules.ai.repository;

import com.openstudy.modules.ai.entity.AiMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessageEntity, Long> {
    List<AiMessageEntity> findByConversationIdOrderByIdAsc(String conversationId);
}
