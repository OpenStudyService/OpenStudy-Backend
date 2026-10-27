package com.openstudy.common.outbox;

import org.springframework.stereotype.Component;

/** Single write path for workspace_event_outbox. Rows commit or roll back with the caller's change. */
@Component
public class WorkspaceOutbox {
    private final WorkspaceEventRepository events;

    public WorkspaceOutbox(WorkspaceEventRepository events) { this.events = events; }

    /** Tells the branch's streams (every stream when branch is null) to reload the entity's data. */
    public void invalidate(Long branch, String entityType, Long entityId) {
        events.save(new WorkspaceEvent(branch, null, entityType, entityId, WorkspaceEventType.INVALIDATE));
    }

    /** Addresses one employee's streams, e.g. to re-evaluate their own authorization. */
    public void notifyEmployee(Long branch, long target, String entityType, Long entityId, WorkspaceEventType type) {
        events.save(new WorkspaceEvent(branch, target, entityType, entityId, type));
    }

    public void invalidateEveryBranch(String entityType, long entityId) {
        events.invalidateEveryBranch(entityType, entityId);
    }
}
