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

    /** Rows of [channel id, unread originals]: past the employee's position, not sent by them. Nothing unread, no row. */
    @Query("SELECT msg.conversationId, COUNT(msg) FROM Message msg, ConversationMember me WHERE me.conversationId = msg.conversationId "
            + "AND me.employeeId = :employee AND msg.conversationId IN :ids AND msg.parentMessageId IS NULL AND msg.senderEmployeeId <> :employee "
            + "AND (me.lastReadMessageId IS NULL OR msg.id > me.lastReadMessageId) GROUP BY msg.conversationId")
    List<Object[]> unreadOriginals(@Param("employee") long employee, @Param("ids") Collection<Long> ids);

    /** Rows of [channel id, unread replies of every thread]: past the baseline and their thread's position, not sent by the employee. */
    @Query("SELECT msg.conversationId, COUNT(msg) FROM Message msg JOIN ConversationMember me ON me.conversationId = msg.conversationId AND me.employeeId = :employee "
            + "LEFT JOIN ConversationThreadRead own ON own.conversationId = msg.conversationId AND own.employeeId = :employee AND own.parentMessageId = msg.parentMessageId "
            + "WHERE msg.conversationId IN :ids AND msg.parentMessageId IS NOT NULL AND msg.senderEmployeeId <> :employee "
            + "AND (me.threadReadBaselineMessageId IS NULL OR msg.id > me.threadReadBaselineMessageId) "
            + "AND (own.lastReadMessageId IS NULL OR msg.id > own.lastReadMessageId) GROUP BY msg.conversationId")
    List<Object[]> unreadReplies(@Param("employee") long employee, @Param("ids") Collection<Long> ids);

    /** Rows of [original id, unread replies], by the same rule as {@link #unreadReplies}. */
    @Query("SELECT msg.parentMessageId, COUNT(msg) FROM Message msg JOIN ConversationMember me ON me.conversationId = msg.conversationId AND me.employeeId = :employee "
            + "LEFT JOIN ConversationThreadRead own ON own.conversationId = msg.conversationId AND own.employeeId = :employee AND own.parentMessageId = msg.parentMessageId "
            + "WHERE msg.conversationId = :channel AND msg.parentMessageId IN :originals AND msg.senderEmployeeId <> :employee "
            + "AND (me.threadReadBaselineMessageId IS NULL OR msg.id > me.threadReadBaselineMessageId) "
            + "AND (own.lastReadMessageId IS NULL OR msg.id > own.lastReadMessageId) GROUP BY msg.parentMessageId")
    List<Object[]> unreadByThread(@Param("employee") long employee, @Param("channel") long channel, @Param("originals") Collection<Long> originals);

    /** The channel's newest message, original or reply; null for an empty channel. */
    @Query("SELECT MAX(m.id) FROM Message m WHERE m.conversationId = :channel")
    Long latestId(@Param("channel") long channel);

    /** Rows of [channel id, sent time of its newest message]. */
    @Query("SELECT m.conversationId, m.sentAt FROM Message m WHERE m.id IN "
            + "(SELECT MAX(x.id) FROM Message x WHERE x.conversationId IN :ids GROUP BY x.conversationId)")
    List<Object[]> lastSent(@Param("ids") Collection<Long> ids);
}
