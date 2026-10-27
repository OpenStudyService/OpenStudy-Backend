package com.openstudy.modules.branch;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.branch.dto.Branch;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: behaviors of the former SQL that the HTTP tests do not pin down. Every accepted command advanced the
 * version by exactly one, weekly hours always end as seven rows, and a missing phone became "" after an update.
 */
@MySqlIntegrationTest
@Transactional
class BranchJpaBehaviorTests {

    @Autowired BranchService service;
    @Autowired JdbcTemplate jdbc;
    long owner;

    @BeforeEach
    void owner() {
        // The single-owner slot may already be taken by fixtures of other suites; reuse that owner if so.
        var existing = jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE'", Long.class);
        if (!existing.isEmpty()) { owner = existing.getFirst(); return; }
        String email = "branch-jpa-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,'hash','소유자','소유자','OWNER','ACTIVE')", email);
        owner = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    @Test
    void anUpdateWithoutAnyDifferenceStillAdvancesTheVersionOnce() {
        var created = create();
        var same = service.update(owner, id(created), new BranchRequests.Update(created.version(), created.name(), null, null, null), "same");
        assertThat(same.version()).isEqualTo(created.version() + 1);
        assertThat(dbVersion(created)).isEqualTo(1);

        var status = service.changeStatus(owner, id(created), new BranchRequests.Status(same.version(), "운영 중"), "same-status");
        assertThat(status.version()).isEqualTo(same.version() + 1);
        assertThat(dbVersion(created)).isEqualTo(2);
    }

    @Test
    void hoursOnlyAndCombinedUpdatesAdvanceOnceAndKeepRowsInPlace() {
        var created = create();
        var rowIds = jdbc.queryForList("SELECT id FROM branch_weekly_hours WHERE branch_id=? ORDER BY weekday", Long.class, id(created));
        var hours = IntStream.rangeClosed(1, 7).mapToObj(d -> d == 3 ? new WeeklyHours(d, true, null, null)
                : new WeeklyHours(d, false, 600, 1800)).toList();

        var hoursOnly = service.update(owner, id(created), new BranchRequests.Update(created.version(), null, null, null, hours), "hours");
        assertThat(hoursOnly.version()).isEqualTo(created.version() + 1);
        assertThat(hoursOnly.weeklyHours()).isEqualTo(hours);
        assertThat(hoursOnly.hours()).contains("3요일 휴무", "1요일 10:00~익일 06:00");
        assertThat(jdbc.queryForList("SELECT id FROM branch_weekly_hours WHERE branch_id=? ORDER BY weekday", Long.class, id(created)))
                .isEqualTo(rowIds);

        var both = service.update(owner, id(created), new BranchRequests.Update(hoursOnly.version(), "새 이름", null, null,
                created.weeklyHours()), "both");
        assertThat(both.version()).isEqualTo(hoursOnly.version() + 1);
        assertThat(both.name()).isEqualTo("새 이름");
        assertThat(both.weeklyHours()).isEqualTo(created.weeklyHours());
        assertThat(both.updatedAt()).isGreaterThanOrEqualTo(hoursOnly.updatedAt());
    }

    @Test
    void legacyBranchWithoutScheduleIsClosedAndGetsSevenRowsAndEmptyPhoneOnUpdate() {
        String code = "LG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'옛 지점','주소')", code);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);

        var legacy = service.get(owner, id);
        assertThat(legacy.weeklyHours()).hasSize(7).allMatch(WeeklyHours::isClosed);
        assertThat(legacy.phone()).isEmpty();

        service.update(owner, id, new BranchRequests.Update(legacy.version(), "옛 지점 수정", null, null, null), "legacy");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch_weekly_hours WHERE branch_id=? AND is_closed", Long.class, id))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT phone FROM branches WHERE id=?", String.class, id)).isEmpty();
    }

    @Test
    void listedBranchesEqualTheirDetailViews() {
        var created = create();
        var listed = service.lookup(owner, null).stream().filter(b -> b.id().equals(created.id())).toList();
        assertThat(listed).containsExactly(service.get(owner, id(created)));
    }

    private Branch create() {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(d -> new WeeklyHours(d, false, 540, 1320)).toList();
        return service.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create("JPA 지점", "서울", null, hours, "운영 중"), "create");
    }

    private static long id(Branch b) { return Long.parseLong(b.id()); }

    private long dbVersion(Branch b) {
        return jdbc.queryForObject("SELECT version FROM branches WHERE id=?", Long.class, id(b));
    }
}
