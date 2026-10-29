package com.openstudy.modules.payment;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.ticket.service.PassService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

@MySqlIntegrationTest
class PaymentIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired PaymentService payments;
    @MockitoSpyBean PassService passes;
    @LocalServerPort int port;

    long owner, seller, reader, viewer, secondSeller, first, second;
    String timeCategory, periodCategory, storedCategory;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @BeforeEach void setup() {
        owner = person("OWNER");
        seller = person("EMPLOYEE"); reader = person("EMPLOYEE"); viewer = person("EMPLOYEE"); secondSeller = person("EMPLOYEE");
        first = branch("결제 첫 지점"); second = branch("결제 둘째 지점");
        member(seller, first, "payments:write", "payments:read");
        member(reader, first, "payments:read");
        member(viewer, first);
        member(secondSeller, second, "payments:write", "payments:read");
        timeCategory = category("시간권");
        periodCategory = category("기간권");
        storedCategory = category("정액권");
    }

    @AfterEach void clean() {
        Mockito.reset(passes);
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM passes WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customer_status_events WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        String actors = people.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
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

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@payment.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'결제 직원','결제',?,'ACTIVE')",
                email, passwords.encode("payment-test-password"), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "payment-fixture");
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

    String category(String kind) {
        var made = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류 " + kind, "kind", kind), "payment-fixture");
        return json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    long product(String name, long price, Map<String, Object> terms, String category, long... branchIds) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", token + " " + name); body.put("priceKrw", price); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", category);
        body.put("branchIds", java.util.Arrays.stream(branchIds).mapToObj(String::valueOf).toList());
        return Long.parseLong(products.create(owner, "all", key(), body, "payment-fixture").id());
    }

    Map<String, Object> time(long minutes, Long validDays) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", minutes); terms.put("validDays", validDays);
        return terms;
    }

    Map<String, Object> period(long days) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "기간권"); terms.put("periodDays", days);
        return terms;
    }

    Map<String, Object> stored(long credit, long rate, Long validDays) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "정액권"); terms.put("creditKrw", credit); terms.put("hourlyRateKrw", rate); terms.put("validDays", validDays);
        return terms;
    }

    long customer(long branch, String name) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name); body.put("phone", "01012345678");
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "payment-fixture").id());
        customerIds.add(id);
        return id;
    }

    Map<String, Object> sale(long customer, long branch, long product) {
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(branch));
        body.put("productId", Long.toString(product)); body.put("method", "카드");
        return body;
    }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    Instant at(String sql, Object... args) { return jdbc.queryForObject(sql, Timestamp.class, args).toInstant(); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "payment-test-password"), null).statusCode()).isEqualTo(200);
        return new Session(client, json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    HttpResponse<String> http(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return http(session.client(), "GET", path, null, null, null); }

    HttpResponse<String> pay(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/payments", session.token(), body, key);
    }

    HttpResponse<String> refund(Session session, long payment, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/payments/" + payment + "/refund", session.token(), body, key);
    }

    static Map<String, Object> reason(String text) { return Map.of("reason", text); }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }

    // ---- sale and issue -------------------------------------------------------------------------------------

    @Test void approvedSaleChargesTheServerPriceAndIssuesOnePassAtomically() throws Exception {
        long product = product("10시간", 15000, time(600, 30L), timeCategory, first);
        long customer = customer(first, "구매 고객");
        try (var s = login(seller)) {
            var made = pay(s, sale(customer, first, product), key());
            assertThat(made.statusCode()).isEqualTo(201);
            var payment = node(made);
            long id = payment.get("id").asLong();
            assertThat(made.headers().firstValue("Location")).contains("/api/payments/" + id);
            assertThat(payment.get("mock").asBoolean()).isTrue();
            assertThat(payment.get("purpose").asString()).isEqualTo("이용권");
            assertThat(payment.get("status").asString()).isEqualTo("승인");
            assertThat(payment.get("method").asString()).isEqualTo("카드");
            assertThat(payment.get("amountKrw").asLong()).isEqualTo(15000);
            assertThat(payment.get("productName").asString()).isEqualTo(token + " 10시간");
            assertThat(payment.at("/customer/name").asString()).isEqualTo("구매 고객");
            assertThat(payment.at("/createdBy/id").asString()).isEqualTo(Long.toString(seller));
            assertThat(payment.at("/createdBy/name").asString()).isEqualTo("결제 직원");
            assertThat(payment.get("failedAt").isNull()).isTrue();
            assertThat(payment.get("refund").isNull()).isTrue();
            assertThat(payment.get("canRefund").asBoolean()).isTrue();
            long passId = payment.get("passId").asLong();

            var row = jdbc.queryForMap("SELECT status, issued_pass_quantity, amount_krw, purpose FROM payments WHERE id=?", id);
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(((Number) row.get("issued_pass_quantity")).intValue()).isEqualTo(1);
            Instant approvedAt = at("SELECT approved_at FROM payments WHERE id=?", id);
            assertThat(payment.get("approvedAt").asString()).isEqualTo(approvedAt.toString());

            // The pass traces back to customer, sale branch, payment and the product revision sold.
            var pass = jdbc.queryForMap("SELECT customer_id, purchase_branch_id, purchase_payment_id, purchase_product_revision_id, "
                    + "status, remaining_minutes, snapshot_price_krw FROM passes WHERE id=?", passId);
            assertThat(((Number) pass.get("customer_id")).longValue()).isEqualTo(customer);
            assertThat(((Number) pass.get("purchase_branch_id")).longValue()).isEqualTo(first);
            assertThat(((Number) pass.get("purchase_payment_id")).longValue()).isEqualTo(id);
            assertThat(((Number) pass.get("purchase_product_revision_id")).longValue()).isEqualTo(
                    count("SELECT MAX(id) FROM product_revisions WHERE original_product_id=?", product));
            assertThat(pass.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) pass.get("remaining_minutes")).longValue()).isEqualTo(600);
            assertThat(at("SELECT started_at FROM passes WHERE id=?", passId)).isEqualTo(approvedAt);
            assertThat(at("SELECT expires_at FROM passes WHERE id=?", passId)).isEqualTo(approvedAt.plus(Duration.ofHours(24 * 30)));

            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='payments' AND entity_id=? AND action='APPROVED'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='passes' AND entity_id=? AND action='ISSUED'", passId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='payments' AND entity_id=? AND branch_id=?", id, first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='passes' AND entity_id=? AND branch_id=?", passId, first)).isEqualTo(1);

            var issued = node(get(s, "/api/passes/" + passId));
            assertThat(issued.get("kind").asString()).isEqualTo("시간권");
            assertThat(issued.get("status").asString()).isEqualTo("활성");
            assertThat(issued.get("remainingMinutes").asLong()).isEqualTo(600);
            assertThat(issued.get("remainingCreditKrw").isNull()).isTrue();
            assertThat(issued.at("/terms/includedMinutes").asLong()).isEqualTo(600);
            assertThat(issued.get("startedAt").asString()).isEqualTo(approvedAt.toString());
            assertThat(issued.get("purchasePaymentId").asString()).isEqualTo(Long.toString(id));
            assertThat(issued.get("used").asBoolean()).isFalse();
            assertThat(issued.get("canRefund").asBoolean()).isTrue();
            assertThat(issued.has("priceKrw")).isFalse();
            assertThat(issued.has("snapshotPriceKrw")).isFalse();
        }
    }

    @Test void everyKindStartsAtIssueAndExpiresByItsOwnTerm() throws Exception {
        long period = product("30일", 90000, period(30), periodCategory, first);
        long storedOpen = product("충전 5만", 50000, stored(50000, 2000, null), storedCategory, first);
        long storedLimited = product("충전 3만 90일", 30000, stored(30000, 2000, 90L), storedCategory, first);
        long timeOpen = product("5시간 무기한", 8000, time(300, null), timeCategory, first);
        long customer = customer(first, "유형 고객");
        try (var s = login(seller)) {
            for (long product : List.of(period, storedOpen, storedLimited, timeOpen))
                assertThat(pay(s, sale(customer, first, product), key()).statusCode()).isEqualTo(201);
            var rows = node(get(s, "/api/passes?customerId=" + customer + "&sort=expiresAt&direction=asc")).get("items");
            assertThat(rows).hasSize(4);
            for (var pass : rows) {
                assertThat(pass.get("status").asString()).isEqualTo("활성");
                assertThat(pass.get("startedAt").isNull()).isFalse();
            }
            var byName = new HashMap<String, JsonNode>();
            rows.forEach(pass -> byName.put(pass.get("productName").asString().substring(token.length() + 1), pass));
            Instant start = Instant.parse(byName.get("30일").get("startedAt").asString());
            assertThat(Instant.parse(byName.get("30일").get("expiresAt").asString())).isEqualTo(start.plus(Duration.ofHours(24 * 30)));
            assertThat(byName.get("30일").get("remainingMinutes").isNull()).isTrue();
            Instant limited = Instant.parse(byName.get("충전 3만 90일").get("startedAt").asString());
            assertThat(Instant.parse(byName.get("충전 3만 90일").get("expiresAt").asString())).isEqualTo(limited.plus(Duration.ofHours(24 * 90)));
            assertThat(byName.get("충전 3만 90일").get("remainingCreditKrw").asLong()).isEqualTo(30000);
            assertThat(byName.get("충전 5만").get("expiresAt").isNull()).isTrue();
            assertThat(byName.get("5시간 무기한").get("expiresAt").isNull()).isTrue();
            // No expiry sorts after every dated pass.
            assertThat(rows.get(2).get("expiresAt").isNull()).isTrue();
            assertThat(rows.get(3).get("expiresAt").isNull()).isTrue();
            assertThat(names(node(get(s, "/api/passes?customerId=" + customer + "&kind=" + enc("정액권"))))).hasSize(2);
            assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=? AND status='NOT_STARTED'", customer)).isZero();
        }
    }

    @Test void failedMockPaymentIsRecordedWithoutAPass() throws Exception {
        long product = product("실패용", 12000, time(300, null), timeCategory, first);
        long customer = customer(first, "실패 고객");
        try (var s = login(seller)) {
            var body = sale(customer, first, product);
            body.put("mockOutcome", "FAILED");
            var made = pay(s, body, key());
            assertThat(made.statusCode()).isEqualTo(201);
            assertThat(node(made).get("status").asString()).isEqualTo("실패");
            assertThat(node(made).get("passId").isNull()).isTrue();
            assertThat(node(made).get("approvedAt").isNull()).isTrue();
            assertThat(node(made).get("failedAt").isNull()).isFalse();
            assertThat(node(made).get("canRefund").asBoolean()).isFalse();
            long id = node(made).get("id").asLong();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, id)).isEqualTo("FAILED");
            assertThat(count("SELECT issued_pass_quantity FROM payments WHERE id=?", id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=?", customer)).isZero();
            assertThat(names(node(get(s, "/api/payments?status=" + enc("실패") + "&customerId=" + customer)))).hasSize(1);
            assertThat(names(node(get(s, "/api/payments?status=" + enc("승인") + "&customerId=" + customer)))).isEmpty();
        }
    }

    @Test void requestCannotCarryAnAmountOrOtherServerOwnedField() throws Exception {
        long product = product("금액 필드", 10000, time(60, null), timeCategory, first);
        long customer = customer(first, "금액 고객");
        try (var s = login(seller)) {
            for (String field : List.of("amountKrw", "productName", "quantity", "status")) {
                var body = sale(customer, first, product);
                body.put(field, field.equals("amountKrw") || field.equals("quantity") ? 1 : "x");
                var rejected = pay(s, body, key());
                assertThat(rejected.statusCode()).isEqualTo(422);
            }
            var missing = sale(customer, first, product);
            missing.remove("method");
            assertThat(pay(s, missing, key()).statusCode()).isEqualTo(422);
            var badMethod = sale(customer, first, product);
            badMethod.put("method", "CARD");
            assertThat(pay(s, badMethod, key()).statusCode()).isEqualTo(422);
            var badOutcome = sale(customer, first, product);
            badOutcome.put("mockOutcome", "PENDING");
            assertThat(pay(s, badOutcome, key()).statusCode()).isEqualTo(422);
            assertThat(pay(s, sale(customer, first, product), null).statusCode()).isEqualTo(422);
            assertThat(pay(s, sale(customer, first, product), "short").statusCode()).isEqualTo(422);
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer)).isZero();
        }
    }

    @Test void saleIsRefusedForStoppedProductsOtherBranchesBlockedCustomersAndMissingRights() throws Exception {
        long product = product("거부용", 10000, time(60, null), timeCategory, first);
        long secondOnly = product("둘째 전용", 10000, time(60, null), timeCategory, second);
        long customer = customer(first, "거부 고객");
        long elsewhere = customer(second, "둘째 고객");
        try (var s = login(seller); var r = login(reader); var o = login(owner)) {
            assertThat(code(pay(s, sale(customer, first, secondOnly), key()))).isEqualTo("PRODUCT_NOT_AVAILABLE_AT_BRANCH");
            assertThat(pay(s, sale(customer, first, 999_999_999L), key()).statusCode()).isEqualTo(404);
            // A customer outside the seller's lookup scope is not found, as on the customer API.
            assertThat(pay(s, sale(elsewhere, first, product), key()).statusCode()).isEqualTo(404);
            assertThat(pay(s, sale(customer, second, product), key()).statusCode()).isEqualTo(403);
            assertThat(pay(r, sale(customer, first, product), key()).statusCode()).isEqualTo(403);

            customers.changeStatus(owner, customer, "all", Map.of("version", 1, "status", "이용 제한", "reason", "테스트"), "payment-fixture");
            var restricted = pay(s, sale(customer, first, product), key());
            assertThat(restricted.statusCode()).isEqualTo(409);
            assertThat(code(restricted)).isEqualTo("CUSTOMER_NOT_ACTIVE");
            customers.changeStatus(owner, customer, "all", Map.of("version", 2, "status", "비활성", "reason", "테스트"), "payment-fixture");
            assertThat(code(pay(s, sale(customer, first, product), key()))).isEqualTo("CUSTOMER_NOT_ACTIVE");
            customers.changeStatus(owner, customer, "all", Map.of("version", 3, "status", "정상", "reason", "테스트"), "payment-fixture");

            products.changeStatus(owner, product, "all", Map.of("version", 1, "status", "판매 중지"), "payment-fixture");
            var stopped = pay(s, sale(customer, first, product), key());
            assertThat(stopped.statusCode()).isEqualTo(409);
            assertThat(code(stopped)).isEqualTo("PRODUCT_NOT_ON_SALE");
            products.changeStatus(owner, product, "all", Map.of("version", 2, "status", "판매 중"), "payment-fixture");

            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            var suspended = pay(o, sale(customer, first, product), key());
            assertThat(suspended.statusCode()).isEqualTo(409);
            assertThat(code(suspended)).isEqualTo("BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);

            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id IN (?,?)", customer, elsewhere)).isZero();
            assertThat(pay(s, sale(customer, first, product), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void aCashierWithOnlyPaymentsWriteCanSellButNotReadTheLedger() throws Exception {
        long cashier = person("EMPLOYEE");
        member(cashier, first, "payments:write");
        long product = product("계산원", 10000, time(60, null), timeCategory, first);
        long customer = customer(first, "계산원 고객");
        try (var c = login(cashier)) {
            var made = pay(c, sale(customer, first, product), key());
            assertThat(made.statusCode()).isEqualTo(201);
            assertThat(node(made).get("canRefund").asBoolean()).isTrue();
            assertThat(get(c, "/api/payments").statusCode()).isEqualTo(403);
            assertThat(get(c, "/api/payments/" + node(made).get("id").asLong()).statusCode()).isEqualTo(403);
            assertThat(get(c, "/api/passes/" + node(made).get("passId").asLong()).statusCode()).isEqualTo(200);
        }
    }

    // ---- idempotency and atomicity ------------------------------------------------------------------------

    @Test void sameKeyReplaysAndDifferentInputConflicts() throws Exception {
        long product = product("멱등", 11000, time(120, null), timeCategory, first);
        long other = product("멱등 다른", 12000, time(120, null), timeCategory, first);
        long customer = customer(first, "멱등 고객");
        try (var s = login(seller); var o = login(owner)) {
            String key = key();
            var made = pay(s, sale(customer, first, product), key);
            var replay = pay(s, sale(customer, first, product), key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay).get("id").asLong()).isEqualTo(node(made).get("id").asLong());
            assertThat(node(replay).get("passId").asLong()).isEqualTo(node(made).get("passId").asLong());
            var conflict = pay(s, sale(customer, first, other), key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
            // Another employee's identical key is a different request, not a replay.
            assertThat(node(pay(o, sale(customer, first, product), key)).get("id").asLong()).isNotEqualTo(node(made).get("id").asLong());
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=?", customer)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=? AND idempotency_key=?", customer, key)).isZero();
        }
    }

    @Test void concurrentSameKeyRequestsCreateExactlyOnePayment() throws Exception {
        long product = product("동시", 9000, time(60, null), timeCategory, first);
        long customer = customer(first, "동시 고객");
        String key = key();
        var start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++)
                results.add(pool.submit(() -> { start.await(); return payments.create(seller, "all", key, sale(customer, first, product), "race").id(); }));
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=?", customer)).isEqualTo(1);
    }

    @Test void aFailureWhileIssuingLeavesNoPaymentPassOrStoredResult() throws Exception {
        long product = product("발급 실패", 13000, time(60, null), timeCategory, first);
        long customer = customer(first, "롤백 고객");
        Mockito.doThrow(new IllegalStateException("issue failed")).when(passes)
                .issue(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString());
        String key = key();
        try (var s = login(seller)) {
            assertThat(pay(s, sale(customer, first, product), key).statusCode()).isEqualTo(500);
        }
        assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=?", customer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key=?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='payments' AND actor_employee_id=?", seller)).isZero();
        Mockito.reset(passes);
        // After the fault is gone the same key starts a fresh, successful purchase.
        try (var s = login(seller)) {
            assertThat(pay(s, sale(customer, first, product), key).statusCode()).isEqualTo(201);
        }
    }

    @Test void databaseRejectsAPassThatMixesCustomersOrBranches() throws Exception {
        long product = product("제약", 7000, time(60, null), timeCategory, first, second);
        long customer = customer(first, "제약 고객");
        long stranger = customer(first, "다른 고객");
        long paymentId;
        try (var s = login(seller)) {
            paymentId = node(pay(s, sale(customer, first, product), key())).get("id").asLong();
        }
        long revision = count("SELECT MAX(id) FROM product_revisions WHERE original_product_id=?", product);
        String insert = "INSERT INTO passes(customer_id,product_id,purchase_payment_id,purchase_branch_id,purchase_product_revision_id,kind,"
                + "snapshot_price_krw,snapshot_product_name,snapshot_category_code,snapshot_category_name,snapshot_minutes,remaining_minutes,"
                + "status,started_at) VALUES(?,?,?,?,?,'TIME',7000,'x','c','n',60,60,'ACTIVE',CURRENT_TIMESTAMP(6))";
        // uk_passes_1 already holds this payment's pass, and fk_passes_3 rejects other customer/branch combinations.
        assertThatThrownBy(() -> jdbc.update(insert, customer, product, paymentId, first, revision)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM passes WHERE purchase_payment_id=?", paymentId);
        assertThatThrownBy(() -> jdbc.update(insert, stranger, product, paymentId, first, revision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, customer, product, paymentId, second, revision)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- purchase-time terms --------------------------------------------------------------------------------

    @Test void productEditsNeverChangeExistingPaymentsOrPasses() throws Exception {
        long product = product("변경 전", 15000, time(600, 30L), timeCategory, first);
        long customer = customer(first, "스냅샷 고객");
        try (var s = login(seller)) {
            var payment = node(pay(s, sale(customer, first, product), key()));
            long passId = payment.get("passId").asLong();
            products.update(owner, product, "all", Map.of("version", 1, "name", token + " 변경 후", "priceKrw", 20000,
                    "terms", time(900, 60L)), "payment-fixture");
            jdbc.update("UPDATE product_categories SET name='바뀐 분류' WHERE id=?", Long.parseLong(timeCategory));

            var after = node(get(s, "/api/payments/" + payment.get("id").asLong()));
            assertThat(after.get("amountKrw").asLong()).isEqualTo(15000);
            assertThat(after.get("productName").asString()).isEqualTo(token + " 변경 전");
            var pass = node(get(s, "/api/passes/" + passId));
            assertThat(pass.get("productName").asString()).isEqualTo(token + " 변경 전");
            assertThat(pass.get("categoryName").asString()).isEqualTo("분류 시간권");
            assertThat(pass.at("/terms/includedMinutes").asLong()).isEqualTo(600);
            assertThat(pass.at("/terms/validDays").asLong()).isEqualTo(30);
            assertThat(pass.get("expiresAt").asString()).isEqualTo(payment.get("approvedAt").isNull() ? null
                    : Instant.parse(payment.get("approvedAt").asString()).plus(Duration.ofHours(24 * 30)).toString());
            // The next sale charges the new price and references the new revision.
            var next = node(pay(s, sale(customer, first, product), key()));
            assertThat(next.get("amountKrw").asLong()).isEqualTo(20000);
            assertThat(node(get(s, "/api/passes/" + next.get("passId").asLong())).at("/terms/includedMinutes").asLong()).isEqualTo(900);
        }
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    @Test void effectiveStatusFollowsTheRequestInstantAndUsageHistory() throws Exception {
        long product = product("만료 판정", 10000, time(120, 30L), timeCategory, first);
        long customer = customer(first, "만료 고객");
        try (var s = login(seller)) {
            long expired = node(pay(s, sale(customer, first, product), key())).get("passId").asLong();
            long used = node(pay(s, sale(customer, first, product), key())).get("passId").asLong();
            long fresh = node(pay(s, sale(customer, first, product), key())).get("passId").asLong();
            // Stored status stays ACTIVE; only the expiry instant has passed.
            jdbc.update("UPDATE passes SET started_at=?, expires_at=? WHERE id=?",
                    Timestamp.from(Instant.now().minus(Duration.ofDays(31))), Timestamp.from(Instant.now().minusSeconds(1)), expired);
            jdbc.update("INSERT INTO pass_balance_events(pass_id,kind,delta_minutes,event_key) VALUES(?,'USE',-30,?)", used, UUID.randomUUID().toString());

            var gone = node(get(s, "/api/passes/" + expired));
            assertThat(gone.get("status").asString()).isEqualTo("만료");
            assertThat(gone.get("canRefund").asBoolean()).isFalse();
            var spent = node(get(s, "/api/passes/" + used));
            assertThat(spent.get("used").asBoolean()).isTrue();
            assertThat(spent.get("status").asString()).isEqualTo("활성");
            assertThat(spent.get("canRefund").asBoolean()).isFalse();
            assertThat(node(get(s, "/api/passes/" + fresh)).get("canRefund").asBoolean()).isTrue();

            assertThat(ids(node(get(s, "/api/passes?customerId=" + customer + "&status=" + enc("만료"))))).containsExactly(expired);
            assertThat(ids(node(get(s, "/api/passes?customerId=" + customer + "&status=" + enc("활성"))))).containsExactlyInAnyOrder(used, fresh);
            assertThat(get(s, "/api/passes?status=NOT_STARTED").statusCode()).isEqualTo(422);

            // The payment list mirrors the same judgement in canRefund.
            var listed = node(get(s, "/api/payments?customerId=" + customer + "&sort=createdAt&direction=asc")).get("items");
            assertThat(listed).hasSize(3);
            assertThat(listed.get(0).get("canRefund").asBoolean()).isFalse();
            assertThat(listed.get(1).get("canRefund").asBoolean()).isFalse();
            assertThat(listed.get(2).get("canRefund").asBoolean()).isTrue();
        }
    }

    @Test void readsAreLimitedToTheBranchAndPaymentsNeedPaymentsRead() throws Exception {
        long product = product("조회", 10000, time(60, null), timeCategory, first, second);
        long customer = customer(first, "조회 고객");
        long paymentId, passId, secondPass;
        try (var s = login(seller); var two = login(secondSeller)) {
            var made = node(pay(s, sale(customer, first, product), key()));
            paymentId = made.get("id").asLong();
            passId = made.get("passId").asLong();
            // The second branch's seller can see the customer only once it has paid there.
            assertThat(pay(two, sale(customer, second, product), key()).statusCode()).isEqualTo(404);
            customer(second, "둘째 지점 고객");
            secondPass = node(pay(two, sale(customerIds.getLast(), second, product), key())).get("passId").asLong();
            assertThat(get(two, "/api/passes/" + passId).statusCode()).isEqualTo(404);
            assertThat(get(two, "/api/payments/" + paymentId).statusCode()).isEqualTo(404);
        }
        try (var r = login(reader); var v = login(viewer); var o = login(owner)) {
            assertThat(get(r, "/api/payments/" + paymentId).statusCode()).isEqualTo(200);
            assertThat(node(get(r, "/api/payments/" + paymentId)).get("canRefund").asBoolean()).isFalse();
            // Any valid membership reads passes; payments need payments:read.
            assertThat(get(v, "/api/passes/" + passId).statusCode()).isEqualTo(200);
            assertThat(node(get(v, "/api/passes/" + passId)).get("canRefund").asBoolean()).isFalse();
            assertThat(get(v, "/api/passes/" + secondPass).statusCode()).isEqualTo(404);
            // Without payments:read anywhere the payment API is closed; with it elsewhere, this payment is just not found.
            assertThat(get(v, "/api/payments/" + paymentId).statusCode()).isEqualTo(403);
            assertThat(get(v, "/api/payments").statusCode()).isEqualTo(403);
            assertThat(get(v, "/api/payments?scope=" + first).statusCode()).isEqualTo(403);

            assertThat(ids(node(get(o, "/api/payments?customerId=" + customer)))).containsExactly(paymentId);
            String createdAt = node(get(o, "/api/payments/" + paymentId)).get("createdAt").asString();
            Instant created = Instant.parse(createdAt);
            assertThat(ids(node(get(o, "/api/payments?customerId=" + customer + "&from=" + created + "&to=" + created.plusSeconds(1))))).containsExactly(paymentId);
            assertThat(ids(node(get(o, "/api/payments?customerId=" + customer + "&to=" + created)))).isEmpty();
            assertThat(get(o, "/api/payments?from=yesterday").statusCode()).isEqualTo(422);
            assertThat(get(o, "/api/payments?from=" + created + "&to=" + created).statusCode()).isEqualTo(422);
            assertThat(get(o, "/api/payments?sort=method").statusCode()).isEqualTo(422);
            assertThat(get(o, "/api/payments?status=PENDING").statusCode()).isEqualTo(422);
            assertThat(http(o.client(), "DELETE", "/api/payments/" + paymentId, o.token(), null, null).statusCode()).isEqualTo(405);
        }
    }

    // ---- refund -------------------------------------------------------------------------------------------

    @Test void refundRecordsAFullRefundEventWithoutRewritingThePayment() throws Exception {
        long product = product("환불", 25000, period(30), periodCategory, first);
        long customer = customer(first, "환불 고객");
        try (var s = login(seller)) {
            var made = node(pay(s, sale(customer, first, product), key()));
            long paymentId = made.get("id").asLong();
            long passId = made.get("passId").asLong();
            Instant approvedAt = at("SELECT approved_at FROM payments WHERE id=?", paymentId);
            Instant startedAt = at("SELECT started_at FROM passes WHERE id=?", passId);
            Instant expiresAt = at("SELECT expires_at FROM passes WHERE id=?", passId);

            // The amount is never an input, and the reason and key are required.
            var withAmount = refund(s, paymentId, Map.of("reason", "고객 요청", "amountKrw", 1000), key());
            assertThat(withAmount.statusCode()).isEqualTo(422);
            assertThat(refund(s, paymentId, reason(""), key()).statusCode()).isEqualTo(422);
            assertThat(refund(s, paymentId, reason("x".repeat(501)), key()).statusCode()).isEqualTo(422);
            assertThat(refund(s, paymentId, reason("고객 요청"), null).statusCode()).isEqualTo(422);
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", paymentId)).isZero();

            var done = refund(s, paymentId, reason("고객 요청"), key());
            assertThat(done.statusCode()).isEqualTo(200);
            var body = node(done);
            assertThat(body.get("status").asString()).isEqualTo("환불");
            assertThat(body.get("amountKrw").asLong()).isEqualTo(25000);
            assertThat(body.get("approvedAt").asString()).isEqualTo(made.get("approvedAt").asString());
            assertThat(body.get("canRefund").asBoolean()).isFalse();
            assertThat(body.at("/refund/amountKrw").asLong()).isEqualTo(25000);
            assertThat(body.at("/refund/reason").asString()).isEqualTo("고객 요청");
            assertThat(body.at("/refund/processingBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(body.at("/refund/processedBy/id").asString()).isEqualTo(Long.toString(seller));
            assertThat(body.at("/refund/processedBy/name").asString()).isEqualTo("결제 직원");
            assertThat(body.at("/refund/refundedAt").isNull()).isFalse();

            // The original payment keeps its amount and approval time; the pass keeps its period.
            assertThat(count("SELECT amount_krw FROM payments WHERE id=?", paymentId)).isEqualTo(25000);
            assertThat(at("SELECT approved_at FROM payments WHERE id=?", paymentId)).isEqualTo(approvedAt);
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("SELECT status FROM passes WHERE id=?", String.class, passId)).isEqualTo("REFUNDED");
            assertThat(at("SELECT started_at FROM passes WHERE id=?", passId)).isEqualTo(startedAt);
            assertThat(at("SELECT expires_at FROM passes WHERE id=?", passId)).isEqualTo(expiresAt);
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=? AND amount_krw=25000 AND processing_branch_id=? "
                    + "AND processed_by=? AND reason='고객 요청'", paymentId, first, seller)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='payments' AND entity_id=? AND action='REFUNDED'", paymentId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='passes' AND entity_id=? AND action='REFUNDED'", passId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='payments' AND entity_id=?", paymentId)).isEqualTo(2);

            var pass = node(get(s, "/api/passes/" + passId));
            assertThat(pass.get("status").asString()).isEqualTo("환불");
            assertThat(pass.get("canRefund").asBoolean()).isFalse();
            assertThat(ids(node(get(s, "/api/passes?customerId=" + customer + "&status=" + enc("환불"))))).containsExactly(passId);
            assertThat(ids(node(get(s, "/api/payments?customerId=" + customer + "&status=" + enc("환불"))))).containsExactly(paymentId);
            assertThat(node(get(s, "/api/payments/" + paymentId)).at("/refund/reason").asString()).isEqualTo("고객 요청");
        }
    }

    @Test void refundIsRefusedForFailedRefundedUsedAndExpiredPayments() throws Exception {
        long product = product("환불 거절", 10000, time(120, 30L), timeCategory, first);
        long customer = customer(first, "거절 고객");
        try (var s = login(seller)) {
            var failedSale = sale(customer, first, product);
            failedSale.put("mockOutcome", "FAILED");
            long failed = node(pay(s, failedSale, key())).get("id").asLong();
            var used = node(pay(s, sale(customer, first, product), key()));
            var expired = node(pay(s, sale(customer, first, product), key()));
            var almost = node(pay(s, sale(customer, first, product), key()));
            jdbc.update("INSERT INTO pass_balance_events(pass_id,kind,delta_minutes,event_key) VALUES(?,'USE',-30,?)",
                    used.get("passId").asLong(), UUID.randomUUID().toString());
            // Stored status stays ACTIVE on both; only the expiry instant differs (just past, and still ahead).
            jdbc.update("UPDATE passes SET started_at=?, expires_at=? WHERE id=?", Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                    Timestamp.from(Instant.now().minusMillis(1)), expired.get("passId").asLong());
            jdbc.update("UPDATE passes SET started_at=?, expires_at=? WHERE id=?", Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                    Timestamp.from(Instant.now().plusSeconds(60)), almost.get("passId").asLong());

            var notApproved = refund(s, failed, reason("실패 결제"), key());
            assertThat(notApproved.statusCode()).isEqualTo(409);
            assertThat(code(notApproved)).isEqualTo("PAYMENT_NOT_REFUNDABLE");
            var spent = refund(s, used.get("id").asLong(), reason("사용함"), key());
            assertThat(spent.statusCode()).isEqualTo(409);
            assertThat(code(spent)).isEqualTo("PASS_ALREADY_USED");
            var late = refund(s, expired.get("id").asLong(), reason("만료됨"), key());
            assertThat(late.statusCode()).isEqualTo(409);
            assertThat(code(late)).isEqualTo("PASS_EXPIRED");
            assertThat(count("SELECT COUNT(*) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE p.customer_id=?", customer)).isZero();
            assertThat(count("SELECT COUNT(*) FROM passes WHERE customer_id=? AND status='REFUNDED'", customer)).isZero();

            assertThat(refund(s, almost.get("id").asLong(), reason("만료 직전"), key()).statusCode()).isEqualTo(200);
            var again = refund(s, almost.get("id").asLong(), reason("만료 직전"), key());
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(code(again)).isEqualTo("ALREADY_REFUNDED");
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", almost.get("id").asLong())).isEqualTo(1);
        }
    }

    @Test void onlyPaymentsWriteAtThePaymentBranchMayRefund() throws Exception {
        long product = product("환불 권한", 10000, time(60, null), timeCategory, first, second);
        long customer = customer(first, "권한 고객");
        long cashier = person("EMPLOYEE");
        member(cashier, first, "payments:write");
        long elsewhere = person("EMPLOYEE");
        member(elsewhere, first, "payments:read");
        member(elsewhere, second, "payments:write");
        List<Long> sold = new ArrayList<>();
        try (var s = login(seller)) {
            for (int i = 0; i < 3; i++) sold.add(node(pay(s, sale(customer, first, product), key())).get("id").asLong());
        }
        long paymentId = sold.getFirst();
        try (var r = login(reader); var v = login(viewer); var e = login(elsewhere); var two = login(secondSeller)) {
            // payments:read alone, no grant at all, or payments:write only at another branch: 403.
            assertThat(code(refund(r, paymentId, reason("권한 없음"), key()))).isEqualTo("FORBIDDEN");
            assertThat(refund(v, paymentId, reason("권한 없음"), key()).statusCode()).isEqualTo(403);
            assertThat(refund(e, paymentId, reason("다른 지점 권한"), key()).statusCode()).isEqualTo(403);
            // Not a member of the payment branch at all: the payment is not visible.
            assertThat(refund(two, paymentId, reason("보이지 않음"), key()).statusCode()).isEqualTo(404);
            assertThat(refund(two, Long.MAX_VALUE, reason("없음"), key()).statusCode()).isEqualTo(404);
        }
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", paymentId)).isZero();
        try (var c = login(cashier); var o = login(owner)) {
            // A cashier refunds without reading the ledger; the owner satisfies every grant.
            var byCashier = refund(c, paymentId, reason("계산원 환불"), key());
            assertThat(byCashier.statusCode()).isEqualTo(200);
            assertThat(node(byCashier).at("/refund/processingBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(refund(o, sold.get(1), reason("대표 환불"), key()).statusCode()).isEqualTo(200);
        }
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id IN (?,?) AND processing_branch_id=?", sold.get(0), sold.get(1), first)).isEqualTo(2);
    }

    @Test void sameRefundKeyReplaysAndConcurrentKeysRefundOnce() throws Exception {
        long product = product("환불 멱등", 10000, time(60, null), timeCategory, first);
        long customer = customer(first, "환불 멱등 고객");
        long once, race;
        try (var s = login(seller)) {
            once = node(pay(s, sale(customer, first, product), key())).get("id").asLong();
            race = node(pay(s, sale(customer, first, product), key())).get("id").asLong();
            String key = key();
            var original = refund(s, once, reason("멱등 환불"), key);
            var replay = refund(s, once, reason("멱등 환불"), key);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(node(replay).at("/refund/id").asString()).isEqualTo(node(original).at("/refund/id").asString());
            var conflict = refund(s, once, reason("다른 사유"), key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", once)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=? AND idempotency_key=?", once, key)).isZero();
        }
        var start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                String key = key();
                results.add(pool.submit(() -> {
                    start.await();
                    try { payments.refund(seller, race, "all", key, reason("동시 환불"), "race"); return "OK"; }
                    catch (com.openstudy.common.exception.ApiException refused) { return refused.getMessage(); }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals)).hasSize(1);
        }
        assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", race)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='payments' AND entity_id=? AND action='REFUNDED'", race)).isEqualTo(1);
    }

    @Test void aFailureDuringRefundLeavesThePaymentAndPassUntouched() throws Exception {
        long product = product("환불 롤백", 10000, time(60, null), timeCategory, first);
        long customer = customer(first, "환불 롤백 고객");
        try (var s = login(seller)) {
            var made = node(pay(s, sale(customer, first, product), key()));
            long paymentId = made.get("id").asLong();
            Mockito.doThrow(new IllegalStateException("refund failed")).when(passes).refund(anyLong(), anyLong(), any(), anyString());
            String key = key();
            assertThat(refund(s, paymentId, reason("롤백"), key).statusCode()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT status FROM passes WHERE id=?", String.class, made.get("passId").asLong())).isEqualTo("ACTIVE");
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id=?", paymentId)).isZero();
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key=?", key)).isZero();
            Mockito.reset(passes);
            assertThat(refund(s, paymentId, reason("롤백"), key).statusCode()).isEqualTo(200);
        }
    }

    List<String> names(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.get("items").forEach(item -> result.add(item.get("id").asString()));
        return result;
    }

    List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.get("items").forEach(item -> result.add(item.get("id").asLong()));
        return result;
    }
}
