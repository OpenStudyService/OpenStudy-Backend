package com.openstudy.modules.retention;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.retention.repository.RetentionRepository;
import com.openstudy.modules.retention.service.RetentionService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #100 (ADR-M11-001 M11-D01·D05, ADR-M11-002): rules start disabled, cannot be enabled without a period or below their
 * legal minimum, anonymize only inactive customers while references and M9 sales stay the same, clean only what is past
 * the period, and leave a run record of counts.
 */
@MySqlIntegrationTest
class RetentionIntegrationTests {
    static final String PASSWORD = "retention-password";
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired RetentionService retention;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), customers = new ArrayList<>();
    long owner, branch;

    static Timestamp ago(long days) { return Timestamp.from(NOW.minus(Duration.ofDays(days))); }

    @BeforeEach void setup() {
        owner = person("OWNER");
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        branch = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create("보존 시험 지점", "서울", "02-123", hours, "운영 중"), "retention").id());
    }

    @AfterEach void clean() {
        // Rules back to their migrated state, so no other test meets an enabled rule.
        jdbc.update("DELETE FROM retention_runs");
        jdbc.update("UPDATE retention_rules SET enabled = 0");
        jdbc.update("UPDATE retention_rules SET period_days = NULL WHERE code = 'AUDIT_EVENT'");
        String ids = customers.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM payments WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM customers WHERE id IN (" + ids + ")");
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ") OR branch_id = ?", branch);
        jdbc.update("DELETE j FROM invitation_send_jobs j JOIN employee_invitations i ON i.id = j.invitation_id WHERE i.branch_id = ?", branch);
        jdbc.update("DELETE g FROM invitation_permission_grants g JOIN employee_invitations i ON i.id = g.invitation_id WHERE i.branch_id = ?", branch);
        jdbc.update("DELETE FROM employee_invitations WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM password_reset_tokens WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM branches WHERE id = ?", branch);
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    void enable(String rule, Integer days) {
        jdbc.update("UPDATE retention_rules SET enabled = 1, period_days = COALESCE(?, period_days) WHERE code = ?", days, rule);
    }

    @Test void everyRuleStartsDisabledAndADisabledRuleDoesNothing() {
        assertThat(jdbc.queryForList("SELECT code FROM retention_rules WHERE enabled = 1")).isEmpty();
        assertThat(jdbc.queryForList("SELECT code FROM retention_rules ORDER BY code", String.class))
                .containsExactlyInAnyOrderElementsOf(RetentionService.RULES);
        long old = customer("오래된 고객", 800, null);
        var outcomes = retention.runAll();
        assertThat(outcomes.values()).allSatisfy(outcome -> assertThat(outcome.ran()).isFalse());
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, old)).isEqualTo("오래된 고객");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_runs", Long.class)).isZero();
    }

    @Test void aRuleCannotBeEnabledWithoutAPeriodOrBelowItsLegalMinimum() {
        assertThatThrownBy(() -> jdbc.update("UPDATE retention_rules SET enabled = 1 WHERE code = 'AUDIT_EVENT'"))
                .hasStackTraceContaining("ck_retention_rules_1");
        assertThatThrownBy(() -> jdbc.update("UPDATE retention_rules SET period_days = 30 WHERE code = 'AUDIT_EVENT'"))
                .hasStackTraceContaining("ck_retention_rules_2");
        assertThat(jdbc.queryForObject("SELECT enabled FROM retention_rules WHERE code = 'AUDIT_EVENT'", Integer.class)).isZero();
    }

    @Test void anInactiveCustomerIsAnonymizedWhileItsReferencesAndSalesStay() throws Exception {
        long inactive = customer("익명화 대상", 800, "01011112222");
        long payment = payment(inactive, 400, "APPROVED");
        long pendingPayment = customer("결제 대기 고객", 800, "01033334444");
        payment(pendingPayment, 400, "PENDING");
        long recent = customer("최근 고객", 10, "01055556666");
        String window = "&from=" + NOW.minus(Duration.ofDays(410)) + "&to=" + NOW.minus(Duration.ofDays(390));
        JsonNode before;
        try (var s = login(owner)) {
            before = ok(s, "/api/sales/revenue?branchIds=" + branch + window).get("totals");
            assertThat(before.get("approvedAmountKrw").asLong()).isEqualTo(15_000);
        }

        enable("CUSTOMER_IDENTITY", null);
        var outcome = retention.run("CUSTOMER_IDENTITY");
        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.processed()).isGreaterThanOrEqualTo(1);

        var row = jdbc.queryForMap("SELECT name, phone, email, memo, member_no, anonymized_at FROM customers WHERE id = ?", inactive);
        assertThat(row.get("name")).isEqualTo(RetentionRepository.ANONYMIZED_NAME);
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("email")).isNull();
        assertThat(row.get("memo")).isNull();
        assertThat(row.get("member_no")).isNotNull();
        assertThat(row.get("anonymized_at")).isNotNull();
        // The row and its references stay, so the M9 figures are the same.
        assertThat(jdbc.queryForObject("SELECT customer_id FROM payments WHERE id = ?", Long.class, payment)).isEqualTo(inactive);
        try (var s = login(owner)) {
            assertThat(ok(s, "/api/sales/revenue?branchIds=" + branch + window).get("totals")).isEqualTo(before);
        }
        // Something still running (a pending payment) and recent use keep the customer as is.
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, pendingPayment)).isEqualTo("결제 대기 고객");
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, recent)).isEqualTo("최근 고객");

        // The run record has counts and times only; a second run does not touch the customer again.
        var run = jdbc.queryForMap("SELECT * FROM retention_runs WHERE rule_code = 'CUSTOMER_IDENTITY'");
        assertThat(run.get("result")).isEqualTo("SUCCESS");
        assertThat(((Number) run.get("period_days")).intValue()).isEqualTo(365);
        assertThat(run.toString()).doesNotContain("익명화 대상", "01011112222");
        long version = jdbc.queryForObject("SELECT version FROM customers WHERE id = ?", Long.class, inactive);
        retention.run("CUSTOMER_IDENTITY");
        assertThat(jdbc.queryForObject("SELECT version FROM customers WHERE id = ?", Long.class, inactive)).isEqualTo(version);
    }

    @Test void idleConversationsExpiredRecordsAndEndedMailHistoryAreRemovedAfterTheirPeriod() {
        String old = conversation(400);
        String fresh = conversation(5);
        jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,state,expires_at) "
                + "VALUES(?,'retention','global','expired',UNHEX(SHA2('a',256)),'PROCESSING',?),"
                + "(?,'retention','global','live',UNHEX(SHA2('b',256)),'PROCESSING',?)", owner, ago(1), owner, Timestamp.from(NOW.plus(Duration.ofDays(1))));
        jdbc.update("INSERT INTO password_reset_tokens(employee_id,requested_by,status,next_attempt_at,updated_at,used_at) VALUES(?,?,'USED',?,?,?)",
                owner, owner, ago(60), ago(60), ago(60));
        long unjoined = invitation("EXPIRED", 60);
        long joined = invitation("ACCEPTED", 60);

        for (String rule : List.of("AI_CONVERSATION", "IDEMPOTENCY_RECORD", "ACCOUNT_MAIL_HISTORY")) enable(rule, null);
        retention.runAll();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversations WHERE id = ?", Long.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executions WHERE conversation_id = ?", Long.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversations WHERE id = ?", Long.class, fresh)).isEqualTo(1);
        // Creating the branch left its own live record too; only the expired one goes.
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM idempotency_records WHERE actor_employee_id = ?", String.class, owner))
                .contains("live").doesNotContain("expired");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE employee_id = ?", Long.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee_invitations WHERE id = ?", Long.class, unjoined)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee_invitations WHERE id = ?", Long.class, joined)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT rule_code FROM retention_runs WHERE result = 'SUCCESS'", String.class))
                .containsExactlyInAnyOrder("AI_CONVERSATION", "IDEMPOTENCY_RECORD", "ACCOUNT_MAIL_HISTORY");
    }

    @Test void auditCleanupRemovesOnlyEventsPastItsPeriod() {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,branch_id,entity_type,action,result,occurred_at) VALUES"
                + "(?,?,'retention-test','OLD','SUCCESS',?),(?,?,'retention-test','KEPT','SUCCESS',?)", owner, branch, ago(1500), owner, branch, ago(800));
        enable("AUDIT_EVENT", 1095);
        var outcome = retention.run("AUDIT_EVENT");
        assertThat(outcome.ran()).isTrue();
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type = 'retention-test'", String.class)).containsExactly("KEPT");
        var run = jdbc.queryForMap("SELECT * FROM retention_runs WHERE rule_code = 'AUDIT_EVENT'");
        assertThat(run.get("result")).isEqualTo("SUCCESS");
        assertThat(((Number) run.get("processed_count")).intValue()).isGreaterThanOrEqualTo(1);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@retention.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'보존 직원','보존',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long customer(String name, long daysAgo, String phone) {
        String memberNo = "RET-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO customers(member_no,name,phone,email,memo,registered_branch_id,registered_at) VALUES(?,?,?,?,?,?,?)",
                memberNo, name, phone, phone == null ? null : phone + "@example.test", "메모", branch, ago(daysAgo));
        long id = jdbc.queryForObject("SELECT id FROM customers WHERE member_no = ?", Long.class, memberNo);
        customers.add(id);
        return id;
    }

    long payment(long customer, long daysAgo, String status) {
        String key = "retention-" + UUID.randomUUID();
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,created_by,created_at,approved_at) "
                + "VALUES(?,?,'LOCKER','CASH',15000,?,?,?,?,?)", customer, branch, status, key, owner, ago(daysAgo),
                "APPROVED".equals(status) ? ago(daysAgo) : null);
        return jdbc.queryForObject("SELECT id FROM payments WHERE idempotency_key = ?", Long.class, key);
    }

    String conversation(long daysAgo) {
        String id = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_conversations(id,employee_id,scope_key,title,created_at,updated_at) VALUES(?,?,'all','보존',?,?)",
                id, owner, ago(daysAgo), ago(daysAgo));
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,status,"
                        + "as_of_utc,snapshot_version,policy_version,created_at) VALUES(?,?,?,?,'all','','q','READY',?,'s','p',?)",
                "exec-" + id, "req-" + id, id, owner, ago(daysAgo), ago(daysAgo));
        return id;
    }

    long invitation(String status, long daysAgo) {
        String email = UUID.randomUUID() + "@invite.test";
        boolean accepted = "ACCEPTED".equals(status);
        jdbc.update("INSERT INTO employee_invitations(branch_id,invitee_email,invitee_name,initial_role,created_by,expires_at,status,updated_at,"
                + "accepted_employee_id,accepted_at) VALUES(?,?,'초대','STAFF',?,?,?,?,?,?)", branch, email, owner, ago(daysAgo - 7), status,
                ago(daysAgo), accepted ? owner : null, accepted ? ago(daysAgo) : null);
        return jdbc.queryForObject("SELECT id FROM employee_invitations WHERE invitee_email = ?", Long.class, email);
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    record Session(HttpClient client) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(get(client, "/api/auth/csrf").body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        var login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        return new Session(client);
    }

    HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(Session s, String path) throws Exception {
        var response = get(s.client(), path);
        assertThat(response.statusCode()).as("%s %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
