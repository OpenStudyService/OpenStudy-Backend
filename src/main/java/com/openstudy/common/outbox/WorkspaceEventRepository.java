package com.openstudy.common.outbox;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceEventRepository extends JpaRepository<WorkspaceEvent, Long> {
    // Database UTC time, not the application Clock, so delivery timing is independent of test clocks.
    // There is intentionally no high-water ID cursor: an older uncommitted ID may become visible later.
    @Query("SELECT e FROM WorkspaceEvent e WHERE e.publishedAt IS NULL "
            + "AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= cast(sql('UTC_TIMESTAMP(6)') as Instant)) ORDER BY e.id")
    List<WorkspaceEvent> findDeliverable(Limit limit);

    /** Own transaction when called from the non-transactional SSE poller, like the former auto-committed UPDATE. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WorkspaceEvent e SET e.publishedAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), e.attempts = e.attempts + 1 "
            + "WHERE e.id = :id AND e.publishedAt IS NULL")
    int markPublished(@Param("id") long id);

    /**
     * JPA-01 native exception: one set-based INSERT ... SELECT keeps "every branch at commit time" atomic,
     * and an HQL insert-select would need the branch module's entity inside common.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) "
            + "SELECT id,:entityType,:entityId,'INVALIDATE' FROM branches", nativeQuery = true)
    int invalidateEveryBranch(@Param("entityType") String entityType, @Param("entityId") long entityId);
}
