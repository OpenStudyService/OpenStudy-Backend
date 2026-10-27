package com.openstudy.modules.authorization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.authorization.dto.PermissionPreset;
import com.openstudy.modules.authorization.service.PermissionPresetService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5-1: preset behaviors of the former SQL that the HTTP tests do not pin down — creation, the (name, role)
 * duplicate rule, version +1 for an identical update, partial grant replacement, and list output equal to the
 * former query (the oracle below is the removed SQL and mapping).
 */
@MySqlIntegrationTest
@Transactional
class PermissionPresetJpaBehaviorTests {

    @Autowired PermissionPresetService presets;
    @Autowired JdbcTemplate jdbc;
    long owner;

    @BeforeEach
    void owner() {
        // The single-owner slot may already be taken by fixtures of other suites; reuse that owner if so.
        var existing = jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE'", Long.class);
        if (!existing.isEmpty()) { owner = existing.getFirst(); return; }
        String email = "preset-jpa-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,'hash','소유자','소유자','OWNER','ACTIVE')", email);
        owner = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    @Test
    void createStoresSortedGrantsReplaysByKeyAndRejectsTheSameNameAndRole() {
        String name = name();
        String key = key();
        var created = presets.create(owner, Map.of("name", name, "role", "admin", "permissions", List.of("sales:read", "members:write")), key, "create");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.permissions()).containsExactly("members:write", "sales:read");
        assertThat(grants(created)).containsExactly("members:write", "sales:read");
        assertThat(presets.create(owner, Map.of("name", name, "role", "admin", "permissions", List.of("sales:read", "members:write")), key, "replay"))
                .isEqualTo(created);

        assertThatThrownBy(() -> presets.create(owner, Map.of("name", name, "role", "admin", "permissions", List.of()), key(), "dup"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("같은 이름과 역할"));
        // The unique key is (name, role): the same name with the other role is a different preset.
        assertThat(presets.create(owner, Map.of("name", name, "role", "staff", "permissions", List.of()), key(), "other-role").role())
                .isEqualTo("staff");
    }

    @Test
    void identicalUpdateAdvancesOnceAndGrantRowsChangeOnlyWhereTheyDiffer() {
        var created = presets.create(owner, Map.of("name", name(), "role", "admin", "permissions", List.of("members:write", "sales:read")), key(), "create");
        long id = Long.parseLong(created.id());

        var same = presets.update(owner, id, Map.of("version", created.version(), "name", created.name(),
                "permissions", created.permissions()), "same");
        assertThat(same.version()).isEqualTo(created.version() + 1);
        assertThat(dbVersion(id)).isEqualTo(ApiVersion.toDatabase(same.version()));

        var swapped = presets.update(owner, id, Map.of("version", same.version(), "name", created.name(),
                "permissions", List.of("payments:read", "sales:read")), "swap");
        assertThat(swapped.version()).isEqualTo(same.version() + 1);
        assertThat(swapped.permissions()).containsExactly("payments:read", "sales:read");
        assertThat(grants(swapped)).containsExactly("payments:read", "sales:read");
    }

    @Test
    void renamingOntoAnotherPresetOfTheSameRoleIsADuplicate() {
        var first = presets.create(owner, Map.of("name", name(), "role", "staff", "permissions", List.of()), key(), "a");
        var second = presets.create(owner, Map.of("name", name(), "role", "staff", "permissions", List.of()), key(), "b");
        assertThatThrownBy(() -> presets.update(owner, Long.parseLong(second.id()), Map.of("version", second.version(),
                "name", first.name(), "permissions", List.of()), "rename"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("같은 이름과 역할"));
    }

    @Test
    void listEqualsTheFormerQuery() {
        presets.create(owner, Map.of("name", name(), "role", "admin", "permissions", List.of("people:manage", "ai:read")), key(), "list");
        var listed = presets.lookup(owner, null);
        var former = jdbc.queryForList("SELECT id FROM permission_presets ORDER BY id", Long.class).stream().map(this::former).toList();
        assertThat(listed).isEqualTo(former);
    }

    private PermissionPreset former(long id) {
        return jdbc.query("SELECT * FROM permission_presets WHERE id=?", (rs, n) -> new PermissionPreset(
                rs.getString("id"), ApiVersion.fromDatabase(rs.getLong("version")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("name"), "MANAGER".equals(rs.getString("role")) ? "admin" : "staff",
                rs.getBoolean("is_system"), jdbc.queryForList("SELECT p.code FROM permission_preset_grants g JOIN permission_definitions p "
                        + "ON p.id=g.permission_id WHERE g.preset_id=? ORDER BY p.code", String.class, id)), id).getFirst();
    }

    private List<String> grants(PermissionPreset preset) {
        return jdbc.queryForList("SELECT p.code FROM permission_preset_grants g JOIN permission_definitions p ON p.id=g.permission_id "
                + "WHERE g.preset_id=? ORDER BY p.code", String.class, Long.parseLong(preset.id()));
    }

    private long dbVersion(long id) { return jdbc.queryForObject("SELECT version FROM permission_presets WHERE id=?", Long.class, id); }
    private static String name() { return "프리셋 " + UUID.randomUUID().toString().substring(0, 8); }
    private static String key() { return UUID.randomUUID().toString(); }
}
