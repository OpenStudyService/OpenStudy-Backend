package com.openstudy.integration.database;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.branch.entity.Branch;
import com.openstudy.modules.branch.entity.BranchStatus;
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.entity.EmployeeStatus;
import com.openstudy.modules.employee.entity.GlobalRole;
import com.openstudy.modules.employee.entity.Theme;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 spike: the context only starts when ddl-auto=validate accepts these mappings against the Flyway schema.
 * Each case also round-trips through the real MySQL columns (ENUM, unsigned, DB timestamps, version).
 */
@MySqlIntegrationTest
@Transactional
class JpaMappingTests {

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void employeeRoundTripsEnumsDbTimestampsAndVersion() {
        var employee = new Employee(email(), "hash", "매핑 직원", GlobalRole.EMPLOYEE, EmployeeStatus.PENDING);
        em.persist(employee);
        em.flush();
        assertThat(employee.getCreatedAt()).isNotNull();
        assertThat(employee.getUpdatedAt()).isNotNull();
        em.clear();

        var loaded = em.find(Employee.class, employee.getId());
        assertThat(loaded.getGlobalRole()).isEqualTo(GlobalRole.EMPLOYEE);
        assertThat(loaded.getStatus()).isEqualTo(EmployeeStatus.PENDING);
        assertThat(loaded.getTheme()).isEqualTo(Theme.FOREST);
        assertThat(loaded.getVersion()).isZero();

        loaded.changeNickname("새 닉네임");
        em.flush();
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT nickname,version,updated_by FROM employees WHERE id=?", loaded.getId()))
                .containsEntry("nickname", "새 닉네임").containsEntry("version", java.math.BigInteger.ONE)
                .containsEntry("updated_by", java.math.BigInteger.valueOf(loaded.getId()));
    }

    @Test
    void rowsWrittenByExistingJdbcCodeLoadThroughTheMapping() {
        String email = email();
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,'hash','JDBC 직원','JDBC','EMPLOYEE','ACTIVE')", email);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        jdbc.update("UPDATE employees SET version=version+1 WHERE id=?", id);

        var loaded = em.find(Employee.class, id);
        assertThat(loaded.getEmail()).isEqualTo(email);
        assertThat(loaded.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void branchPersistsWeeklyHoursAsOneAggregate() {
        var branch = new Branch("JPA-" + UUID.randomUUID().toString().substring(0, 8), "매핑 지점", "주소", null,
                BranchStatus.ACTIVE, null);
        branch.addWeeklyHour(1, false, 9 * 60, 26 * 60);
        branch.addWeeklyHour(7, true, null, null);
        em.persist(branch);
        em.flush();
        em.clear();

        var loaded = em.find(Branch.class, branch.getId());
        assertThat(loaded.getStatus()).isEqualTo(BranchStatus.ACTIVE);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getWeeklyHours()).extracting(h -> h.getWeekday()).containsExactly(1, 7);
        assertThat(loaded.getWeeklyHours().getFirst().getClosesMinute()).isEqualTo(26 * 60);
        assertThat(loaded.getWeeklyHours().get(1).isClosed()).isTrue();

        loaded.rename("바뀐 지점", null);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT version FROM branches WHERE id=?", Long.class, loaded.getId())).isEqualTo(1);
    }

    private static String email() { return "jpa-" + UUID.randomUUID() + "@example.com"; }
}
