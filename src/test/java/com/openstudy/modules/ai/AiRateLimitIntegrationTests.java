package com.openstudy.modules.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** #96 (M10-D02): the per-employee limit is a setting, and going over it is the common 429 with Retry-After. */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.ai.rate-limit.max-per-minute=2")
class AiRateLimitIntegrationTests extends AiTestSupport {
    long staff, other, branch;

    @BeforeEach void setup() {
        long owner = person("OWNER");
        staff = person("EMPLOYEE");
        other = person("EMPLOYEE");
        branch = branch(owner, "제한 지점");
        member(staff, branch, "ai:read");
        member(other, branch, "ai:read");
    }

    @Test void theThirdQuestionInAMinuteIsRateLimitedForThatEmployeeOnly() throws Exception {
        String scope = Long.toString(branch);
        try (var s = login(staff)) {
            assertThat(ask(s, "만료 예정 이용권 알려줘", scope).statusCode()).isEqualTo(200);
            assertThat(ask(s, "만료 예정 이용권 알려줘", scope).statusCode()).isEqualTo(200);
            var limited = ask(s, "만료 예정 이용권 알려줘", scope);
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).contains("60");
            assertThat(body(limited).get("errorCode").asString()).isEqualTo("RATE_LIMITED");
            assertThat(body(limited).get("envelope").isNull()).isTrue();
        }
        assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id=?", staff)).isEqualTo(2);
        try (var s = login(other)) {
            assertThat(ask(s, "만료 예정 이용권 알려줘", scope).statusCode()).isEqualTo(200);
        }
    }
}
