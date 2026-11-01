package com.openstudy.common.audit;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

/** Single access path for audit_events. IDENTITY ids make each save an immediate INSERT in the caller's transaction. */
@Component
public class AuditTrail {
    private final AuditEventRepository events;

    public AuditTrail(AuditEventRepository events) { this.events = events; }

    public void record(AuditEvent event) { events.save(event); }

    /** Rows of one action on these entities, for a record whose handler and reason are kept nowhere else. */
    public List<AuditEvent> recorded(String entityType, String action, Collection<Long> entityIds) {
        return entityIds.isEmpty() ? List.of() : events.findByEntityTypeAndActionAndEntityIdIn(entityType, action, entityIds);
    }
}
