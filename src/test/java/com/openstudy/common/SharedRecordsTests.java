package com.openstudy.common;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditResult;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceEventRepository;
import com.openstudy.common.outbox.WorkspaceEventType;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 1: audit, idempotency and outbox rows written through JPA must match what the former SQL stored. */
@MySqlIntegrationTest
@Transactional
class SharedRecordsTests {

    @Autowired AuditTrail audit;
    @Autowired IdempotencyStore requests;
    @Autowired WorkspaceOutbox outbox;
    @Autowired WorkspaceEventRepository events;
    @Autowired JdbcTemplate jdbc;
    long actor;
    long branch;

    @BeforeEach
    void seed() {
        String email = "shared-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,'hash','공통 기록','공통','EMPLOYEE','ACTIVE')", email);
        actor = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        String code = "SR-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'공통 지점','주소')", code);
        branch = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
    }

    @Test
    void auditSnapshotsAreStoredAsJsonDocumentsNotAsEncodedStrings() {
        audit.record(AuditEvent.change(actor, branch, "branches", branch, "UPDATED", "{\"name\":\"전\"}",
                "{\"name\":\"후\"}", "req-1"));
        audit.record(AuditEvent.attempt(null, "employee", null, "LOGIN", "BAD_CREDENTIALS", "req-2", AuditResult.FAILED));

        var change = jdbc.queryForMap("SELECT JSON_TYPE(before_json) t, after_json->>'$.name' name, result, occurred_at "
                + "FROM audit_events WHERE request_id='req-1'");
        assertThat(change).containsEntry("t", "OBJECT").containsEntry("name", "후").containsEntry("result", "SUCCESS");
        assertThat(change.get("occurred_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT before_json,after_json,result,reason FROM audit_events WHERE request_id='req-2'"))
                .containsEntry("before_json", null).containsEntry("after_json", null)
                .containsEntry("result", "FAILED").containsEntry("reason", "BAD_CREDENTIALS");
    }

    @Test
    void idempotencyRecordRoundTripsBinaryHashAndCaseSensitiveKey() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest("input".getBytes());
        Instant expires = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        requests.complete(actor, "branches:create", "all", "Key-1", hash, 201, "{\"id\":\"7\"}", expires);

        var stored = requests.lock(actor, "branches:create", "all", "Key-1").orElseThrow();
        assertThat(stored.getRequestHash()).isEqualTo(hash);
        // Callers parse the text again, so only the document matters, not MySQL's whitespace normalization.
        assertThat(stored.getResponseJson().replace(" ", "")).isEqualTo("{\"id\":\"7\"}");
        assertThat(jdbc.queryForMap("SELECT JSON_TYPE(response_json) t, response_json->>'$.id' id FROM idempotency_records "
                + "WHERE id=?", stored.getId())).containsEntry("t", "OBJECT").containsEntry("id", "7");
        assertThat(stored.getHttpStatus()).isEqualTo(201);
        assertThat(stored.getExpiresAt()).isEqualTo(expires);
        assertThat(requests.lock(actor, "branches:create", "all", "key-1")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT LENGTH(request_hash) FROM idempotency_records WHERE id=?", Integer.class,
                stored.getId())).isEqualTo(32);

        requests.discard(stored);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE id=?", Integer.class, stored.getId()))
                .isZero();
    }

    @Test
    void outboxRowsAreDeliveredOnceAndMarkedWithDatabaseTime() {
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM workspace_event_outbox", Long.class);
        outbox.invalidate(branch, "branches", branch);
        outbox.notifyEmployee(branch, actor, "employee", actor, WorkspaceEventType.AUTHORIZATION_CHANGED);

        var mine = events.findDeliverable(org.springframework.data.domain.Limit.of(1000)).stream()
                .filter(e -> e.getId() > before).toList();
        assertThat(mine).extracting(e -> e.getEventType()).containsExactly(WorkspaceEventType.INVALIDATE,
                WorkspaceEventType.AUTHORIZATION_CHANGED);
        assertThat(mine.get(1).getTargetEmployeeId()).isEqualTo(actor);

        long id = mine.getFirst().getId();
        assertThat(events.markPublished(id)).isEqualTo(1);
        assertThat(events.markPublished(id)).isZero();
        assertThat(jdbc.queryForMap("SELECT attempts, published_at IS NOT NULL published FROM workspace_event_outbox WHERE id=?", id))
                .containsEntry("attempts", 1L).containsEntry("published", 1L);
    }

    @Test
    void everyBranchInvalidationWritesOneRowPerExistingBranch() {
        long branches = jdbc.queryForObject("SELECT COUNT(*) FROM branches", Long.class);
        long before = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM workspace_event_outbox", Long.class);
        outbox.invalidateEveryBranch("product-categories", 42);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT branch_id) FROM workspace_event_outbox "
                + "WHERE id>? AND entity_type='product-categories' AND entity_id=42", Long.class, before)).isEqualTo(branches);
    }
}
