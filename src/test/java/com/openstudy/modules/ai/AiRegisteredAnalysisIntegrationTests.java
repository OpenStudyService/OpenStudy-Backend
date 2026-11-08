package com.openstudy.modules.ai;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-M10-003: the registered analyses work on the real branches. A branch is found by its real name, a branch the
 * requester cannot read is refused without its name, a one-branch analysis over several branches asks which branch,
 * the quality suite is the OWNER's, and the reference time is now.
 */
@MySqlIntegrationTest
class AiRegisteredAnalysisIntegrationTests {
    static final String PASSWORD = "ai-registered-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    long owner, staff, first, second;
    String firstName, secondName;

    @BeforeEach void setup() {
        owner = person("OWNER");
        staff = person("EMPLOYEE");
        String tag = Integer.toString(Math.abs(UUID.randomUUID().hashCode() % 100000));
        firstName = "가온" + tag + "점";
        secondName = "나래" + tag + "점";
        first = branch(firstName);
        second = branch(secondName);
        member(staff, first, "ai:read");
    }

    @AfterEach void clean() {
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    @Test void aBranchTheRequesterCannotReadIsRefusedWithoutRepeatingItsName() throws Exception {
        try (var s = login(staff)) {
            for (String question : List.of(secondName + " 지금 빈자리가 얼마나 있어?",
                    secondName.substring(0, secondName.length() - 1) + " 지금 빈자리가 얼마나 있어?")) {
                var response = ask(s, question, Long.toString(first), null);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
                JsonNode body = json.readTree(response.body());
                assertThat(body.get("errorCode").asString()).isEqualTo("BRANCH_SCOPE_DENIED");
                assertThat(body.get("message").asString()).doesNotContain(secondName);
                assertThat(body.get("envelope").isNull()).isTrue();
            }
            // The requester's own branch, named the same way, runs.
            var own = ask(s, firstName + " 지금 빈자리가 얼마나 있어?", Long.toString(first), null);
            assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
            assertThat(json.readTree(own.body()).get("status").asString()).isEqualTo("COMPLETED");
        }
    }

    @Test void aOneBranchAnalysisOverSeveralBranchesAsksWhichBranchAndThenRunsThatBranch() throws Exception {
        try (var s = login(owner)) {
            var asked = ask(s, "지금 빈자리가 얼마나 있어?", "all", null);
            assertThat(asked.statusCode()).as(asked.body()).isEqualTo(200);
            JsonNode question = json.readTree(asked.body());
            assertThat(question.get("status").asString()).isEqualTo("CLARIFICATION");
            assertThat(question.get("errorCode").asString()).isEqualTo("BRANCH_REQUIRED");
            assertThat(question.get("envelope").isNull()).isTrue();
            JsonNode picked = null;
            var labels = new ArrayList<String>();
            for (var option : question.get("clarificationOptions")) {
                labels.add(option.get("label").asString());
                if (option.get("targetScope").asString().equals(Long.toString(first))) picked = option;
            }
            assertThat(labels).contains(firstName, secondName);
            assertThat(picked).isNotNull();
            assertThat(picked.get("id").asString()).isEqualTo("branch:" + first + ":" + OpenStudyAnalyses.ID_POC_004);

            var answered = ask(s, picked.get("label").asString(), "all", picked.get("id").asString());
            assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
            JsonNode result = json.readTree(answered.body());
            assertThat(result.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(result.get("securityGate").get("authorizedBranches").toString()).isEqualTo("[" + first + "]");
            assertThat(result.get("viewSpec").toString()).contains(firstName).doesNotContain("강남");
        }
        try (var s = login(staff)) {
            // With one branch in scope there is nothing to choose.
            var direct = ask(s, "지금 빈자리가 얼마나 있어?", Long.toString(first), null);
            assertThat(direct.statusCode()).as(direct.body()).isEqualTo(200);
            assertThat(json.readTree(direct.body()).get("status").asString()).isEqualTo("COMPLETED");
            // A choice naming a branch outside the scope is checked again and refused.
            var forged = ask(s, "지금 빈자리가 얼마나 있어?", Long.toString(first),
                    "branch:" + second + ":" + OpenStudyAnalyses.ID_POC_004);
            assertThat(forged.statusCode()).as(forged.body()).isEqualTo(403);
        }
    }

    @Test void theQualitySuiteIsTheOwnersOnly() throws Exception {
        try (var s = login(staff)) {
            assertThat(send(s, "POST", "/api/ai/evaluation/run", Map.of("suiteId", "POC_GOLDEN_10")).statusCode()).isEqualTo(403);
        }
    }

    @Test void theReferenceTimeIsNow() throws Exception {
        try (var s = login(owner)) {
            var response = send(s, "GET", "/api/ai/catalog/readiness", null);
            assertThat(response.statusCode()).isEqualTo(200);
            var asOf = OffsetDateTime.parse(json.readTree(response.body()).get("asOfKst").asString());
            assertThat(Duration.between(asOf.toInstant(), java.time.Instant.now()).abs()).isLessThan(Duration.ofMinutes(5));
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@ai-registered.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'AI 직원','AI',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        long id = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "ai-registered").id());
        branches.add(id);
        return id;
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(raw(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD)).statusCode())
                .isEqualTo(200);
        return new Session(client, json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString());
    }

    HttpResponse<String> ask(Session s, String question, String scope, String choice) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("question", question);
        body.put("scope", scope);
        if (choice != null) body.put("clarificationChoice", choice);
        return send(s, "POST", "/api/ai/queries", body);
    }

    HttpResponse<String> raw(HttpClient client, String method, String path, String csrf, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(Session s, String method, String path, Object body) throws Exception {
        return raw(s.client(), method, path, "GET".equals(method) ? null : s.token(), body);
    }
}
