package com.openstudy.integration.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #102 (ADR-M11-001 M11-D04): anonymous health shows the status only, request metrics are collected per route, and each
 * API request ends with one log line naming its route template, never its raw path.
 */
@MySqlIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTests {
    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void healthIsAnonymousAndShowsTheStatusOnly() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            var response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            // The root also names the probe groups (liveness, readiness); no component or detail is shown.
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"").doesNotContain("components", "details", "db");
        }
        // Only health is exposed; metrics and the rest of actuator are not served to anyone anonymous.
        assertThat(get("/actuator/metrics").statusCode()).isIn(401, 404);
        assertThat(get("/actuator/env").statusCode()).isIn(401, 404);
    }

    @Test void requestMetricsAreCollectedPerRouteWithStatusAndLatency() throws Exception {
        get("/api/auth/csrf");
        get("/api/auth/csrf");
        var csrf = meters.find("http.server.requests").tag("uri", "/api/auth/csrf").tag("status", "200").timer();
        assertThat(csrf).isNotNull();
        assertThat(csrf.count()).isGreaterThanOrEqualTo(2);
        assertThat(csrf.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
        get("/api/me");
        assertThat(meters.find("http.server.requests").tag("status", "401").timers()).isNotEmpty();
    }

    @Test void eachApiRequestEndsWithOneLineOfRouteStatusAndDuration(CapturedOutput output) throws Exception {
        var response = get("/api/auth/csrf?token=never-logged");
        String id = response.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(output.getAll()).contains("API requestId=" + id + " status=200 route=/api/auth/csrf durationMs=")
                .doesNotContain("never-logged");
    }
}
