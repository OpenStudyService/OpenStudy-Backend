package com.openstudy.integration.http;

import java.sql.SQLException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.mysql.MySQLContainer;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MySqlIntegrationTest
@Transactional
class SchemaConstraintsTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired MySQLContainer mysql;
    long branchId;
    long roomId;

    @BeforeEach
    void seedsOnlyTheContainerDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("openstudy_test");
            assertThat(connection.getMetaData().getURL().split("\\?")[0])
                    .isEqualTo(mysql.getJdbcUrl().split("\\?")[0]);
        }
        jdbc.update("INSERT INTO branches (code,name,address) VALUES ('SCHEMA_TEST','Test','Test address')");
        branchId = jdbc.queryForObject("SELECT id FROM branches WHERE code='SCHEMA_TEST'", Long.class);
        jdbc.update("INSERT INTO rooms (branch_id,room_no,name,capacity,rate_per_30min_krw) "
                + "VALUES (?,'R1','Test room',4,1000)", branchId);
        roomId = jdbc.queryForObject("SELECT id FROM rooms WHERE branch_id=?", Long.class, branchId);
    }

    static Stream<Arguments> hoursCases() {
        Object[][] cases = {
                {0, 540, 1080, true}, {0, 1320, 1560, true}, {0, 0, 1440, true},
                {1, null, null, true}, {0, null, null, false}, {0, null, 1080, false},
                {0, 540, null, false}, {1, 540, 1080, false}, {0, 1080, 540, false},
                {0, 540, 540, false}, {0, 1440, 1500, false}, {0, 540, 2881, false}
        };
        return Stream.of("branch_weekly_hours", "room_weekly_hours")
                .flatMap(table -> Stream.of(cases).map(c -> Arguments.of(table, c[0], c[1], c[2], c[3])));
    }

    @ParameterizedTest(name = "{0}: closed={1}, opens={2}, closes={3}, valid={4}")
    @MethodSource("hoursCases")
    void enforcesOpeningHours(String table, int closed, Integer opens, Integer closes, boolean valid) {
        String parent = table.equals("branch_weekly_hours") ? "branch_id" : "room_id";
        long id = table.equals("branch_weekly_hours") ? branchId : roomId;
        Runnable insert = () -> jdbc.update("INSERT INTO " + table + " (" + parent
                + ",weekday,is_closed,opens_minute,closes_minute) VALUES (?,1,?,?,?)", id, closed, opens, closes);
        if (valid) {
            insert.run();
        } else {
            assertSqlError(insert, 3819, "ck_" + table + "_2");
        }
    }

    @Test
    void rejectsNullUpdatesForBothHoursTables() {
        jdbc.update("INSERT INTO branch_weekly_hours (branch_id,weekday,opens_minute,closes_minute) "
                + "VALUES (?,1,540,1080)", branchId);
        jdbc.update("INSERT INTO room_weekly_hours (room_id,weekday,opens_minute,closes_minute) "
                + "VALUES (?,1,540,1080)", roomId);
        assertSqlError(() -> jdbc.update("UPDATE branch_weekly_hours SET opens_minute=NULL WHERE branch_id=?", branchId),
                3819, "ck_branch_weekly_hours_2");
        assertSqlError(() -> jdbc.update("UPDATE room_weekly_hours SET opens_minute=NULL WHERE room_id=?", roomId),
                3819, "ck_room_weekly_hours_2");
    }

    @Test
    void preservesTheBranchReferencedByAChannel() {
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        jdbc.update("INSERT INTO conversations (kind,branch_id,name) VALUES ('BRANCH_CHANNEL',?,'Test channel')", branchId);
        assertSqlError(() -> jdbc.update("DELETE FROM branches WHERE id=?", branchId), 1451, "fk_conversations_1");
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", branchId);
        assertThat(jdbc.queryForObject("SELECT branch_id FROM conversations WHERE branch_id=?", Long.class, branchId))
                .isEqualTo(branchId);
    }

    @Test
    void preservesTheEmployeeWhoCancelledAReservation() {
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname) VALUES "
                + "('creator@example.test','test-only','Creator','Creator'),"
                + "('canceller@example.test','test-only','Canceller','Canceller')");
        long creator = jdbc.queryForObject("SELECT id FROM employees WHERE email='creator@example.test'", Long.class);
        long canceller = jdbc.queryForObject("SELECT id FROM employees WHERE email='canceller@example.test'", Long.class);
        jdbc.update("INSERT INTO customers (member_no,name) VALUES ('SCHEMA_CUSTOMER','Test customer')");
        long customer = jdbc.queryForObject("SELECT id FROM customers WHERE member_no='SCHEMA_CUSTOMER'", Long.class);
        jdbc.update("INSERT INTO room_reservations (room_id,branch_id,customer_id,start_at,end_at,"
                + "rate_per_30min_snapshot_krw,slot_count,amount_krw,status,cancelled_at,cancelled_by,"
                + "cancel_reason,request_key,created_by) VALUES (?,?,?,'2026-10-19 09:00:00',"
                + "'2026-10-19 09:30:00',1000,1,1000,'CANCELLED','2026-10-19 08:00:00',?,"
                + "'Test cancellation','SCHEMA_REQUEST',?)", roomId, branchId, customer, canceller, creator);
        assertSqlError(() -> jdbc.update("DELETE FROM employees WHERE id=?", canceller), 1451, "fk_room_reservations_5");
        jdbc.update("UPDATE employees SET status='LEFT' WHERE id=?", canceller);
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM room_reservations WHERE request_key='SCHEMA_REQUEST'",
                Long.class)).isEqualTo(canceller);
    }

    private static void assertSqlError(Runnable action, int code, String constraint) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class).satisfies(error -> {
            Throwable cause = ((DataAccessException) error).getMostSpecificCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(code);
            assertThat(cause.getMessage()).contains(constraint);
        });
    }
}
