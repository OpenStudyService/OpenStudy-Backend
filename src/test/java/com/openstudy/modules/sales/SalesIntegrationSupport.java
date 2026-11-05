package com.openstudy.modules.sales;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures of the #87 integration tests: the worked example of aggregation-api.md "계산 예시", built through the real
 * payment and refund paths at pinned instants, plus the rows the current API cannot produce (a refund processed at
 * another branch) written directly. Every test owns its branches, so other tests' rows never enter an aggregation.
 */
@MySqlIntegrationTest
@Import(SalesIntegrationSupport.TimeConfiguration.class)
abstract class SalesIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired PaymentService payments;
    @Autowired TransactionTemplate transactions;
    @Autowired MutableClock clock;
    /** A pass-through spy; only the snapshot test stubs it. Declared here so every sales test shares one context. */
    @MockitoSpyBean SalesSourceRepository sources;
    @LocalServerPort int port;

    /** null follows the real clock. The fixture pins it for every sale and refund, then to the example's asOf. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock salesClock() { return new MutableClock(); }
    }

    static final String PASSWORD = "sales-test-password";
    /** The example's asOf: 2026-12-05 in Asia/Seoul. */
    static final Instant AS_OF = seoul("2026-12-05T12:00");

    long owner, seller, reader, firstOnly, nobody, salesOnly, first, second;
    long customer;
    /** Payment IDs of the example, P1~P7, and the refund IDs of P1, P2 and P6. */
    long p1, p2, p3, p4, p5, p6, p7, r1, r2, r6;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String category;

    @BeforeEach void setupPeople() {
        clock.fixed = null;
        owner = person("OWNER");
        seller = person("EMPLOYEE"); reader = person("EMPLOYEE"); firstOnly = person("EMPLOYEE");
        nobody = person("EMPLOYEE"); salesOnly = person("EMPLOYEE");
        first = branch("매출 첫 지점"); second = branch("매출 둘째 지점");
        member(seller, first, "payments:write", "payments:read");
        member(seller, second, "payments:write", "payments:read");
        member(reader, first, "sales:read", "payments:read");
        member(reader, second, "sales:read");
        member(firstOnly, first, "sales:read");
        member(firstOnly, second, "payments:read");
        member(nobody, first, "payments:read");
        member(salesOnly, first, "sales:read");
        member(salesOnly, second, "sales:read");
        category = category();
        customer = customer(first, "매출 고객");
    }

    @AfterEach void clean() {
        Mockito.reset(sources);
        clock.fixed = null;
        for (long id : customerIds) {
            jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM passes WHERE customer_id=?", id);
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customer_status_events WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
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
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    /**
     * The example of aggregation-api.md "계산 예시". P1, P3, P4, P6 and P7 go through POST /payments and P1·P6's refunds
     * through POST /payments/{id}/refund at pinned instants; P2 is the room payment and cancellation refund through the
     * payment service the reservation module calls; P5 is a locker payment inserted directly. P6's refund is then moved to
     * branch 2, which the current API never does (M4-D08) but the schema allows.
     */
    void workedExample() throws Exception {
        long tenThousand = product("1만", 10_000), fifteen = product("1만5천", 15_000), twelve = product("1만2천", 12_000),
                nine = product("9천", 9_000);
        p1 = sold(first, tenThousand, seoul("2026-10-31T23:30"), true);
        p3 = sold(first, fifteen, seoul("2026-11-30T23:59:59"), true);
        p4 = sold(first, fifteen, seoul("2026-11-04T15:00"), false);
        p6 = sold(first, twelve, seoul("2026-11-10T10:00"), true);
        p7 = sold(second, nine, seoul("2026-12-01T00:00"), true);
        r1 = refunded(p1, seoul("2026-11-02T10:00"));
        r6 = refunded(p6, seoul("2026-12-02T10:00"));
        p2 = transactions.execute(status -> payments.chargeRoom(seller, customer, first, "카드", 6_000, true,
                "TEST-ROOM-" + UUID.randomUUID(), seoul("2026-11-01T00:30"), "sales-fixture"));
        r2 = transactions.execute(status -> payments.refundRoom(seller, p2, "예약 취소", "TEST-RFD-" + UUID.randomUUID(),
                seoul("2026-11-03T12:00"), "sales-fixture"));
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,"
                + "issued_pass_quantity,approved_at,created_by) VALUES(?,?,'LOCKER','CASH',20000,'APPROVED',?,0,?,?)",
                customer, first, "TEST-LOCKER-" + UUID.randomUUID(), utc(seoul("2026-11-04T15:00")), seller);
        p5 = jdbc.queryForObject("SELECT MAX(id) FROM payments WHERE customer_id=? AND purpose='LOCKER'", Long.class, customer);
        jdbc.update("UPDATE refunds SET processing_branch_id=? WHERE id=?", second, r6);
        clock.fixed = AS_OF;
    }

    /** Sessions follow the clock, so each pinned instant logs in anew. */
    long sold(long branch, long product, Instant at, boolean approved) throws Exception {
        clock.fixed = at;
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(branch));
        body.put("productId", Long.toString(product)); body.put("method", "카드");
        if (!approved) body.put("mockOutcome", "FAILED");
        try (var s = login(seller)) {
            var made = http(s, "POST", "/api/payments", body, key());
            assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
            return node(made).get("id").asLong();
        }
    }

    long refunded(long payment, Instant at) throws Exception {
        clock.fixed = at;
        try (var s = login(seller)) {
            var made = http(s, "POST", "/api/payments/" + payment + "/refund", Map.of("reason", "고객 요청 010-1234-5678"), key());
            assertThat(made.statusCode()).as(made.body()).isEqualTo(200);
            return Long.parseLong(node(made).at("/refund/id").asString());
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@sales.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'매출 직원','매출',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "sales-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    String category() {
        var made = categories.create(owner, key(), Map.of("code", "S" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "매출 분류", "kind", "시간권"), "sales-fixture");
        return json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    long product(String name, long price) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600L); terms.put("validDays", null);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", token + " " + name); body.put("priceKrw", price); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", category);
        body.put("branchIds", List.of(Long.toString(first), Long.toString(second)));
        return Long.parseLong(products.create(owner, "all", key(), body, "sales-fixture").id());
    }

    long customer(long branch, String name) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name); body.put("phone", "01012345678");
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "sales-fixture").id());
        customerIds.add(id);
        return id;
    }

    String categoryCode() { return jdbc.queryForObject("SELECT code FROM product_categories WHERE id=?", String.class, category); }

    static Instant seoul(String local) { return java.time.LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Seoul")).toInstant(); }

    /** A DATETIME(6) literal in UTC, as the application stores it. */
    static String utc(Instant at) { return at.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' '); }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    // ---- HTTP -----------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var issued = send(client, "GET", "/api/auth/csrf", null, null, null);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        String csrf = json.readTree(issued.body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(send(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(200);
        return new Session(client, json.readTree(send(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    HttpResponse<String> send(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> http(Session s, String method, String path, Object body, String key) throws Exception {
        return send(s.client(), method, path, s.token(), body, key);
    }

    HttpResponse<String> get(Session s, String path) throws Exception { return send(s.client(), "GET", path, null, null, null); }

    /** A 200 body. */
    JsonNode ok(Session s, String path) throws Exception {
        var response = get(s, path);
        assertThat(response.statusCode()).as("%s %s", path, response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        return node(response);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    /** Query string of a period given in Asia/Seoul local time, offset +09:00 encoded. */
    static String period(String from, String to) {
        return "from=" + enc(from + "+09:00") + "&to=" + enc(to + "+09:00");
    }

    static String november() { return period("2026-11-01T00:00", "2026-12-01T00:00"); }
    static String october() { return period("2026-10-01T00:00", "2026-11-01T00:00"); }
    static String december() { return period("2026-12-01T00:00", "2027-01-01T00:00"); }

    String branchIds(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> "branchIds=" + id).collect(Collectors.joining("&"));
    }

    /** approved, refund, net, approvedCount, refundCount and dataStatus of a RevenueAmounts-shaped node. */
    static void amounts(JsonNode node, long approved, long refund, long net, long approvedCount, long refundCount, String status) {
        assertThat(node.get("approvedAmountKrw").asLong()).as("approved %s", node).isEqualTo(approved);
        assertThat(node.get("refundAmountKrw").asLong()).as("refund %s", node).isEqualTo(refund);
        assertThat(node.get("netAmountKrw").asLong()).as("net %s", node).isEqualTo(net);
        assertThat(node.get("approvedPaymentCount").asLong()).as("approved count %s", node).isEqualTo(approvedCount);
        assertThat(node.get("refundCount").asLong()).as("refund count %s", node).isEqualTo(refundCount);
        assertThat(node.get("dataStatus").asString()).as("status %s", node).isEqualTo(status);
    }

    static List<String> fields(JsonNode node) {
        var names = new ArrayList<String>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
