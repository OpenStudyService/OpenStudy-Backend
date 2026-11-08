package com.openstudy.support.database;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfiguration {

    // MySQL 8.4.11: pin the image verified for V1; never share development data.
    public static final String MYSQL_IMAGE =
            "mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70";

    /**
     * One MySQL for the whole test JVM. Each distinct test configuration builds its own Spring context; with the
     * container as a per-context bean every one of them (23 at the time) started a MySQL and re-ran the migrations, and
     * all of them stayed up until the JVM exited, enough to lose connections when another suite ran alongside. Tests
     * already share the database within a context and clean up their own rows, so sharing it across contexts changes
     * nothing they rely on. Testcontainers' reaper removes it when the JVM ends.
     */
    private static final MySQLContainer SHARED = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName("openstudy_test")
            .withUsername("openstudy_test")
            .withPassword(UUID.randomUUID().toString())
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci", "--default-time-zone=+00:00")
            .withReuse(false);

    /** No destroy method: closing one cached context must not stop the database the others still use. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return SHARED;
    }
}
