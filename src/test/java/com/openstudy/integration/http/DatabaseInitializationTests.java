package com.openstudy.integration.http;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.testcontainers.mysql.MySQLContainer;

import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.support.database.MySqlTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class DatabaseInitializationTests {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired MySQLContainer mysql;
    @Autowired SessionRepository<? extends Session> sessions;

    @BeforeEach
    void verifiesTheConnectionBeforeAnyTestDataIsWritten() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("openstudy_test");
            assertThat(connection.getMetaData().getURL().split("\\?")[0])
                    .isEqualTo(mysql.getJdbcUrl().split("\\?")[0]);
            assertThat(connection.getMetaData().getUserName()).startsWith("openstudy_test@");
        }
    }

    @Test
    void recordsTheRealDatabaseEnvironment() throws Exception {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForObject("SELECT @@collation_database", String.class))
                .isEqualTo("utf8mb4_0900_ai_ci");
        System.out.printf("Test DB image: %s%nMySQL: %s%nOS: %s%n", MySqlTestConfiguration.MYSQL_IMAGE,
                jdbc.queryForObject("SELECT VERSION()", String.class),
                mysql.execInContainer("cat", "/etc/os-release").getStdout());
    }

    @Test
    void migratesAllTablesAndDoesNotReapplyV1OrRemoveData() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'", Integer.class))
                .isEqualTo(58);

        String code = "M-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?, 'Migration test', 'Test address')", code);
        try {
            var restartedFlyway = Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration").cleanDisabled(true).load();
            assertThat(restartedFlyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE code=?", Integer.class, code))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1",
                    Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM branches WHERE code=?", code);
        }
    }

    @Test
    void persistsLoadsAndDeletesSessionAttributesThroughJdbc() {
        assertThat(sessions).isInstanceOf(JdbcIndexedSessionRepository.class);
        verifySessionRoundTrip(sessions);
    }

    private <S extends Session> void verifySessionRoundTrip(SessionRepository<S> repository) {
        S session = repository.createSession();
        String marker = UUID.randomUUID().toString();
        session.setAttribute("test-marker", marker);
        repository.save(session);
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID=?",
                    Integer.class, session.getId())).isEqualTo(1);
            assertThat(repository.findById(session.getId()).<String>getAttribute("test-marker")).isEqualTo(marker);
        } finally {
            repository.deleteById(session.getId());
        }
        assertThat(repository.findById(session.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME='test-marker'",
                Integer.class)).isZero();
    }

    @Test
    void separatesRollbackAndCommittedVisibilityAcrossRealConnections() throws Exception {
        String rolledBack = "R-" + UUID.randomUUID().toString().substring(0, 16);
        String committed = "C-" + UUID.randomUUID().toString().substring(0, 16);
        try (var writer = dataSource.getConnection(); var reader = dataSource.getConnection()) {
            assertThat(connectionId(writer)).isNotEqualTo(connectionId(reader));
            writer.setAutoCommit(false);
            insertBranch(writer, rolledBack);
            assertThat(countBranch(reader, rolledBack)).isZero();
            writer.rollback();
            assertThat(countBranch(reader, rolledBack)).isZero();
            insertBranch(writer, committed);
            writer.commit();
            assertThat(countBranch(reader, committed)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM branches WHERE code IN (?,?)", rolledBack, committed);
        }
    }

    private static long connectionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void insertBranch(Connection connection, String code) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO branches (code,name,address) VALUES (?, 'Isolation test', 'Test address')")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private static int countBranch(Connection connection, String code) throws Exception {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM branches WHERE code=?")) {
            statement.setString(1, code);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
