package com.openstudy.modules.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditResult;
import com.openstudy.common.audit.AuditTrail;

/** Writes authentication events to audit_events. Never stores email, address, password or session/CSRF material. */
@Repository
@RequiredArgsConstructor
public class LoginAuditRepository {

    private static final String ENTITY = "employee";

    private final AuditTrail auditTrail;

    /**
     * @param actor authenticated employee only; null for failed or limited attempts
     * @param target employee the attempt was resolved to; null when no account matched
     */
    public void record(Long actor, Long target, String action, String reason, String result, String requestId) {
        auditTrail.record(AuditEvent.attempt(
                actor,
                ENTITY,
                target,
                action,
                reason,
                requestId,
                AuditResult.valueOf(result)
        ));
    }
}
