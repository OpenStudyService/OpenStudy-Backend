package com.openstudy.modules.authorization.repository;

import java.util.*;
import java.sql.Statement;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.authorization.dto.PermissionPreset;
import com.openstudy.modules.authorization.dto.PermissionDefinition;

@Repository
public class PermissionPresetRepository {
    private static final Set<String> CODES = Set.of("members:write", "operations:write", "payments:read",
            "payments:write", "sales:read", "facilities:write", "notices:write", "people:manage", "ai:read");
    private static final String CREATE = "createPermissionPreset";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;
    public PermissionPresetRepository(JdbcTemplate jdbc, ObjectMapper json, AuditTrail audit, WorkspaceOutbox outbox,
            IdempotencyStore requests, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.audit = audit; this.outbox = outbox; this.requests = requests; this.clock = clock;
    }
    public List<PermissionPreset> list() {
        return jdbc.queryForList("SELECT id FROM permission_presets ORDER BY id", Long.class).stream().map(id -> find(id, false).orElseThrow()).toList();
    }
    public List<PermissionDefinition> definitions() {
        return jdbc.query("SELECT code,description,is_admin_only FROM permission_definitions ORDER BY code",
                (rs,n) -> new PermissionDefinition(rs.getString(1),rs.getString(2),rs.getBoolean(3)))
                .stream().filter(p -> CODES.contains(p.code())).toList();
    }
    public Optional<PermissionPreset> find(long id, boolean lock) {
        return jdbc.query("SELECT * FROM permission_presets WHERE id=?" + (lock ? " FOR UPDATE" : ""), (rs,n) -> new PermissionPreset(
                rs.getString("id"), ApiVersion.fromDatabase(rs.getLong("version")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("name"), "MANAGER".equals(rs.getString("role")) ? "admin" : "staff",
                rs.getBoolean("is_system"), jdbc.queryForList("SELECT p.code FROM permission_preset_grants g JOIN permission_definitions p ON p.id=g.permission_id WHERE g.preset_id=? ORDER BY p.code"
                        + (lock ? " FOR SHARE" : ""), String.class, id)), id).stream().findFirst();
    }
    public void validate(String role, List<String> grants) {
        for (String code : grants) {
            if (!CODES.contains(code)) throw ApiException.validation("permissions", "알 수 없는 권한입니다.");
            var flags = jdbc.queryForList("SELECT is_admin_only FROM permission_definitions WHERE code=?", Boolean.class, code);
            if (flags.isEmpty() || ("staff".equals(role) && flags.getFirst())) throw ApiException.validation("permissions", "해당 역할에 부여할 수 없는 권한입니다.");
        }
    }
    public long create(long actor, String name, String role, List<String> grants) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var sql = connection.prepareStatement("INSERT INTO permission_presets(name,role,created_by,updated_by) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            sql.setString(1,name); sql.setString(2,"admin".equals(role) ? "MANAGER" : "STAFF"); sql.setLong(3,actor); sql.setLong(4,actor); return sql;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue(); setGrants(id, grants); return id;
    }
    public void update(long actor, long id, String name, List<String> grants) {
        jdbc.update("UPDATE permission_presets SET name=?,updated_by=?,version=version+1 WHERE id=?", name, actor, id); setGrants(id,grants);
    }
    private void setGrants(long id, List<String> grants) {
        jdbc.update("DELETE FROM permission_preset_grants WHERE preset_id=?", id);
        for (String code : grants) jdbc.update("INSERT INTO permission_preset_grants(preset_id,permission_id) SELECT ?,id FROM permission_definitions WHERE code=?", id, code);
    }
    public void changed(long actor, long id, String action, Object before, Object after, String requestId) {
        audit.record(AuditEvent.change(actor, null, "permission-preset", id, action,
                json.writeValueAsString(before), json.writeValueAsString(after), requestId));
        outbox.invalidate(null, "permission-presets", id);
    }
    public Optional<PermissionPreset> replay(long actor, String key, Object input) {
        byte[] hash = hash(input);
        var stored = requests.lock(actor, CREATE, "all", key);
        if (stored.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(hash,stored.get().getRequestHash())) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        return Optional.of(json.readValue(stored.get().getResponseJson(),PermissionPreset.class));
    }
    public void remember(long actor, String key, Object input, PermissionPreset result) {
        requests.complete(actor, CREATE, "all", key, hash(input), 201, json.writeValueAsString(result),
                clock.instant().plus(Duration.ofHours(24)));
    }
    private byte[] hash(Object input) {
        try { return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
