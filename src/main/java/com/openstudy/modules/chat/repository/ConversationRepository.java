package com.openstudy.modules.chat.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.chat.entity.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM conversations WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** Channels with an open participation of the employee, by name. The caller re-checks the current eligibility. */
    @Query("SELECT c FROM Conversation c, ConversationMember m WHERE m.conversationId = c.id AND m.employeeId = :employee "
            + "AND m.leftAt IS NULL AND c.kind <> com.openstudy.modules.chat.entity.ConversationKind.DM ORDER BY c.name, c.id")
    List<Conversation> joined(@Param("employee") long employee);

    /** JPA-01 native exception: page numbers run past what a Pageable's int offset holds. */
    @Query(value = "SELECT c.* FROM conversations c WHERE (:owner AND c.kind = 'GLOBAL_CHANNEL') OR "
            + "(c.kind = 'BRANCH_CHANNEL' AND c.branch_id IN (:branches)) ORDER BY c.name, c.id LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Conversation> manageable(@Param("owner") boolean owner, @Param("branches") Collection<Long> branches,
            @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = "SELECT COUNT(*) FROM conversations c WHERE (:owner AND c.kind = 'GLOBAL_CHANNEL') OR "
            + "(c.kind = 'BRANCH_CHANNEL' AND c.branch_id IN (:branches))", nativeQuery = true)
    long countManageable(@Param("owner") boolean owner, @Param("branches") Collection<Long> branches);

    /** Advances channels whose member list changed; a managed entity has to be refreshed afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Conversation c SET c.version = c.version + 1 WHERE c.id IN :ids")
    int incrementVersions(@Param("ids") Collection<Long> ids);
}
