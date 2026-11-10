package com.openstudy.modules.ticket;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #113 over HTTP and MySQL (operations-extension-api.md "상품 판매 기간·한도", M13-D02·D06): a product without a period or a
 * limit sells as before; a sale is judged stopped → branch → period → limit with a code for each; the last unit sold twice
 * at once sells once; a refund gives the unit back and a failed payment never takes one; a limit below the sold quantity
 * only stops new sales; and every change of the period or limit is a product revision.
 */
@MySqlIntegrationTest
@Import(ProductSaleLimitIntegrationTests.TimeConfiguration.class)
class ProductSaleLimitIntegrationTests {
    static final String PASSWORD = "sale-limit-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductCategoryService categories;
    @Autowired MutableClock clock;
    @LocalServerPort int port;

    long owner, seller, first, second;
    String category;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();

    /** null follows the real clock; a test that judges a period boundary pins it. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock saleClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.fixed = null;
        owner = person("OWNER", "사장");
        seller = person("EMPLOYEE", "판매 직원");
        first = branch("한도 첫 지점");
        second = branch("한도 둘째 지점");
        member(seller, first, "payments:write", "payments:read");
        member(seller, second, "payments:write", "payments:read");
        var made = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "한도 분류", "kind", "시간권"), "limit-fixture");
        category = json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    @AfterEach void clean() {
        clock.fixed = null;
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM passes WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
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

    /** AC 1: without a period or a limit a product sells as before; the response says so. */
    @Test void aProductWithoutPeriodOrLimitSellsAsBefore() throws Exception {
        long customer = customer();
        try (var boss = login(owner); var cash = login(seller)) {
            var product = created(createProduct(boss, Map.of()));
            assertThat(product.get("salesStartsAt").isNull()).isTrue();
            assertThat(product.get("salesEndsAt").isNull()).isTrue();
            assertThat(product.get("salesLimit").isNull()).isTrue();
            assertThat(product.get("remainingQuantity").isNull()).isTrue();
            assertThat(product.get("soldQuantity").asLong()).isZero();
            assertThat(product.get("saleAvailability").asString()).isEqualTo("판매 가능");
            String id = product.get("id").asString();
            for (int sale = 0; sale < 3; sale++) created(pay(cash, sale(customer, first, id)));
            var after = ok(get(boss, "/api/products/" + id));
            assertThat(after.get("soldQuantity").asLong()).isEqualTo(3);
            assertThat(after.get("saleAvailability").asString()).isEqualTo("판매 가능");
            // The response carries exactly the contract's fields.
            same("Product", after);
        }
    }

    /** AC 2: stopped, then period, then limit, each its own code; the period is [start, end). */
    @Test void aSaleIsJudgedStoppedThenPeriodThenLimit() throws Exception {
        long customer = customer();
        // Near the real time: the session's idle limit reads the same clock.
        Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(300), end = start.plusSeconds(600);
        try (var boss = login(owner); var cash = login(seller)) {
            var product = created(createProduct(boss, sales(start.toString(), end.toString(), 1)));
            String id = product.get("id").asString();

            clock.fixed = start.minusSeconds(1);
            assertThat(ok(get(boss, "/api/products/" + id)).get("saleAvailability").asString()).isEqualTo("판매 전");
            var early = pay(cash, sale(customer, first, id));
            refused(early, 409, "PRODUCT_NOT_IN_SALE_PERIOD");
            assertThat(node(early).get("salesStartsAt").asString()).isEqualTo(start.toString());
            assertThat(node(early).get("salesEndsAt").asString()).isEqualTo(end.toString());
            assertThat(fields(node(early))).containsExactlyInAnyOrder("code", "message", "requestId", "salesStartsAt", "salesEndsAt");

            clock.fixed = start;
            created(pay(cash, sale(customer, first, id)));
            // Sold out inside the period: the limit decides.
            var full = pay(cash, sale(customer, first, id));
            refused(full, 409, "PRODUCT_SALE_LIMIT_REACHED");
            assertThat(node(full).get("salesLimit").asInt()).isEqualTo(1);
            assertThat(node(full).get("soldQuantity").asLong()).isEqualTo(1);
            assertThat(ok(get(boss, "/api/products/" + id)).get("saleAvailability").asString()).isEqualTo("한도 소진");

            // At the end, past the period and over the limit: the period comes first.
            clock.fixed = end;
            refused(pay(cash, sale(customer, first, id)), 409, "PRODUCT_NOT_IN_SALE_PERIOD");
            assertThat(ok(get(boss, "/api/products/" + id)).get("saleAvailability").asString()).isEqualTo("판매 종료");
            // Stopped as well: the stop comes first, and the branch check before the period.
            long version = ok(get(boss, "/api/products/" + id)).get("version").asLong();
            ok(http(boss.client(), "POST", "/api/products/" + id + "/status", boss.token(), Map.of("version", version, "status", "판매 중지"), null));
            refused(pay(cash, sale(customer, first, id)), 409, "PRODUCT_NOT_ON_SALE");
            assertThat(ok(get(boss, "/api/products/" + id)).get("saleAvailability").asString()).isEqualTo("판매 중지");
            var elsewhere = created(createProduct(boss, sales(start.toString(), end.toString(), 1)));
            refused(pay(cash, sale(customer, second, elsewhere.get("id").asString())), 409, "PRODUCT_NOT_AVAILABLE_AT_BRANCH");
        }
    }

    /** AC 3: with one unit left, two simultaneous sales at two branches sell it once. */
    @Test void theLastUnitSoldTwiceAtOnceSellsOnce() throws Exception {
        long customer = customer();
        try (var boss = login(owner); var one = login(seller); var two = login(seller)) {
            var product = created(createProduct(boss, Map.of("salesLimit", 3), first, second));
            String id = product.get("id").asString();
            created(pay(one, sale(customer, first, id)));
            created(pay(one, sale(customer, second, id)));
            for (int round = 0; round < 3; round++) {
                if (round > 0) {
                    // Give one unit back for the next round.
                    String paid = jdbc.queryForObject("SELECT MAX(id) FROM payments WHERE product_id=? AND status='APPROVED'", String.class, id);
                    ok(refund(one, paid));
                }
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> pay(one, sale(customer, first, id)));
                calls.add(() -> pay(two, sale(customer, second, id)));
                var responses = race(calls);
                assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).as(round + "").containsExactly(201, 409);
                responses.stream().filter(response -> response.statusCode() == 409)
                        .forEach(response -> refused(response, 409, "PRODUCT_SALE_LIMIT_REACHED"));
                assertThat(count("SELECT COUNT(*) FROM payments WHERE product_id=? AND status='APPROVED'", id)).isEqualTo(3);
            }
        }
    }

    /** AC 4: a refund gives the unit back; a failed payment never takes one. */
    @Test void aRefundGivesTheUnitBackAndAFailedPaymentTakesNone() throws Exception {
        long customer = customer();
        try (var boss = login(owner); var cash = login(seller)) {
            String id = created(createProduct(boss, Map.of("salesLimit", 1))).get("id").asString();
            // A failed Mock payment is recorded but does not use the limit.
            var failed = created(pay(cash, with(sale(customer, first, id), "mockOutcome", "FAILED")));
            assertThat(failed.get("status").asString()).isEqualTo("실패");
            assertThat(ok(get(boss, "/api/products/" + id)).get("remainingQuantity").asLong()).isEqualTo(1);
            var paid = created(pay(cash, sale(customer, first, id)));
            assertThat(ok(get(boss, "/api/products/" + id)).get("remainingQuantity").asLong()).isZero();
            refused(pay(cash, sale(customer, first, id)), 409, "PRODUCT_SALE_LIMIT_REACHED");
            // A sale that would fail is judged too: the sold-out product records no failed payment.
            refused(pay(cash, with(sale(customer, first, id), "mockOutcome", "FAILED")), 409, "PRODUCT_SALE_LIMIT_REACHED");
            assertThat(count("SELECT COUNT(*) FROM payments WHERE product_id=? AND status='FAILED'", id)).isEqualTo(1);

            ok(refund(cash, paid.get("id").asString()));
            var after = ok(get(boss, "/api/products/" + id));
            assertThat(after.get("soldQuantity").asLong()).isZero();
            assertThat(after.get("remainingQuantity").asLong()).isEqualTo(1);
            created(pay(cash, sale(customer, first, id)));
        }
    }

    /** AC 5: a limit below the sold quantity is accepted and only stops new sales. */
    @Test void aLimitBelowTheSoldQuantityOnlyStopsNewSales() throws Exception {
        long customer = customer();
        try (var boss = login(owner); var cash = login(seller)) {
            var product = created(createProduct(boss, Map.of()));
            String id = product.get("id").asString();
            for (int sale = 0; sale < 3; sale++) created(pay(cash, sale(customer, first, id)));
            var lowered = ok(patch(boss, id, Map.of("version", product.get("version").asLong(), "salesLimit", 2)));
            assertThat(lowered.get("salesLimit").asInt()).isEqualTo(2);
            assertThat(lowered.get("soldQuantity").asLong()).isEqualTo(3);
            assertThat(lowered.get("remainingQuantity").asLong()).isZero();
            assertThat(lowered.get("saleAvailability").asString()).isEqualTo("한도 소진");
            refused(pay(cash, sale(customer, first, id)), 409, "PRODUCT_SALE_LIMIT_REACHED");
            assertThat(count("SELECT COUNT(*) FROM payments WHERE product_id=? AND status='APPROVED'", id)).isEqualTo(3);
            // Clearing the limit sells again.
            var cleared = new LinkedHashMap<String, Object>();
            cleared.put("version", lowered.get("version").asLong()); cleared.put("salesLimit", null);
            assertThat(ok(patch(boss, id, cleared)).get("saleAvailability").asString()).isEqualTo("판매 가능");
            created(pay(cash, sale(customer, first, id)));
            // The list filters by availability.
            ok(patch(boss, id, Map.of("version", ok(get(boss, "/api/products/" + id)).get("version").asLong(), "salesLimit", 1)));
            assertThat(ids(ok(get(boss, "/api/products?availability=" + enc("한도 소진") + "&pageSize=100")).get("items"))).contains(id);
            assertThat(ids(ok(get(boss, "/api/products?availability=" + enc("판매 가능") + "&pageSize=100")).get("items"))).doesNotContain(id);
            refused(get(boss, "/api/products?availability=" + enc("없음")), 422, "VALIDATION_FAILED");
        }
    }

    /** AC 6: every change of the period or limit is a product revision; inputs are checked. */
    @Test void changesAreRevisionsAndInputsAreChecked() throws Exception {
        try (var boss = login(owner)) {
            refused(createProduct(boss, sales("2026-12-02T00:00:00Z", "2026-12-01T00:00:00Z", null)), 422, "VALIDATION_FAILED");
            refused(createProduct(boss, sales("2026-12-01T00:00:00.5Z", null, null)), 422, "VALIDATION_FAILED");
            refused(createProduct(boss, Map.of("salesLimit", 0)), 422, "VALIDATION_FAILED");
            refused(createProduct(boss, Map.of("salesLimit", 1_000_001)), 422, "VALIDATION_FAILED");
            refused(createProduct(boss, Map.of("salesLimit", 1.5)), 422, "VALIDATION_FAILED");

            var product = created(createProduct(boss, sales("2026-12-01T09:00:00+09:00", null, 10)));
            assertThat(product.get("salesStartsAt").asString()).isEqualTo("2026-12-01T00:00:00Z");
            String id = product.get("id").asString();
            long version = product.get("version").asLong();
            // The same values are not a change: no new revision.
            assertThat(ok(patch(boss, id, Map.of("version", version, "salesLimit", 10))).get("version").asLong()).isEqualTo(version);
            // An end before the stored start is refused; an end after it is a new revision.
            refused(patch(boss, id, Map.of("version", version, "salesEndsAt", "2026-11-30T00:00:00Z")), 422, "VALIDATION_FAILED");
            var changed = ok(patch(boss, id, Map.of("version", version, "salesEndsAt", "2026-12-31T00:00:00Z", "salesLimit", 20)));
            assertThat(changed.get("version").asLong()).isEqualTo(version + 1);
            var history = ok(get(boss, "/api/products/" + id + "/history?pageSize=100&direction=asc")).get("items");
            assertThat(history).hasSize(2);
            var latest = history.get(1).get("after");
            assertThat(latest.get("schemaVersion").asInt()).isEqualTo(2);
            assertThat(latest.get("salesStartsAt").asString()).isEqualTo("2026-12-01T00:00:00Z");
            assertThat(latest.get("salesEndsAt").asString()).isEqualTo("2026-12-31T00:00:00Z");
            assertThat(latest.get("salesLimit").asInt()).isEqualTo(20);
            assertThat(history.get(1).get("before").get("salesLimit").asInt()).isEqualTo(10);
            assertThat(fields(latest)).isEqualTo(propertyNames("ProductSnapshot"));
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@limit.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"),
                "limit-fixture");
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

    long customer() {
        var body = new LinkedHashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(first)); body.put("name", "한도 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "limit-fixture").id());
        customerIds.add(id);
        return id;
    }

    static Map<String, Object> sales(String startsAt, String endsAt, Integer limit) {
        var body = new LinkedHashMap<String, Object>();
        if (startsAt != null) body.put("salesStartsAt", startsAt);
        if (endsAt != null) body.put("salesEndsAt", endsAt);
        if (limit != null) body.put("salesLimit", limit);
        return body;
    }

    Map<String, Object> productBody(Map<String, Object> extra, long... sellAt) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600); terms.put("validDays", null);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "한도 시간권"); body.put("priceKrw", 10000L); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", category);
        long[] at = sellAt.length == 0 ? new long[]{first} : sellAt;
        body.put("branchIds", java.util.Arrays.stream(at).mapToObj(String::valueOf).toList());
        body.putAll(extra);
        return body;
    }

    static Map<String, Object> sale(long customer, long branch, String product) {
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(branch));
        body.put("productId", product); body.put("method", "카드");
        return body;
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        var copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> createProduct(Session session, Map<String, Object> extra, long... sellAt) throws Exception {
        return http(session.client(), "POST", "/api/products", session.token(), productBody(extra, sellAt), key());
    }

    HttpResponse<String> patch(Session session, String id, Map<String, Object> body) throws Exception {
        return http(session.client(), "PATCH", "/api/products/" + id, session.token(), body, null);
    }

    HttpResponse<String> pay(Session session, Object body) throws Exception {
        return http(session.client(), "POST", "/api/payments", session.token(), body, key());
    }

    HttpResponse<String> refund(Session session, String payment) throws Exception {
        return http(session.client(), "POST", "/api/payments/" + payment + "/refund", session.token(), Map.of("reason", "고객 요청"), key());
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

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    static List<String> ids(JsonNode items) {
        var ids = new ArrayList<String>();
        for (var item : items) ids.add(item.get("id").asString());
        return ids;
    }

    Set<String> propertyNames(String schema) throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        var names = new HashSet<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    void same(String schema, JsonNode actual) throws Exception { assertThat(fields(actual)).as(schema).isEqualTo(propertyNames(schema)); }

    /** Starts every call at the same instant on its own thread and returns all responses. */
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
            for (var future : running) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
