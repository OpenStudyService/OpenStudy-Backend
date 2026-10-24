package com.openstudy.bootstrap;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit offline operator command. Never component-scanned or invoked by server startup. */
public final class OwnerBootstrap {
    private OwnerBootstrap() {}

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("OWNER bootstrap accepts process environment only, no command arguments.");
            System.exit(2);
        }
        System.exit(run(System.getenv(), System.out, System.err));
    }

    public static int run(Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            String url = required(env, "DB_URL");
            String username = required(env, "DB_USERNAME");
            String dbPassword = required(env, "DB_PASSWORD");
            String email = required(env, "OWNER_EMAIL").strip().toLowerCase(Locale.ROOT);
            String name = required(env, "OWNER_NAME").strip();
            String password = required(env, "OWNER_PASSWORD");
            if (!url.startsWith("jdbc:mysql:") || email.length() > 254
                    || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                    || name.isBlank() || name.codePointCount(0, name.length()) > 100
                    || password.codePointCount(0, password.length()) < 8
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("Invalid OWNER bootstrap configuration.");
            }
            var flyway = Flyway.configure().dataSource(url, username, dbPassword)
                    .locations("classpath:db/migration").loggers(SilentMigrationLogger.class.getName()).load();
            flyway.validate();
            if (flyway.info().current() == null
                    || flyway.info().current().getVersion().compareTo(MigrationVersion.fromVersion("3")) < 0) {
                throw new IllegalArgumentException("Apply the reviewed foundation migrations first.");
            }
            // Hash before acquiring the shared administrative lock; no sensitive values are logged.
            String hash = new BCryptPasswordEncoder(12).encode(password);
            try (Connection connection = DriverManager.getConnection(url, username, dbPassword)) {
                connection.setAutoCommit(false);
                try {
                    try (var lock = connection.prepareStatement("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE");
                            var rows = lock.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Missing administrative guard.");
                    }
                    try (var query = connection.prepareStatement("SELECT id FROM employees WHERE global_role='OWNER' FOR UPDATE");
                            var rows = query.executeQuery()) {
                        if (rows.next()) {
                            connection.commit();
                            out.println("OWNER already exists; no changes made.");
                            return 0;
                        }
                    }
                    // Same-email EMPLOYEE is never promoted. The unique email index also protects races.
                    try (var query = connection.prepareStatement("SELECT id FROM employees WHERE email=? FOR UPDATE")) {
                        query.setString(1, email);
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) throw new IllegalArgumentException("OWNER email belongs to an existing account; no changes made.");
                        }
                    }
                    long id;
                    try (var insert = connection.prepareStatement("INSERT INTO employees "
                            + "(email,password_hash,name,nickname,global_role,status) VALUES (?,?,?,?,'OWNER','ACTIVE')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, email);
                        insert.setString(2, hash);
                        insert.setString(3, name);
                        insert.setString(4, name);
                        insert.executeUpdate();
                        try (var keys = insert.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Missing generated account ID.");
                            id = keys.getLong(1);
                        }
                    }
                    try (var audit = connection.prepareStatement("INSERT INTO audit_events "
                            + "(entity_type,entity_id,action,reason) VALUES ('employee',?,'OWNER_BOOTSTRAPPED',"
                            + "'Explicit offline operator initialization')")) {
                        audit.setLong(1, id);
                        audit.executeUpdate();
                    }
                    connection.commit();
                    out.println("Initial OWNER created.");
                    return 0;
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        } catch (IllegalArgumentException failure) {
            // Only our fixed validation messages are printable; configuration/driver messages may contain secrets.
            err.println("OWNER bootstrap rejected. Check required environment, email/name/password limits, account conflicts and migrated schema.");
            return 2;
        } catch (Exception failure) {
            err.println("OWNER bootstrap failed. Check database access and migration/constraint state. No credentials were updated.");
            return 1;
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing bootstrap environment.");
        return value;
    }

    /** Flyway's usual connection logging can disclose JDBC URL query credentials. CLI owns its diagnostics. */
    public static final class SilentMigrationLogger implements LogCreator {
        @Override public Log createLogger(Class<?> type) {
            return new Log() {
                @Override public void debug(String message) {}
                @Override public void info(String message) {}
                @Override public void warn(String message) {}
                @Override public void error(String message) {}
                @Override public void error(String message, Exception exception) {}
                @Override public void notice(String message) {}
            };
        }
    }
}
