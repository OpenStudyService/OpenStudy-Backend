package com.openstudy.modules.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes authentication events to audit_events. Never stores email, address, password or session/CSRF material. */
@Repository
public class LoginAuditRepository {
    private final JdbcTemplate jdbc;

    public LoginAuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param actor authenticated employee only; null for failed or limited attempts
     * @param target employee the attempt was resolved to; null when no account matched
     */
    public void record(Long actor, Long target, String action, String reason, String result, String requestId) {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,entity_type,entity_id,action,reason,request_id,result) "
                + "VALUES (?,'employee',?,?,?,?,?)", actor, target, action, reason, requestId, result);
    }
}
