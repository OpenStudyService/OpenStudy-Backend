package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModuleBoundaryTests {
    @Test
    void crossModuleImportsUsePublicServicesOrDtosAndHaveNoCycles() throws Exception {
        Map<String, Set<String>> graph = new HashMap<>();
        // Include fully qualified references in method bodies, not only import statements.
        var imports = Pattern.compile("com\\.openstudy\\.modules\\.([a-z]+)\\.([a-zA-Z0-9_.*]+)");
        Path root = Path.of("src/main/java/com/openstudy/modules");
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = root.relativize(file).getName(0).toString();
                graph.computeIfAbsent(source, key -> new HashSet<>());
                var matcher = imports.matcher(Files.readString(file));
                while (matcher.find()) {
                    String target = matcher.group(1), member = matcher.group(2);
                    if (source.equals(target)) continue;
                    assertThat(member).as("%s crosses into %s", file, member).matches("(?:service|dto)\\..*");
                    graph.get(source).add(target);
                }
            }
        }
        for (String module : graph.keySet()) visit(module, graph, new HashSet<>(), new HashSet<>());
    }
    private void visit(String module, Map<String, Set<String>> graph, Set<String> active, Set<String> done) {
        if (done.contains(module)) return;
        assertThat(active.add(module)).as("Module dependency cycle at %s", module).isTrue();
        for (String next : graph.getOrDefault(module, Set.of())) visit(next, graph, active, done);
        active.remove(module); done.add(module);
    }
}
