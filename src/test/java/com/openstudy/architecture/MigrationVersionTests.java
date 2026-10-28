package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MigrationVersionTests {
    @Test
    void migrationVersionsStartAtOneAndIncreaseWithoutGapsOrDuplicates() throws Exception {
        // Versions are assigned just before merge (main's highest + 1), so a gap or duplicate means a stale branch number.
        var name = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
        List<Integer> versions = new ArrayList<>();
        try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                var matcher = name.matcher(file.getFileName().toString());
                assertThat(matcher.matches()).as("Migration file name %s", file.getFileName()).isTrue();
                versions.add(Integer.parseInt(matcher.group(1)));
            }
        }
        versions.sort(null);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("Migration versions %s", versions).isEqualTo(i + 1);
        }
    }
}
