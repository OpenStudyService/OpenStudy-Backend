package com.openstudy.bootstrap;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Explicit offline operator command. Never component-scanned or invoked by server startup.
 * <p>The only plain-JDBC code in the application (JPA migration phase 7): it runs without a Spring context by design
 * (M1-10), so it opens one connection and keeps the foundation lock order itself (guard, then employees). The
 * PersistenceAccessTests architecture rule names this file as its single exception.</p>
 */
public final class OwnerBootstrap {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_REJECTED = 2;

    private static final String JDBC_URL_PREFIX = "jdbc:mysql:";
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_NAME_CODE_POINTS = 100;
    private static final int MIN_PASSWORD_CODE_POINTS = 8;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final int BCRYPT_STRENGTH = 12;

    private static final String LOCK_ADMINISTRATION_GUARD_SQL = """
            SELECT id
            FROM administration_guard
            WHERE id = 1
            FOR UPDATE
            """;

    private static final String LOCK_OWNER_SQL = """
            SELECT id
            FROM employees
            WHERE global_role = 'OWNER'
            FOR UPDATE
            """;

    private static final String LOCK_EMAIL_SQL = """
            SELECT id
            FROM employees
            WHERE email = ?
            FOR UPDATE
            """;

    private static final String INSERT_OWNER_SQL = """
            INSERT INTO employees (email, password_hash, name, nickname, global_role, status)
            VALUES (?, ?, ?, ?, 'OWNER', 'ACTIVE')
            """;

    private static final String INSERT_AUDIT_EVENT_SQL = """
            INSERT INTO audit_events (entity_type, entity_id, action, reason)
            VALUES ('employee', ?, 'OWNER_BOOTSTRAPPED', 'Explicit offline operator initialization')
            """;

    private static final String REJECTED_MESSAGE = "OWNER bootstrap rejected. Check required environment, "
            + "email/name/password limits, account conflicts and migrated schema.";
    private static final String FAILED_MESSAGE = "OWNER bootstrap failed. Check database access and "
            + "migration/constraint state. No credentials were updated.";

    private OwnerBootstrap() {
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("OWNER bootstrap accepts process environment only, no command arguments.");
            System.exit(EXIT_REJECTED);
        }

        System.exit(run(System.getenv(), System.out, System.err));
    }

    public static int run(Map<String, String> env, PrintStream out, PrintStream err) {
        try {
            BootstrapSettings settings = readSettings(env);
            validateSettings(settings);
            requireMigratedSchema(settings);

            // Hash before acquiring the shared administrative lock; no sensitive values are logged.
            String passwordHash = new BCryptPasswordEncoder(BCRYPT_STRENGTH).encode(settings.password());

            return createOwner(settings, passwordHash, out);
        } catch (IllegalArgumentException failure) {
            // Only our fixed validation messages are printable; configuration/driver messages may contain secrets.
            err.println(REJECTED_MESSAGE);
            return EXIT_REJECTED;
        } catch (Exception failure) {
            err.println(FAILED_MESSAGE);
            return EXIT_FAILED;
        }
    }

    private static BootstrapSettings readSettings(Map<String, String> env) {
        String url = required(env, "DB_URL");
        String username = required(env, "DB_USERNAME");
        String dbPassword = required(env, "DB_PASSWORD");
        String email = required(env, "OWNER_EMAIL")
                .strip()
                .toLowerCase(Locale.ROOT);
        String name = required(env, "OWNER_NAME").strip();
        String password = required(env, "OWNER_PASSWORD");

        return new BootstrapSettings(url, username, dbPassword, email, name, password);
    }

    private static void validateSettings(BootstrapSettings settings) {
        String email = settings.email();
        String name = settings.name();
        String password = settings.password();

        boolean validUrl = settings.url().startsWith(JDBC_URL_PREFIX);
        boolean validEmail = email.length() <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matcher(email).matches();
        boolean validName = !name.isBlank() && name.codePointCount(0, name.length()) <= MAX_NAME_CODE_POINTS;
        boolean validPassword = password.codePointCount(0, password.length()) >= MIN_PASSWORD_CODE_POINTS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;

        if (!validUrl || !validEmail || !validName || !validPassword) {
            throw new IllegalArgumentException("Invalid OWNER bootstrap configuration.");
        }
    }

    private static void requireMigratedSchema(BootstrapSettings settings) {
        Flyway flyway = Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.dbPassword())
                .locations(MIGRATION_LOCATION)
                .loggers(SilentMigrationLogger.class.getName())
                .load();
        flyway.validate();

        // A consolidated V1 is ready too. Require an applied, fully migrated schema,
        // rather than tying readiness to the old V1-V3 numbering.
        MigrationInfoService migrations = flyway.info();
        boolean noMigrationApplied = migrations.current() == null;
        boolean hasPendingMigrations = migrations.pending().length != 0;

        if (noMigrationApplied || hasPendingMigrations) {
            throw new IllegalArgumentException("Apply all configured migrations before OWNER initialization.");
        }
    }

    private static int createOwner(BootstrapSettings settings, String passwordHash, PrintStream out)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                settings.url(),
                settings.username(),
                settings.dbPassword()
        )) {
            connection.setAutoCommit(false);

            try {
                lockAdministrationGuard(connection);

                if (ownerExists(connection)) {
                    connection.commit();
                    out.println("OWNER already exists; no changes made.");
                    return EXIT_SUCCESS;
                }

                // Same-email EMPLOYEE is never promoted. The unique email index also protects races.
                if (emailExists(connection, settings.email())) {
                    throw new IllegalArgumentException("OWNER email belongs to an existing account; no changes made.");
                }

                long ownerId = insertOwner(connection, settings, passwordHash);
                insertAuditEvent(connection, ownerId);

                connection.commit();
                out.println("Initial OWNER created.");
                return EXIT_SUCCESS;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void lockAdministrationGuard(Connection connection) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(LOCK_ADMINISTRATION_GUARD_SQL);
             ResultSet rows = lock.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Missing administrative guard.");
            }
        }
    }

    private static boolean ownerExists(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(LOCK_OWNER_SQL);
             ResultSet rows = query.executeQuery()) {
            return rows.next();
        }
    }

    private static boolean emailExists(Connection connection, String email) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(LOCK_EMAIL_SQL)) {
            query.setString(1, email);

            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static long insertOwner(Connection connection, BootstrapSettings settings, String passwordHash)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_OWNER_SQL, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, settings.email());
            insert.setString(2, passwordHash);
            insert.setString(3, settings.name());
            insert.setString(4, settings.name());
            insert.executeUpdate();

            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Missing generated account ID.");
                }

                return keys.getLong(1);
            }
        }
    }

    private static void insertAuditEvent(Connection connection, long ownerId) throws SQLException {
        try (PreparedStatement audit = connection.prepareStatement(INSERT_AUDIT_EVENT_SQL)) {
            audit.setLong(1, ownerId);
            audit.executeUpdate();
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing bootstrap environment.");
        }

        return value;
    }

    /** Environment values read once; never printed, since they carry credentials. */
    private record BootstrapSettings(
            String url,
            String username,
            String dbPassword,
            String email,
            String name,
            String password
    ) {
    }

    /** Flyway's usual connection logging can disclose JDBC URL query credentials. CLI owns its diagnostics. */
    public static final class SilentMigrationLogger implements LogCreator {

        @Override
        public Log createLogger(Class<?> type) {
            return new Log() {
                @Override
                public void debug(String message) {
                }

                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void error(String message) {
                }

                @Override
                public void error(String message, Exception exception) {
                }

                @Override
                public void notice(String message) {
                }
            };
        }
    }
}
