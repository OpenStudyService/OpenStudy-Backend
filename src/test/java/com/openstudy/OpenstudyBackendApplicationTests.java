package com.openstudy;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.boot.test.web.server.LocalServerPort;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class OpenstudyBackendApplicationTests {

    @LocalServerPort
    int port;

    @Test
    void servesHealthFromTheRunningApplication() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }

}
