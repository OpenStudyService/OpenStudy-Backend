package com.openstudy.common.audit;

import java.util.Collection;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Single access path for audit_events. IDENTITY ids make each save an immediate INSERT in the caller's transaction. */
@Component
@RequiredArgsConstructor
public class AuditTrail {

    private final AuditEventRepository auditEventRepository;

    public void record(AuditEvent event) {
        auditEventRepository.save(event);
    }

    /** Rows of one action on these entities, for a record whose handler and reason are kept nowhere else. */
    public List<AuditEvent> recorded(String entityType, String action, Collection<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }

        return auditEventRepository.findByEntityTypeAndActionAndEntityIdIn(entityType, action, entityIds);
    }
}
