package com.openstudy.integration.database;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.openstudy.bootstrap.OwnerBootstrap;
import com.openstudy.support.database.MySqlTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerBootstrapTests {
    static MySQLContainer mysql;
    static DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    Map<String, String> environment;

    @BeforeAll static void start() {
        mysql = new MySQLContainer(DockerImageName.parse(MySqlTestConfiguration.MYSQL_IMAGE))
                .withDatabaseName("owner_bootstrap_test").withUsername("bootstrap_test")
                .withPassword(UUID.randomUUID().toString());
        mysql.start();
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    @AfterAll static void stop() { if (mysql != null) mysql.stop(); }

    @BeforeEach void resetOnlyContainerDatabase() {
        assertThat(dataSource.getUrl()).isEqualTo(mysql.getJdbcUrl());
        assertThat(mysql.getDatabaseName()).isEqualTo("owner_bootstrap_test");
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        environment = new HashMap<>(Map.of("DB_URL", mysql.getJdbcUrl(), "DB_USERNAME", mysql.getUsername(),
                "DB_PASSWORD", mysql.getPassword(), "OWNER_EMAIL", " Owner@Example.com ",
                "OWNER_NAME", "초기 사장님", "OWNER_PASSWORD", "test-only-" + UUID.randomUUID()));
    }

    record Result(int code, String output) {}
    Result run(Map<String, String> env) {
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = OwnerBootstrap.run(env, output, output);
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(env.getOrDefault("OWNER_PASSWORD", "never-secret"), mysql.getPassword(), mysql.getJdbcUrl());
        return new Result(code, text);
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void createsOneActiveOwnerAndAuditWithoutMembershipOrSecretAuditData() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("1");
        assertThat(run(environment).code()).isZero();
        var account = jdbc.queryForMap("SELECT * FROM employees");
        assertThat(account).containsEntry("email", "owner@example.com").containsEntry("global_role", "OWNER")
                .containsEntry("status", "ACTIVE").containsEntry("name", "초기 사장님");
        assertThat(new BCryptPasswordEncoder().matches(environment.get("OWNER_PASSWORD"), (String) account.get("password_hash"))).isTrue();
        assertThat(count("branch_memberships")).isZero();
        var audit = jdbc.queryForMap("SELECT actor_employee_id,action,before_json,after_json FROM audit_events WHERE action='OWNER_BOOTSTRAPPED'");
        assertThat(audit).containsEntry("actor_employee_id", null).containsEntry("before_json", null).containsEntry("after_json", null);
    }

    @Test void repeatedExecutionDoesNotResetCredentialsOrReactivateSuspendedOwner() {
        assertThat(run(environment).code()).isZero();
        jdbc.update("UPDATE employees SET status='SUSPENDED', version=7");
        var before = jdbc.queryForMap("SELECT * FROM employees");
        environment.put("OWNER_PASSWORD", "replacement-password");
        environment.put("OWNER_EMAIL", "different@example.com");
        assertThat(run(environment).code()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM employees")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='OWNER_BOOTSTRAPPED'", Long.class)).isEqualTo(1);
    }

    @Test void existingEmployeeWithEquivalentEmailIsNeverPromoted() {
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,status) VALUES ('OWNER@example.com','unchanged','Staff','Staff','ACTIVE')");
        var before = jdbc.queryForMap("SELECT * FROM employees");
        assertThat(run(environment).code()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM employees")).isEqualTo(before);
    }

    @Test void concurrentCommandsCreateOnlyOneOwnerWithoutOverwritingWinner() throws Exception {
        var second = new HashMap<>(environment);
        second.put("OWNER_PASSWORD", "other-password");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstRun = pool.submit(() -> { ready.countDown(); start.await(); return run(environment); });
            var secondRun = pool.submit(() -> { ready.countDown(); start.await(); return run(second); });
            ready.await(); start.countDown();
            assertThat(firstRun.get().code()).isZero();
            assertThat(secondRun.get().code()).isZero();
        }
        assertThat(count("employees")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='OWNER_BOOTSTRAPPED'", Long.class)).isEqualTo(1);
        String hash = jdbc.queryForObject("SELECT password_hash FROM employees", String.class);
        assertThat(new BCryptPasswordEncoder().matches(environment.get("OWNER_PASSWORD"), hash)
                || new BCryptPasswordEncoder().matches(second.get("OWNER_PASSWORD"), hash)).isTrue();
    }

    @Test void invalidOrMissingEnvironmentAndUtf8BcryptOverflowFailBeforeAnyWrite() {
        for (String invalid : new String[] { "short", "가".repeat(25) }) {
            environment.put("OWNER_PASSWORD", invalid);
            assertThat(run(environment).code()).isEqualTo(2);
        }
        environment.remove("OWNER_PASSWORD");
        assertThat(run(environment).code()).isEqualTo(2);
        assertThat(count("employees")).isZero();
    }

    @Test void acceptsExact72Utf8BytesAndRollsBackIfAuditCannotBeWritten() {
        environment.put("OWNER_PASSWORD", "가".repeat(24));
        assertThat(run(environment).code()).isZero();
        jdbc.update("DELETE FROM audit_events WHERE action='OWNER_BOOTSTRAPPED'");
        jdbc.update("DELETE FROM employees");
        jdbc.execute("DROP TABLE audit_events");
        assertThat(run(environment).code()).isEqualTo(1);
        assertThat(count("employees")).isZero();
    }

    @Test void missingGuardRefusesInitialization() {
        jdbc.update("DELETE FROM administration_guard");
        assertThat(run(environment).code()).isEqualTo(1);
        assertThat(count("employees")).isZero();
    }

    @Test void unmigratedDatabaseIsRejectedWithoutCreatingSchema() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        assertThat(run(environment).code()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'", Long.class))
                .isZero();
    }

    @Test void pendingMigrationIsRejectedWithoutApplyingItOrCreatingOwner(@TempDir Path directory) throws Exception {
        Path migrations = Files.createDirectories(directory.resolve("db/migration"));
        Files.writeString(migrations.resolve("V2__bootstrap_pending_test.sql"),
                "CREATE TABLE bootstrap_pending_probe (id INT PRIMARY KEY);");
        String classpath = directory + java.io.File.pathSeparator + System.getProperty("ownerBootstrap.runtimeClasspath");
        assertThat(command(classpath).code()).isNotZero();
        assertThat(count("employees")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='bootstrap_pending_probe'", Long.class))
                .isZero();
    }

    private Result command(String classpath) throws Exception {
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(executable, "-cp", classpath,
                OwnerBootstrap.class.getName()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        var process = builder.start();
        assertThat(process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(environment.get("OWNER_PASSWORD"), mysql.getPassword(), mysql.getJdbcUrl(),
                "Tomcat", "Started OpenstudyBackendApplication", "Hikari", "Spring Boot");
        return new Result(process.exitValue(), text);
    }

    @Test void actualCommandStartsNoServerAndPrintsNoSecrets() throws Exception {
        var result = command(System.getProperty("ownerBootstrap.runtimeClasspath"));
        assertThat(result.code()).isZero();
        assertThat(result.output().strip()).isEqualTo("Initial OWNER created.");
        assertThat(count("employees")).isEqualTo(1);
        assertThat(count("branches")).isZero();
    }
}
