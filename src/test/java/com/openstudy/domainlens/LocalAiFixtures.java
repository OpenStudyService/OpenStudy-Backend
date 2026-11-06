package com.openstudy.domainlens;

import java.nio.file.Files;

import com.openstudy.config.OpenStudyCatalogConfiguration;

/**
 * Conditions for the DomainLens tests that need local-only fixtures, used through
 * {@code @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#...")}. They are skipped where the fixtures are absent
 * instead of failing the whole suite.
 * <ul>
 *   <li>The koen-e5-tiny-q8 ONNX model is not committed (.gitignore); it is installed under .models/koen-e5-tiny-q8.</li>
 *   <li>The AI integration tests also read the seeded docker-compose MySQL on 127.0.0.1:3307 (password in
 *       OPENSTUDY_AI_TEST_DB_PASSWORD) and the DomainLens expected-result files under docs/04_TEST_DATA and
 *       docs/05_TEST_CASES, which are not in this repository yet, so they run only on explicit opt-in
 *       (OPENSTUDY_AI_LOCAL_TESTS=true).</li>
 * </ul>
 */
public final class LocalAiFixtures {
    private LocalAiFixtures() { }

    /** The koen-e5-tiny-q8 model is installed under .models/koen-e5-tiny-q8. */
    public static boolean modelInstalled() {
        return Files.isDirectory(OpenStudyCatalogConfiguration.resolvePath(null, ".models/koen-e5-tiny-q8"));
    }

    /** The model is installed and the local AI fixtures were opted into with OPENSTUDY_AI_LOCAL_TESTS=true. */
    public static boolean localAiTestsEnabled() {
        return modelInstalled() && "true".equalsIgnoreCase(System.getenv("OPENSTUDY_AI_LOCAL_TESTS"));
    }
}
