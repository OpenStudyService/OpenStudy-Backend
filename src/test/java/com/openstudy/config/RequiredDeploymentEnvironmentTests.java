package com.openstudy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RequiredDeploymentEnvironmentTests {
    private final RequiredDeploymentEnvironment check = new RequiredDeploymentEnvironment();

    @Test
    void prodNamesEveryMissingOrBlankVariableWithoutValues() {
        var environment = new MockEnvironment().withProperty("DB_URL", "jdbc:mysql://db/openstudy")
                .withProperty("DB_PASSWORD", "secret-value").withProperty("TRUSTED_PROXIES", " ");
        environment.setActiveProfiles("prod");

        assertThatIllegalStateException().isThrownBy(() -> check.postProcessEnvironment(environment, new SpringApplication()))
                .withMessage("Missing required deployment environment: DB_USERNAME, TRUSTED_PROXIES")
                .withMessageNotContaining("secret-value");
    }

    @Test
    void prodStartsWhenAllVariablesArePresent() {
        var environment = new MockEnvironment();
        RequiredDeploymentEnvironment.REQUIRED.forEach(name -> environment.setProperty(name, "value"));
        environment.setActiveProfiles("prod");

        assertThatNoException().isThrownBy(() -> check.postProcessEnvironment(environment, new SpringApplication()));
    }

    @Test
    void prodRejectsTheLocalProfile() {
        var environment = new MockEnvironment();
        RequiredDeploymentEnvironment.REQUIRED.forEach(name -> environment.setProperty(name, "value"));
        environment.setActiveProfiles("prod", "local");

        assertThatIllegalStateException().isThrownBy(() -> check.postProcessEnvironment(environment, new SpringApplication()))
                .withMessageContaining("local profile");
    }

    @Test
    void otherProfilesAreNotChecked() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertThatNoException().isThrownBy(() -> check.postProcessEnvironment(environment, new SpringApplication()));
    }
}
