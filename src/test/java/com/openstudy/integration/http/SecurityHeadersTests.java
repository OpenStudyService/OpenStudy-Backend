package com.openstudy.integration.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #101 (ADR-M11-001 M11-D08): every API response carries the content security headers, success and error alike. HSTS is
 * the HTTPS proxy's, so the app never sends it, not even for a request the proxy marks as HTTPS.
 */
@MySqlIntegrationTest
class SecurityHeadersTests {
    @LocalServerPort int port;

    HttpResponse<String> get(String path, String... headers) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
            if (headers.length > 0) request.headers(headers);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void successErrorAndHealthResponsesCarryTheSecurityHeaders() throws Exception {
        for (var response : List.of(get("/api/auth/csrf"), get("/api/me"), get("/api/no-such-route"), get("/actuator/health"))) {
            var headers = response.headers();
            String where = response.uri().getPath() + " " + response.statusCode();
            assertThat(headers.firstValue("X-Content-Type-Options")).as(where).contains("nosniff");
            assertThat(headers.firstValue("X-Frame-Options")).as(where).contains("DENY");
            assertThat(headers.firstValue("Referrer-Policy")).as(where).contains("no-referrer");
            assertThat(headers.firstValue("Content-Security-Policy")).as(where)
                    .contains("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            assertThat(headers.firstValue("Strict-Transport-Security")).as(where).isEmpty();
        }
    }

    @Test void theAppLeavesHstsToTheProxyEvenForAForwardedHttpsRequest() throws Exception {
        var response = get("/api/auth/csrf", "X-Forwarded-Proto", "https", "X-Forwarded-Host", "openstudy.example");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(response.headers().allValues("Content-Security-Policy")).hasSize(1);
    }
}
