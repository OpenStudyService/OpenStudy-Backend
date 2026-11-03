package com.openstudy.modules.chat.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.chat.entity.ConversationThreadRead;
import com.openstudy.modules.chat.entity.ConversationThreadReadId;

public interface ConversationThreadReadRepository extends JpaRepository<ConversationThreadRead, ConversationThreadReadId> {
    List<ConversationThreadRead> findByConversationIdAndEmployeeIdAndParentMessageIdIn(long conversationId, long employeeId, Collection<Long> parents);
}
