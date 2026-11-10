package com.openstudy.modules.sales;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #115 over HTTP and MySQL (operations-extension-api.md "일별 마감", M13-D04·D08·D11): only a MANAGER of the branch with
 * sales:read closes an ended day, once; the snapshot equals the M9 revenue of the same day for both refund bases; a later
 * refund of a closed day's payment is processed and leaves an adjustment with its reason, processor and amount; and the
 * closing shows snapshot + adjustments = the current payment-date total. Payments made now are moved to yesterday in the
 * database, since only an ended day can be closed.
 */
@MySqlIntegrationTest
class DailyClosingIntegrationTests {
    static final String PASSWORD = "closing-test-password";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @LocalServerPort int port;

    long owner, manager, plainManager, accountant, seller, reader, first, second, customer, product;
    final LocalDate yesterday = LocalDate.now(SEOUL).minusDays(1);
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "마감 매니저");
        plainManager = person("EMPLOYEE", "매출 권한 없는 매니저");
        accountant = person("EMPLOYEE", "매출 직원");
        seller = person("EMPLOYEE", "판매 직원");
        reader = person("EMPLOYEE", "매출만 보는 매니저");
        first = branch("마감 첫 지점");
        second = branch("마감 둘째 지점");
        member(manager, first, "MANAGER", "sales:read", "payments:read", "payments:write");
        member(plainManager, first, "MANAGER", "payments:write");
        member(accountant, first, "STAFF", "sales:read");
        member(seller, first, "STAFF", "payments:write", "payments:read");
        member(reader, first, "MANAGER", "sales:read");
        var body = new LinkedHashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(first)); body.put("name", "마감 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        customer = Long.parseLong(customers.create(owner, "all", key(), body, "closing-fixture").id());
        customerIds.add(customer);
        var category = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "마감 분류", "kind", "시간권"), "closing-fixture");
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600); terms.put("validDays", null);
        var made = new LinkedHashMap<String, Object>();
        made.put("name", "마감 시간권"); made.put("priceKrw", 10000L); made.put("terms", terms); made.put("status", "판매 중");
        made.put("categoryId", json.readTree(json.writeValueAsString(category)).get("id").asString());
        made.put("branchIds", List.of(Long.toString(first)));
        product = Long.parseLong(products.create(owner, "all", key(), made, "closing-fixture").id());
    }

    @AfterEach void clean() {
        String ids = customerIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long id : branches) {
            jdbc.update("DELETE a FROM daily_closing_adjustments a JOIN daily_closings c ON c.id=a.closing_id WHERE c.branch_id=?", id);
            jdbc.update("DELETE FROM daily_closings WHERE branch_id=?", id);
        }
        jdbc.update("DELETE FROM passes WHERE customer_id IN (" + ids + ")");
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id IN (" + ids + "))");
        jdbc.update("DELETE FROM payments WHERE customer_id IN (" + ids + ")");
        jdbc.update("DELETE FROM customers WHERE id IN (" + ids + ")");
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long id : jdbc.queryForList("SELECT id FROM products WHERE created_by IN (" + actors + ")", Long.class)) {
            jdbc.update("DELETE FROM product_revisions WHERE original_product_id=?", id);
            jdbc.update("DELETE FROM product_branches WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM product_categories WHERE created_by IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- acceptance -----------------------------------------------------------------------------------------------

    /** AC 1: a MANAGER of the branch with sales:read closes an ended day, once. */
    @Test void onlyTheBranchManagerClosesAnEndedDayOnce() throws Exception {
        try (var lead = login(manager); var plain = login(plainManager); var staff = login(accountant); var boss = login(owner)) {
            refused(close(staff, yesterday, null), 403, "FORBIDDEN");
            refused(close(plain, yesterday, null), 403, "FORBIDDEN");
            refused(close(lead, LocalDate.now(SEOUL), null), 422, "CLOSING_DATE_OPEN");
            refused(close(lead, LocalDate.now(SEOUL).plusDays(1), null), 422, "CLOSING_DATE_OPEN");
            refused(http(lead, "POST", "/api/daily-closings", Map.of("branchId", Long.toString(first), "businessDate", "2026/11/01"), key()),
                    422, "VALIDATION_FAILED");
            refused(http(lead, "POST", "/api/daily-closings", Map.of("branchId", Long.toString(first), "businessDate", yesterday.toString()), null),
                    422, "VALIDATION_FAILED");
            String key = key();
            var made = created(http(lead, "POST", "/api/daily-closings", Map.of("branchId", Long.toString(first),
                    "businessDate", yesterday.toString(), "note", "마감"), key));
            assertThat(made.get("businessDate").asString()).isEqualTo(yesterday.toString());
            assertThat(made.at("/closedBy/name").asString()).isEqualTo("마감 매니저");
            // The same key replays; a second closing of the day names the first.
            assertThat(created(http(lead, "POST", "/api/daily-closings", Map.of("branchId", Long.toString(first),
                    "businessDate", yesterday.toString(), "note", "마감"), key)).get("id")).isEqualTo(made.get("id"));
            var twice = close(boss, yesterday, null);
            refused(twice, 409, "CLOSING_EXISTS");
            assertThat(node(twice).get("closingId").asString()).isEqualTo(made.get("id").asString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_closings WHERE branch_id=?", Long.class, first)).isEqualTo(1);
            // Reading needs sales:read at the branch; another branch's closing is not seen.
            refused(get(plain, "/api/daily-closings/" + made.get("id").asString()), 403, "FORBIDDEN");
            ok(get(staff, "/api/daily-closings/" + made.get("id").asString()));
            String other = created(close(boss, yesterday, second)).get("id").asString();
            refused(get(lead, "/api/daily-closings/" + other), 404, "NOT_FOUND");
            refused(get(lead, "/api/daily-closings?branchIds=" + second), 403, "FORBIDDEN");
            var listed = ok(get(lead, "/api/daily-closings"));
            assertThat(texts(listed.get("items"), "/id")).containsExactly(made.get("id").asString());
            assertThat(listed.at("/items/0/adjustmentCount").asLong()).isZero();
        }
    }

    /** AC 2: the snapshot equals the M9 revenue of the same branch and day for both refund bases. */
    @Test void theSnapshotEqualsTheM9RevenueOfTheDay() throws Exception {
        try (var lead = login(manager); var cash = login(seller)) {
            String card = paid(cash, "카드"), money = paid(cash, "현금"), earlier = paid(cash, "카드");
            moveApproval(card, yesterday.atTime(10, 0));
            moveApproval(money, yesterday.atTime(11, 0));
            moveApproval(earlier, yesterday.minusDays(1).atTime(9, 0));
            // Refunded yesterday: one of yesterday's sales and one of the day before.
            ok(refund(cash, money, "현금 환불"));
            ok(refund(cash, earlier, "이전 결제 환불"));
            moveRefund(money, yesterday.atTime(15, 0));
            moveRefund(earlier, yesterday.atTime(16, 0));

            var closing = created(close(lead, yesterday, null));
            for (String basis : List.of("processedDate", "paymentDate")) {
                var m9 = ok(get(lead, "/api/sales/revenue?branchIds=" + first + "&from=" + start(yesterday) + "&to=" + start(yesterday.plusDays(1))
                        + "&refundBasis=" + basis));
                var snapshot = closing.at("/snapshot/" + basis);
                for (String field : List.of("approvedAmountKrw", "refundAmountKrw", "netAmountKrw", "approvedPaymentCount", "refundCount"))
                    assertThat(snapshot.at("/totals/" + field)).as(basis + " " + field).isEqualTo(m9.at("/totals/" + field));
                for (int index = 0; index < 3; index++)
                    assertThat(snapshot.at("/byPurpose/" + index + "/netAmountKrw")).isEqualTo(m9.at("/byPurpose/" + index + "/netAmountKrw"));
                // The method split adds up to the totals.
                long net = 0;
                for (var method : snapshot.get("byMethod")) net += method.get("netAmountKrw").asLong();
                assertThat(net).as(basis).isEqualTo(snapshot.at("/totals/netAmountKrw").asLong());
            }
            // processedDate: two approvals, two refunds (one of the day before). paymentDate: the refund of yesterday's sale only.
            assertThat(closing.at("/snapshot/processedDate/totals/netAmountKrw").asLong()).isZero();
            assertThat(closing.at("/snapshot/paymentDate/totals/netAmountKrw").asLong()).isEqualTo(10000);
            assertThat(methodOf(closing.at("/snapshot/processedDate/byMethod"), "현금").get("refundAmountKrw").asLong()).isEqualTo(10000);
            assertThat(methodOf(closing.at("/snapshot/paymentDate/byMethod"), "카드").get("refundAmountKrw").asLong()).isZero();
            same("DailyClosing", closing);
            same("ClosingBasisSnapshot", closing.at("/snapshot/processedDate"));
            same("ClosingMethodAmounts", closing.at("/snapshot/processedDate/byMethod/0"));
        }
    }

    /** AC 3, 4 and 5: a later refund of a closed day's payment is processed and adjusts that closing; the equation holds. */
    @Test void aLaterRefundAdjustsTheClosingOfThePaymentDay() throws Exception {
        try (var lead = login(manager); var cash = login(seller); var sales = login(reader)) {
            String closedSale = paid(cash, "카드"), otherSale = paid(cash, "현금"), todaySale = paid(cash, "카드");
            moveApproval(closedSale, yesterday.atTime(10, 0));
            moveApproval(otherSale, yesterday.atTime(12, 0));
            String id = created(close(lead, yesterday, null)).get("id").asString();

            // The refund goes through after closing and joins the closing of its payment's day.
            ok(refund(cash, closedSale, "고객 변심"));
            // A refund of today's sale touches no closing.
            ok(refund(cash, todaySale, "오늘 환불"));
            var detail = ok(get(lead, "/api/daily-closings/" + id));
            var adjustments = detail.get("adjustments");
            assertThat(adjustments).hasSize(1);
            assertThat(adjustments.at("/0/paymentId").asString()).isEqualTo(closedSale);
            assertThat(adjustments.at("/0/amountKrw").asLong()).isEqualTo(-10000);
            assertThat(adjustments.at("/0/reason").asString()).isEqualTo("고객 변심");
            assertThat(adjustments.at("/0/processedBy/name").asString()).isEqualTo("판매 직원");
            same("DailyClosingAdjustment", adjustments.get(0));
            // Snapshot + adjustments = current (payment date); the processed-date snapshot of an ended day does not move.
            long snapshot = detail.at("/snapshot/paymentDate/totals/netAmountKrw").asLong();
            long current = detail.at("/current/paymentDate/netAmountKrw").asLong();
            assertThat(snapshot).isEqualTo(20000);
            assertThat(current).isEqualTo(10000);
            assertThat(detail.at("/difference/netAmountKrw").asLong()).isEqualTo(current - snapshot).isEqualTo(-10000);
            assertThat(detail.at("/difference/refundCount").asLong()).isEqualTo(1);
            var processed = ok(get(lead, "/api/sales/revenue?branchIds=" + first + "&from=" + start(yesterday) + "&to="
                    + start(yesterday.plusDays(1)))).get("totals");
            assertThat(detail.at("/snapshot/processedDate/totals/netAmountKrw")).isEqualTo(processed.get("netAmountKrw"));
            // The reason is free text: only payments:read sees it.
            assertThat(ok(get(sales, "/api/daily-closings/" + id)).at("/adjustments/0/reason").isNull()).isTrue();
            assertThat(ok(get(lead, "/api/daily-closings")).at("/items/0/adjustmentCount").asLong()).isEqualTo(1);
            same("DailyClosingSummary", ok(get(lead, "/api/daily-closings")).at("/items/0"));
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@closing.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"),
                "closing-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    String paid(Session session, String method) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(first));
        body.put("productId", Long.toString(product)); body.put("method", method);
        return created(http(session, "POST", "/api/payments", body, key())).get("id").asString();
    }

    void moveApproval(String payment, java.time.LocalDateTime seoul) {
        String at = SQL.format(seoul.atZone(SEOUL).toInstant());
        jdbc.update("UPDATE payments SET approved_at=?, created_at=? WHERE id=?", at, at, payment);
    }

    void moveRefund(String payment, java.time.LocalDateTime seoul) {
        jdbc.update("UPDATE refunds SET refunded_at=? WHERE payment_id=?", SQL.format(seoul.atZone(SEOUL).toInstant()), payment);
    }

    static Instant start(LocalDate day) { return day.atStartOfDay(SEOUL).toInstant(); }

    static String key() { return UUID.randomUUID().toString(); }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(send(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
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
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> http(Session session, String method, String path, Object body, String key) throws Exception {
        return send(session.client(), method, path, session.token(), body, key);
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return send(session.client(), "GET", path, null, null, null); }

    HttpResponse<String> close(Session session, LocalDate day, Long branch) throws Exception {
        return http(session, "POST", "/api/daily-closings", Map.of("branchId", Long.toString(branch == null ? first : branch),
                "businessDate", day.toString()), key());
    }

    HttpResponse<String> refund(Session session, String payment, String reason) throws Exception {
        return http(session, "POST", "/api/payments/" + payment + "/refund", Map.of("reason", reason), key());
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).as(response.body()).isEqualTo(code);
    }

    static JsonNode methodOf(JsonNode methods, String label) {
        for (var method : methods) if (method.get("method").asString().equals(label)) return method;
        throw new AssertionError(label);
    }

    static List<String> texts(JsonNode items, String pointer) {
        var values = new ArrayList<String>();
        for (var item : items) values.add(item.at(pointer).asString());
        return values;
    }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    void same(String schema, JsonNode actual) throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        var expected = new HashSet<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> expected.add(entry.getKey()));
        assertThat(fields(actual)).as(schema).isEqualTo(expected);
    }
}
