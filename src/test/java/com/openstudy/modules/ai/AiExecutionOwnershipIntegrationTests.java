package com.openstudy.modules.ai;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * An AI execution belongs to the employee who ran it (ADR-M10-002 M10-D12). Looking it up, cancelling it, or using it
 * for a work record or a CSV export request is a 404 for anyone else, OWNER included, even with full access to the
 * execution's branch, and leaves the execution unchanged. The author keeps working as before.
 */
@MySqlIntegrationTest
class AiExecutionOwnershipIntegrationTests {
    static final String PASSWORD = "ai-ownership-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    final List<String> conversations = new ArrayList<>();
    long owner, author, colleague, branch;

    @BeforeEach void setup() {
        owner = person("OWNER");
        author = person("EMPLOYEE");
        colleague = person("EMPLOYEE");
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        branch = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create("AI 실행 지점", "서울", "02-123", hours, "운영 중"), "ai-ownership").id());
        branches.add(branch);
        // Same grants on the same branch: only who ran the execution tells them apart.
        member(author, branch, "ai:read", "sales:read");
        member(colleague, branch, "ai:read", "sales:read");
    }

    @AfterEach void clean() {
        for (String id : conversations) {
            jdbc.update("DELETE FROM ai_work_records WHERE execution_id IN (SELECT id FROM ai_executions WHERE conversation_id=?)", id);
            jdbc.update("DELETE FROM ai_export_approvals WHERE execution_id IN (SELECT id FROM ai_executions WHERE conversation_id=?)", id);
            jdbc.update("DELETE FROM ai_executions WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM ai_conversations WHERE id=?", id);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    @Test void anotherEmployeesExecutionIsA404ForLookupCancelAndReuseAndStaysUnchanged() throws Exception {
        String execution = execution(conversation(author), author);
        for (long other : List.of(colleague, owner)) {
            try (var s = login(other)) {
                for (String id : List.of(execution, "req-" + execution)) {
                    assertNotFound(send(s, "GET", "/api/ai/executions/" + id, null));
                    assertNotFound(send(s, "POST", "/api/ai/requests/" + id + "/cancel", null));
                }
                assertNotFound(send(s, "POST", "/api/ai/records", Map.of("executionId", execution, "title", "가로채기")));
                assertNotFound(send(s, "POST", "/api/ai/approvals",
                        Map.of("executionId", execution, "datasetId", "branchSales", "reason", "가로채기")));
            }
        }
        assertThat(status(execution)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_work_records WHERE execution_id=?", Long.class, execution)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_export_approvals WHERE execution_id=?", Long.class, execution)).isZero();
    }

    @Test void theAuthorCanStillLookUpReuseAndCancelTheirOwnExecution() throws Exception {
        String execution = execution(conversation(author), author);
        try (var s = login(author)) {
            var found = send(s, "GET", "/api/ai/executions/" + execution, null);
            assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
            assertThat(json.readTree(found.body()).get("id").asString()).isEqualTo(execution);
            assertThat(json.readTree(send(s, "GET", "/api/ai/executions/req-" + execution, null).body()).get("id").asString())
                    .isEqualTo(execution);

            var record = send(s, "POST", "/api/ai/records", Map.of("executionId", execution, "title", "내 기록"));
            assertThat(record.statusCode()).as(record.body()).isEqualTo(200);

            var canceled = send(s, "POST", "/api/ai/requests/req-" + execution + "/cancel", null);
            assertThat(canceled.statusCode()).as(canceled.body()).isEqualTo(200);
            JsonNode body = json.readTree(canceled.body());
            assertThat(body.get("status").asString()).isEqualTo("CANCELED");

            // An id that does not exist answers exactly like someone else's.
            assertNotFound(send(s, "GET", "/api/ai/executions/exec-missing", null));
            assertNotFound(send(s, "POST", "/api/ai/requests/req-missing/cancel", null));

            // The older aliases are gone (Frontend #84 moved to /api/ai): even the author's own execution is not there.
            for (var gone : List.of(send(s, "GET", "/api/executions/" + execution, null),
                    send(s, "GET", "/api/catalog/readiness", null),
                    send(s, "POST", "/api/conversations", Map.of("title", "옛 경로")),
                    send(s, "POST", "/api/conversations/conv-x/requests", Map.of("question", "지점별 매출을 비교해줘")),
                    send(s, "POST", "/api/requests/req-" + execution + "/cancel", null))) {
                assertThat(gone.statusCode()).as(gone.uri() + " " + gone.body()).isEqualTo(404);
            }
        }
        assertThat(status(execution)).isEqualTo("CANCELED");
    }

    void assertNotFound(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@ai-ownership.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'AI 직원','AI',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    String conversation(long employee) {
        String id = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_conversations(id,employee_id,scope_key,title) VALUES(?,?,'all','매출 점검')", id, employee);
        conversations.add(id);
        return id;
    }

    String execution(String conversation, long employee) {
        String id = "exec-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,"
                        + "analysis_id,intent_domain,status,as_of_utc,snapshot_version,policy_version,created_at) "
                        + "VALUES(?,?,?,?,'all',?,'지점별 매출을 비교해줘',?,'sales','READY','2026-11-01 00:00:00','s1','p1',"
                        + "'2026-11-01 01:00:00')",
                id, "req-" + id, conversation, employee, String.valueOf(branch), OpenStudyAnalyses.ID_POC_001);
        return id;
    }

    String status(String execution) {
        return jdbc.queryForObject("SELECT status FROM ai_executions WHERE id=?", String.class, execution);
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
