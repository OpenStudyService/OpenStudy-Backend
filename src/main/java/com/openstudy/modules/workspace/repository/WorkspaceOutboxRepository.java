package com.openstudy.modules.workspace.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceOutboxRepository {
    public record Event(long id, Long branchId, Long targetEmployeeId, String entityType, Long entityId,
            String eventType, Instant occurredAt) {}
    private final JdbcTemplate jdbc;
    public WorkspaceOutboxRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Event> pending() {
        // There is intentionally no high-water ID cursor: an older uncommitted ID may become visible later.
        return jdbc.query("SELECT id,branch_id,target_employee_id,entity_type,entity_id,event_type,occurred_at "
                + "FROM workspace_event_outbox WHERE published_at IS NULL "
                + "AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6)) ORDER BY id LIMIT 100",
                (rs,n)->new Event(rs.getLong("id"),rs.getObject("branch_id",Long.class),rs.getObject("target_employee_id",Long.class),
                        rs.getString("entity_type"),rs.getObject("entity_id",Long.class),rs.getString("event_type"),
                        rs.getTimestamp("occurred_at").toInstant()));
    }

    public void published(long id) {
        jdbc.update("UPDATE workspace_event_outbox SET published_at=UTC_TIMESTAMP(6),attempts=attempts+1 "
                + "WHERE id=? AND published_at IS NULL",id);
    }
}
