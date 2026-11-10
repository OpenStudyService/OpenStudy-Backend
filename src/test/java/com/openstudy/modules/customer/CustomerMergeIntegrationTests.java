package com.openstudy.modules.customer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * #114 over HTTP and MySQL (operations-extension-api.md "중복 고객 확인·병합", M13-D03·D07·D13): only the OWNER sees
 * duplicates and merges; both customers' records can be compared before; the absorbed customer stays as a merged record
 * and no payment, pass or stay changes its customer; the target's reads include the absorbed records while the sales
 * totals stay the same; the history keeps who, when and why; self, repeated and simultaneous merges are refused; and the
 * absorbed customer neither buys nor checks in, while the target may use its passes (2026-11-10 user decision).
 */
@MySqlIntegrationTest
class CustomerMergeIntegrationTests {
    static final String PASSWORD = "merge-test-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @LocalServerPort int port;

    long owner, manager, firstSeller, secondSeller, first, second, product, seat;
    final String phone = "010" + (10_000_000 + (int) (Math.random() * 89_999_999));
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "첫 지점 매니저");
        firstSeller = person("EMPLOYEE", "첫 지점 판매");
        secondSeller = person("EMPLOYEE", "둘째 지점 판매");
        first = branch("병합 첫 지점");
        second = branch("병합 둘째 지점");
        member(manager, first, "MANAGER", "payments:write", "payments:read", "sales:read", "members:write");
        member(firstSeller, first, "STAFF", "payments:write", "payments:read");
        member(secondSeller, second, "STAFF", "payments:write", "payments:read");
        var category = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "병합 분류", "kind", "시간권"), "merge-fixture");
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600); terms.put("validDays", null);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "병합 시간권"); body.put("priceKrw", 10000L); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", json.readTree(json.writeValueAsString(category)).get("id").asString());
        body.put("branchIds", List.of(Long.toString(first), Long.toString(second)));
        product = Long.parseLong(products.create(owner, "all", key(), body, "merge-fixture").id());
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'A','M-1','GENERAL','AVAILABLE')", first);
        seat = jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=?", Long.class, first);
    }

    @AfterEach void clean() {
        String ids = customerIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM seat_sessions WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id IN (" + ids + "))");
            jdbc.update("DELETE FROM passes WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id IN (" + ids + "))");
            jdbc.update("DELETE FROM payments WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM customer_service_interactions WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM customer_merges WHERE target_customer_id IN (" + ids + ") OR absorbed_customer_id IN (" + ids + ")");
            jdbc.update("UPDATE customers SET status='NORMAL', merged_into_customer_id=NULL WHERE id IN (" + ids + ")");
            jdbc.update("DELETE FROM customer_status_events WHERE customer_id IN (" + ids + ")");
            jdbc.update("DELETE FROM customers WHERE id IN (" + ids + ")");
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

    /** AC 1 and 2: only the OWNER sees duplicates and merges; both customers' records are there to compare first. */
    @Test void onlyTheOwnerSeesDuplicatesAndMergesAfterComparingBothCustomers() throws Exception {
        long kept = customer(first, "김병합"), absorbed = customer(second, "김 병합");
        try (var boss = login(owner); var lead = login(manager); var east = login(firstSeller); var west = login(secondSeller)) {
            created(pay(east, kept, first));
            created(pay(west, absorbed, second));
            refused(get(lead, "/api/customer-duplicates"), 403, "FORBIDDEN");
            refused(merge(lead, kept, absorbed), 403, "FORBIDDEN");

            var group = groupOf(ok(get(boss, "/api/customer-duplicates?pageSize=100")));
            assertThat(ids(group.get("customers"))).containsExactly(Long.toString(kept), Long.toString(absorbed));
            assertThat(group.at("/customers/0/paymentCount").asLong()).isEqualTo(1);
            assertThat(group.at("/customers/0/lastActivityAt").isNull()).isFalse();
            same("CustomerDuplicateGroup", group);
            same("DuplicateCustomer", group.at("/customers/0"));
            // Each customer's purchases and payments, before the merge.
            assertThat(ok(get(boss, "/api/payments?customerId=" + kept)).get("items")).hasSize(1);
            assertThat(ok(get(boss, "/api/payments?customerId=" + absorbed)).get("items")).hasSize(1);
            assertThat(ok(get(boss, "/api/customers/" + absorbed + "/pass-history")).get("events")).isNotEmpty();
        }
    }

    /** AC 3, 4 and 5: references stay, the target reads both customers, totals stay, and the merge is recorded. */
    @Test void aMergeKeepsReferencesShowsBothAndRecordsWhoWhenAndWhy() throws Exception {
        long kept = customer(first, "이대상"), absorbed = customer(second, "이흡수");
        try (var boss = login(owner); var east = login(firstSeller); var west = login(secondSeller); var lead = login(manager)) {
            created(pay(east, kept, first));
            String absorbedPayment = created(pay(west, absorbed, second)).get("id").asString();
            String absorbedPass = jdbc.queryForObject("SELECT id FROM passes WHERE customer_id=?", String.class, absorbed);
            var revenueBefore = ok(get(boss, "/api/sales/revenue?branchIds=" + first + "&branchIds=" + second)).get("totals");
            // Before the merge the first branch does not see the customer registered and paying at the second.
            refused(get(east, "/api/customers/" + absorbed), 404, "NOT_FOUND");

            var merged = ok(merge(boss, kept, absorbed));
            assertThat(merged.get("absorbedCustomerCount").asLong()).isEqualTo(1);
            assertThat(merged.get("version").asLong()).isEqualTo(2);
            same("Customer", merged);
            var gone = ok(get(boss, "/api/customers/" + absorbed));
            assertThat(gone.get("status").asString()).isEqualTo("병합됨");
            assertThat(gone.at("/mergedInto/id").asString()).isEqualTo(Long.toString(kept));
            // No reference moved.
            assertThat(jdbc.queryForObject("SELECT customer_id FROM payments WHERE id=?", Long.class, absorbedPayment)).isEqualTo(absorbed);
            assertThat(jdbc.queryForObject("SELECT customer_id FROM passes WHERE id=?", Long.class, absorbedPass)).isEqualTo(absorbed);
            // The target's reads include the absorbed records, which keep their own customer.
            var payments = ok(get(boss, "/api/payments?customerId=" + kept)).get("items");
            assertThat(payments).hasSize(2);
            assertThat(texts(payments, "/customer/id")).containsExactlyInAnyOrder(Long.toString(kept), Long.toString(absorbed));
            assertThat(ok(get(boss, "/api/passes?customerId=" + kept)).get("items")).hasSize(2);
            assertThat(ok(get(boss, "/api/customers/" + kept + "/pass-history")).get("events").toString()).contains(absorbedPass);
            // The second branch now sees the target, through the absorbed customer's registration and payment.
            assertThat(ok(get(west, "/api/customers/" + kept)).get("id").asString()).isEqualTo(Long.toString(kept));
            // The sales totals are the same: no transaction moved.
            assertThat(ok(get(boss, "/api/sales/revenue?branchIds=" + first + "&branchIds=" + second)).get("totals")).isEqualTo(revenueBefore);
            // Lists hide the merged customer unless asked for it.
            assertThat(ids(ok(get(boss, "/api/customers?q=" + phone + "&pageSize=100")).get("items"))).containsExactly(Long.toString(kept));
            assertThat(ids(ok(get(boss, "/api/customers?q=" + phone + "&status=" + enc("병합됨"))).get("items")))
                    .containsExactly(Long.toString(absorbed));
            // The pair is no longer a duplicate.
            assertThat(ok(get(boss, "/api/customer-duplicates?pageSize=100")).get("items").toString()).doesNotContain(phone);

            // The history: target, absorbed, who, when, why; readable by whoever sees the customer.
            var history = ok(get(lead, "/api/customers/" + kept + "/merges")).get("items");
            assertThat(history).hasSize(1);
            assertThat(history.at("/0/target/id").asString()).isEqualTo(Long.toString(kept));
            assertThat(history.at("/0/absorbed/id").asString()).isEqualTo(Long.toString(absorbed));
            assertThat(history.at("/0/mergedBy/name").asString()).isEqualTo("사장");
            assertThat(history.at("/0/reason").asString()).isEqualTo("같은 고객");
            assertThat(history.at("/0/mergedAt").isNull()).isFalse();
            assertThat(history.at("/0/via").isNull()).isTrue();
            same("CustomerMergeRecord", history.get(0));
            assertThat(ok(get(boss, "/api/customers/" + absorbed + "/merges")).get("items")).hasSize(1);
            assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='MERGED'",
                    String.class, kept)).hasSize(1);
        }
    }

    /** AC 6: self, repeated and simultaneous merges are refused; a chain moves earlier absorbed customers. */
    @Test void selfRepeatedAndSimultaneousMergesAreRefused() throws Exception {
        long a = customer(first, "가"), b = customer(first, "나"), c = customer(first, "다"), d = customer(first, "라");
        try (var boss = login(owner)) {
            refused(http(boss, "POST", "/api/customers/" + a + "/merge", body(1, a, 1)), 422, "CUSTOMER_MERGE_SELF");
            refused(http(boss, "POST", "/api/customers/" + a + "/merge", body(1, 999_999_999L, 1)), 404, "NOT_FOUND");
            refused(http(boss, "POST", "/api/customers/" + a + "/merge", body(2, b, 1)), 409, "VERSION_CONFLICT");
            refused(http(boss, "POST", "/api/customers/" + a + "/merge", Map.of("version", 1, "absorbedCustomerId", Long.toString(b),
                    "absorbedVersion", 1)), 422, "VALIDATION_FAILED");

            // Two merges of the same customer at once: one wins.
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            calls.add(() -> merge(boss, a, c));
            calls.add(() -> merge(boss, b, c));
            var responses = race(calls);
            assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(200, 409);
            var loser = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertThat(node(loser).get("code").asString()).isIn("CUSTOMER_ALREADY_MERGED", "VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_merges WHERE absorbed_customer_id=?", Long.class, c)).isEqualTo(1);
            long winner = jdbc.queryForObject("SELECT merged_into_customer_id FROM customers WHERE id=?", Long.class, c);
            long other = winner == a ? b : a;

            // Merging the merged customer again, or into it, is refused with its target.
            var again = http(boss, "POST", "/api/customers/" + other + "/merge", body(1, c, 1));
            refused(again, 409, "CUSTOMER_ALREADY_MERGED");
            assertThat(node(again).get("mergedIntoCustomerId").asString()).isEqualTo(Long.toString(winner));
            refused(http(boss, "POST", "/api/customers/" + c + "/merge", body(2, other, 1)), 409, "CUSTOMER_ALREADY_MERGED");
            // A merged customer is a record: no edit, no status change.
            refused(http(boss, "PATCH", "/api/customers/" + c, Map.of("version", 2, "name", "바뀜")), 409, "CUSTOMER_ALREADY_MERGED");
            refused(http(boss, "POST", "/api/customers/" + other + "/status", Map.of("version", 1, "status", "병합됨", "reason", "직접")),
                    422, "VALIDATION_FAILED");

            // A chain: the winner merged into d moves c to d, through the winner.
            ok(http(boss, "POST", "/api/customers/" + d + "/merge", body(1, winner, 2)));
            assertThat(jdbc.queryForObject("SELECT merged_into_customer_id FROM customers WHERE id=?", Long.class, c)).isEqualTo(d);
            assertThat(ok(get(boss, "/api/customers/" + d)).get("absorbedCustomerCount").asLong()).isEqualTo(2);
            var moved = ok(get(boss, "/api/customers/" + c + "/merges")).get("items");
            assertThat(moved.at("/0/target/id").asString()).isEqualTo(Long.toString(d));
            assertThat(moved.at("/0/via/id").asString()).isEqualTo(Long.toString(winner));
        }
    }

    /** AC 7: the absorbed customer neither buys nor checks in and names its target; the target may use its passes. */
    @Test void theAbsorbedCustomerNeitherBuysNorChecksInButItsPassServesTheTarget() throws Exception {
        long kept = customer(first, "박대상"), absorbed = customer(first, "박흡수");
        try (var boss = login(owner); var east = login(firstSeller)) {
            created(pay(east, absorbed, first));
            String pass = jdbc.queryForObject("SELECT id FROM passes WHERE customer_id=?", String.class, absorbed);
            ok(merge(boss, kept, absorbed));

            var sale = pay(east, absorbed, first);
            refused(sale, 409, "CUSTOMER_NOT_ACTIVE");
            assertThat(node(sale).get("mergedIntoCustomerId").asString()).isEqualTo(Long.toString(kept));
            var checkIn = checkIn(east, absorbed, pass);
            refused(checkIn, 409, "CUSTOMER_NOT_ACTIVE");
            assertThat(node(checkIn).get("mergedIntoCustomerId").asString()).isEqualTo(Long.toString(kept));

            // The exact-match lookup finds the target once, with the absorbed customer's pass as a candidate.
            var lookup = ok(http(east, "POST", "/api/customer-lookups/check-in", Map.of("branchId", Long.toString(first), "phone", phone)));
            assertThat(lookup.toString()).doesNotContain("\"id\":\"" + absorbed + "\"");
            assertThat(lookup.toString()).contains(pass);
            // The target checks in with that pass; the pass keeps its customer and the stay names the target.
            var stay = created(checkIn(east, kept, pass));
            assertThat(jdbc.queryForObject("SELECT customer_id FROM seat_sessions WHERE id=?", Long.class, stay.get("id").asString())).isEqualTo(kept);
            assertThat(jdbc.queryForObject("SELECT customer_id FROM passes WHERE id=?", Long.class, pass)).isEqualTo(absorbed);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@merge.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"),
                "merge-fixture");
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

    /** Every customer of a test shares the phone, so each test makes one duplicate group. */
    long customer(long branch, String name) {
        var body = new LinkedHashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name); body.put("phone", phone);
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "merge-fixture").id());
        customerIds.add(id);
        return id;
    }

    static Map<String, Object> body(long version, long absorbed, long absorbedVersion) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version); body.put("absorbedCustomerId", Long.toString(absorbed)); body.put("absorbedVersion", absorbedVersion);
        body.put("reason", "같은 고객");
        return body;
    }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }

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

    HttpResponse<String> http(Session session, String method, String path, Object body) throws Exception {
        return send(session.client(), method, path, session.token(), body, null);
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return send(session.client(), "GET", path, null, null, null); }

    HttpResponse<String> merge(Session session, long target, long absorbed) throws Exception {
        long version = node(get(session, "/api/customers/" + target)).get("version").asLong();
        long absorbedVersion = jdbc.queryForObject("SELECT version FROM customers WHERE id=?", Long.class, absorbed) + 1;
        return http(session, "POST", "/api/customers/" + target + "/merge", body(version, absorbed, absorbedVersion));
    }

    HttpResponse<String> pay(Session session, long customer, long branch) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(branch));
        body.put("productId", Long.toString(product)); body.put("method", "카드");
        return send(session.client(), "POST", "/api/payments", session.token(), body, key());
    }

    HttpResponse<String> checkIn(Session session, long customer, String pass) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("seatId", Long.toString(seat)); body.put("customerId", Long.toString(customer)); body.put("passId", pass);
        return send(session.client(), "POST", "/api/seat-sessions", session.token(), body, key());
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

    JsonNode groupOf(JsonNode page) {
        for (var group : page.get("items")) if (group.get("phone").asString().equals(phone)) return group;
        throw new AssertionError("no duplicate group for " + phone + ": " + page);
    }

    static List<String> ids(JsonNode items) {
        var ids = new ArrayList<String>();
        for (var item : items) ids.add(item.get("id").asString());
        return ids;
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
