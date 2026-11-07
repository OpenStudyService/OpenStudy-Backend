package com.openstudy.modules.passhistory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real sales, check-ins and check-outs over HTTP and MySQL for the #64 tests. {@code payer} holds payments:write at the
 * first branch, {@code clerk} is a plain member there, {@code foreign} holds payments:write at the second branch and
 * {@code dual} is a plain member of both, so every combination of purchase branch and usage branch can be exercised.
 */
@Import(PassUsageFixture.TimeConfiguration.class)
abstract class PassUsageFixture {
    static final Instant TEN = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired PaymentService payments;
    @Autowired MutableClock clock;
    @LocalServerPort int port;

    long owner, payer, clerk, foreign, dual, first, second;
    final Map<String, String> categoryIds = new HashMap<>();
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    int seatNumber;

    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock passUsageClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.fixed = TEN;
        owner = person("OWNER", "사장");
        payer = person("EMPLOYEE", "결제 담당"); clerk = person("EMPLOYEE", "일반 직원");
        foreign = person("EMPLOYEE", "둘째지점 결제 담당"); dual = person("EMPLOYEE", "양쪽 직원");
        first = branch("내역 첫 지점"); second = branch("내역 둘째 지점");
        member(payer, first); member(clerk, first); member(foreign, second); member(dual, first); member(dual, second);
        grant(payer, first, "payments:write"); grant(foreign, second, "payments:write");
        for (String kind : List.of("시간권", "기간권", "정액권")) categoryIds.put(kind, category(kind));
    }

    @AfterEach void clean() {
        clock.fixed = null;
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM seat_session_moves WHERE seat_session_id IN (SELECT id FROM seat_sessions WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM seat_sessions WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM passes WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customer_service_interactions WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long id : jdbc.queryForList("SELECT id FROM products WHERE created_by IN (" + actors + ")", Long.class)) {
            jdbc.update("DELETE FROM product_revisions WHERE original_product_id=?", id);
            jdbc.update("DELETE FROM product_branches WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM product_categories WHERE created_by IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM seats WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id IN (SELECT id FROM branch_memberships WHERE branch_id=?)", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@history.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("history-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "history-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
    }

    void grant(long person, long branch, String permission) {
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?", permission, person, branch);
    }

    String category(String kind) {
        var made = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류 " + kind, "kind", kind), "history-fixture");
        return json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    long seat(long branch) {
        String no = "H-" + ++seatNumber;
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'A',?,'GENERAL','AVAILABLE')", branch, no);
        return jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", Long.class, branch, no);
    }

    long customer(long branch, String name) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name);
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        body.put("email", "secret@customer.test"); body.put("memo", "내부 메모");
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "history-fixture").id());
        customerIds.add(id);
        return id;
    }

    long pass(long customer, long branch, Map<String, Object> terms) {
        String kind = (String) terms.get("kind");
        var product = new LinkedHashMap<String, Object>();
        product.put("name", token + " " + kind); product.put("priceKrw", 15437L); product.put("terms", terms); product.put("status", "판매 중");
        product.put("categoryId", categoryIds.get(kind)); product.put("branchIds", List.of(Long.toString(branch)));
        String productId = products.create(owner, "all", key(), product, "history-fixture").id();
        var sale = new LinkedHashMap<String, Object>();
        sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(branch));
        sale.put("productId", productId); sale.put("method", "카드");
        return Long.parseLong(payments.create(owner, "all", key(), sale, "history-fixture").passId());
    }

    static Map<String, Object> time(long minutes) { return time(minutes, null); }

    static Map<String, Object> time(long minutes, Long validDays) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", minutes); terms.put("validDays", validDays);
        return terms;
    }

    static Map<String, Object> stored(long credit, long rate) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "정액권"); terms.put("creditKrw", credit); terms.put("hourlyRateKrw", rate); terms.put("validDays", null);
        return terms;
    }

    static Map<String, Object> period(long days) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "기간권"); terms.put("periodDays", days);
        return terms;
    }

    void refund(long pass) {
        long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", pass);
        payments.refund(owner, payment, "all", key(), Map.of("reason", "고객 요청 환불"), "history-fixture");
    }

    static String key() { return UUID.randomUUID().toString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    long version(long pass) { return count("SELECT version FROM passes WHERE id=?", pass) + 1; }
    long minutes(long pass) { return count("SELECT remaining_minutes FROM passes WHERE id=?", pass); }
    String stored(long pass) { return text("SELECT status FROM passes WHERE id=?", pass); }
    long adjustments(long pass) { return count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=? AND kind='ADJUST'", pass); }
    long adjustAudits(long pass) { return count("SELECT COUNT(*) FROM audit_events WHERE entity_type='passes' AND entity_id=? AND action='ADJUSTED'", pass); }
    long ledgerSum(long pass) { return count("SELECT COALESCE(SUM(delta_minutes),0) FROM pass_balance_events WHERE pass_id=?", pass); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "history-test-password"), null).statusCode()).isEqualTo(200);
        return new Session(client, json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    HttpResponse<String> http(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return http(session.client(), "GET", path, null, null, null); }

    JsonNode ok(Session session, String path) throws Exception {
        var response = get(session, path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    HttpResponse<String> adjust(Session session, long pass, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/passes/" + pass + "/adjustments", session.token(), body, key);
    }

    JsonNode adjusted(Session session, long pass, Object body) throws Exception {
        var response = adjust(session, pass, body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    /** A valid adjustment body at the pass's current version; pairs are field name, value. */
    Map<String, Object> change(long pass, Object... pairs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version(pass)); body.put("reason", "현장 보정");
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    long entered(Session session, long seat, long customer, long pass) throws Exception {
        var response = checkIn(session, seat, customer, pass);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response).get("id").asLong();
    }

    HttpResponse<String> checkIn(Session session, long seat, long customer, long pass) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("seatId", Long.toString(seat)); body.put("customerId", Long.toString(customer)); body.put("passId", Long.toString(pass));
        return http(session.client(), "POST", "/api/seat-sessions", session.token(), body, key());
    }

    HttpResponse<String> checkOut(Session session, long id, String reason) throws Exception {
        Map<String, Object> body = reason == null ? Map.of() : Map.of("reason", reason);
        return http(session.client(), "POST", "/api/seat-sessions/" + id + "/check-out", session.token(), body, key());
    }

    JsonNode left(Session session, long id, String reason) throws Exception {
        var response = checkOut(session, id, reason);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    /** Sits on a fresh seat of {@code branch} at the current clock and leaves {@code minutes} later; the clock stays there. */
    JsonNode stay(Session session, long branch, long customer, long pass, long minutes, String reason) throws Exception {
        long id = entered(session, seat(branch), customer, pass);
        clock.fixed = clock.fixed.plusSeconds(minutes * 60);
        return left(session, id, reason);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).isEqualTo(code);
    }

    // ---- real concurrency -----------------------------------------------------------------------------------

    <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        var ready = new CountDownLatch(calls.size());
        var start = new CountDownLatch(1);
        try {
            var running = new ArrayList<Future<T>>();
            for (var call : calls) running.add(pool.submit(() -> { ready.countDown(); start.await(); return call.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : running) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
