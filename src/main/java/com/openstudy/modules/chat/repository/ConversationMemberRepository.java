package com.openstudy.modules.chat.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.chat.entity.ConversationMember;
import com.openstudy.modules.chat.entity.ConversationMemberId;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMemberId> {
    Optional<ConversationMember> findByConversationIdAndEmployeeIdAndLeftAtIsNull(long conversationId, long employeeId);

    List<ConversationMember> findByConversationId(long conversationId);

    List<ConversationMember> findByConversationIdAndLeftAtIsNull(long conversationId);

    List<ConversationMember> findByEmployeeIdAndLeftAtIsNullAndConversationIdIn(long employeeId, Collection<Long> conversationIds);

    /** Rows of [conversation id, open participations]. */
    @Query("SELECT m.conversationId, COUNT(m) FROM ConversationMember m WHERE m.conversationId IN :ids AND m.leftAt IS NULL "
            + "GROUP BY m.conversationId")
    List<Object[]> countActive(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ConversationMember m SET m.leftAt = :at WHERE m.employeeId = :employee AND m.conversationId IN :ids AND m.leftAt IS NULL")
    int end(@Param("employee") long employee, @Param("ids") Collection<Long> ids, @Param("at") Instant at);
}
