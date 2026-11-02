package com.openstudy.modules.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.chat.entity.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {
    Optional<Message> findByIdAndConversationId(long id, long conversationId);

    boolean existsByIdAndConversationId(long id, long conversationId);

    /** The key column's collation decides equality, the same one uk_messages_1 enforces. */
    Optional<Message> findByConversationIdAndSenderEmployeeIdAndClientMessageKey(long conversationId, long sender, String key);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :channel AND m.parentMessageId IS NULL AND m.id < :before ORDER BY m.id DESC")
    List<Message> originals(@Param("channel") long channel, @Param("before") long before, Limit limit);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :channel AND m.parentMessageId = :parent AND m.id < :before ORDER BY m.id DESC")
    List<Message> replies(@Param("channel") long channel, @Param("parent") long parent, @Param("before") long before, Limit limit);

    /** Rows of [original id, reply count]. */
    @Query("SELECT m.parentMessageId, COUNT(m) FROM Message m WHERE m.parentMessageId IN :ids GROUP BY m.parentMessageId")
    List<Object[]> replyCounts(@Param("ids") Collection<Long> ids);

    /**
     * Rows of [channel id, unread messages]: after the employee's read position, replies included, not sent by them.
     * A channel with nothing unread has no row.
     */
    @Query("SELECT msg.conversationId, COUNT(msg) FROM Message msg, ConversationMember me WHERE me.conversationId = msg.conversationId "
            + "AND me.employeeId = :employee AND msg.conversationId IN :ids AND msg.senderEmployeeId <> :employee "
            + "AND (me.lastReadMessageId IS NULL OR msg.id > me.lastReadMessageId) GROUP BY msg.conversationId")
    List<Object[]> unreadCounts(@Param("employee") long employee, @Param("ids") Collection<Long> ids);

    /** Rows of [channel id, sent time of its newest message]. */
    @Query("SELECT m.conversationId, m.sentAt FROM Message m WHERE m.id IN "
            + "(SELECT MAX(x.id) FROM Message x WHERE x.conversationId IN :ids GROUP BY x.conversationId)")
    List<Object[]> lastSent(@Param("ids") Collection<Long> ids);
}
