package com.openstudy.config;

import java.util.List;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fails a prod start before any bean is created when required deployment variables are missing or blank,
 * or when the local profile is also active.
 * Otherwise Spring passes the unresolved "${DB_USERNAME}" text to MySQL and the cause reads as an access error.
 * Reports variable names only, never values. Registered in META-INF/spring.factories.
 */
public class RequiredDeploymentEnvironment implements EnvironmentPostProcessor {
    static final List<String> REQUIRED = List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "TRUSTED_PROXIES");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        // local enables preview sample data and the in-app OWNER bootstrap; neither may run in a deployment.
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("The prod profile cannot be combined with the local profile.");
        }
        var missing = REQUIRED.stream().filter(name -> {
            String value = environment.getProperty(name);
            return value == null || value.isBlank();
        }).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required deployment environment: " + String.join(", ", missing));
        }
    }
}
