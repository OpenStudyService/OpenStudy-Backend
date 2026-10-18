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

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName("openstudy_test")
                .withUsername("openstudy_test")
                .withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withCommand("--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci", "--default-time-zone=+00:00")
                .withReuse(false);
    }
}
