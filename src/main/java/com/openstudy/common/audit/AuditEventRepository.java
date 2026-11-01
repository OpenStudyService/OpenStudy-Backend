package com.openstudy.common.audit;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByEntityTypeAndActionAndEntityIdIn(String entityType, String action, Collection<Long> entityIds);
}
