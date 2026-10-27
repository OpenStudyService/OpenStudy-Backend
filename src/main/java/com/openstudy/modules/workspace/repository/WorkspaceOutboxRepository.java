package com.openstudy.modules.workspace.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import com.openstudy.common.outbox.WorkspaceEventRepository;

/** Delivery side of workspace_event_outbox; every module writes through {@link com.openstudy.common.outbox.WorkspaceOutbox}. */
@Repository
public class WorkspaceOutboxRepository {
    public record Event(long id, Long branchId, Long targetEmployeeId, String entityType, Long entityId,
            String eventType, Instant occurredAt) {}
    private final WorkspaceEventRepository events;
    public WorkspaceOutboxRepository(WorkspaceEventRepository events) { this.events=events; }

    public List<Event> pending() {
        return events.findDeliverable(Limit.of(100)).stream().map(e->new Event(e.getId(),e.getBranchId(),
                e.getTargetEmployeeId(),e.getEntityType(),e.getEntityId(),e.getEventType().name(),e.getOccurredAt())).toList();
    }

    public void published(long id) {
        events.markPublished(id);
    }
}
