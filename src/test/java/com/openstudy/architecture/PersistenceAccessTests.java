package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * JPA migration (M1-04, JPA-01): application code reaches the database through JPA repositories only.
 * Plain JDBC would bypass the persistence context and miss writes made in the same transaction, so it is limited to
 * the offline OWNER bootstrap command; native SQL is confined to repository interfaces.
 */
class PersistenceAccessTests {
    private static final Path MAIN = Path.of("src/main/java/com/openstudy");
    private static final Set<String> PLAIN_JDBC_ALLOWED = Set.of("bootstrap/OwnerBootstrap.java");
    private static final Pattern PLAIN_JDBC = Pattern.compile(
            "org\\.springframework\\.jdbc\\.core|\\bJdbcTemplate\\b|\\bJdbcClient\\b"
                    + "|java\\.sql\\.(Connection|DriverManager|Statement|PreparedStatement)\\b"
                    + "|\\.prepareStatement\\(|\\.createStatement\\(");
    private static final Pattern NATIVE_QUERY = Pattern.compile("nativeQuery\\s*=\\s*true|createNativeQuery\\(");

    @Test
    void plainJdbcIsLimitedToTheOfflineOwnerBootstrap() throws Exception {
        assertThat(violations(PLAIN_JDBC, PLAIN_JDBC_ALLOWED::contains)).isEmpty();
    }

    @Test
    void nativeSqlLivesOnlyInRepositories() throws Exception {
        assertThat(violations(NATIVE_QUERY, file -> file.endsWith("Repository.java") || PLAIN_JDBC_ALLOWED.contains(file))).isEmpty();
    }

    @Test
    void rulesRejectViolatingSourcesAndAcceptJpaOnes() {
        assertThat(PLAIN_JDBC.matcher("import org.springframework.jdbc.core.JdbcTemplate;").find()).isTrue();
        assertThat(PLAIN_JDBC.matcher("var statement = connection.prepareStatement(sql);").find()).isTrue();
        assertThat(PLAIN_JDBC.matcher("import java.sql.Connection;").find()).isTrue();
        // SQLException inspection (DuplicateKeys) and Spring Data annotations are not database access.
        assertThat(PLAIN_JDBC.matcher("import java.sql.SQLException;").find()).isFalse();
        assertThat(PLAIN_JDBC.matcher("import org.springframework.data.jpa.repository.Query;").find()).isFalse();
        assertThat(NATIVE_QUERY.matcher("@Query(value = \"SELECT 1\", nativeQuery = true)").find()).isTrue();
        assertThat(NATIVE_QUERY.matcher("entityManager.createNativeQuery(sql)").find()).isTrue();
        assertThat(NATIVE_QUERY.matcher("@Query(\"SELECT b FROM Branch b\")").find()).isFalse();
    }

    private static List<String> violations(Pattern forbidden, Predicate<String> allowed) throws Exception {
        List<String> found = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (allowed.test(relative)) continue;
                if (forbidden.matcher(Files.readString(file)).find()) found.add(relative);
            }
        }
        return found;
    }
}
