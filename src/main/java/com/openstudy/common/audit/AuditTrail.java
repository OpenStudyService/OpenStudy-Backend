package com.openstudy.common.audit;

import org.springframework.stereotype.Component;

/** Single write path for audit_events. IDENTITY ids make each save an immediate INSERT in the caller's transaction. */
@Component
public class AuditTrail {
    private final AuditEventRepository events;

    public AuditTrail(AuditEventRepository events) { this.events = events; }

    public void record(AuditEvent event) { events.save(event); }
}
