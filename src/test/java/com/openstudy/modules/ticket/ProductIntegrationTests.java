package com.openstudy.modules.ticket;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.ticket.dto.Product;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MySqlIntegrationTest
class ProductIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired TransactionTemplate transactions;
    @LocalServerPort int port;

    long owner, staffFirst, managerFirst, staffBoth, staffNoGrant, first, second;
    String timeCategory, periodCategory, storedCategory;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customers = new ArrayList<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @BeforeEach void setup() {
        owner = person("OWNER");
        staffFirst = person("EMPLOYEE"); managerFirst = person("EMPLOYEE"); staffBoth = person("EMPLOYEE"); staffNoGrant = person("EMPLOYEE");
        first = branch("첫 지점"); second = branch("둘째 지점");
        member(staffFirst, first, "STAFF", true); member(managerFirst, first, "MANAGER", true);
        member(staffBoth, first, "STAFF", true); member(staffBoth, second, "STAFF", true);
        member(staffNoGrant, first, "STAFF", false);
        timeCategory = category("시간권").get("id").asString();
        periodCategory = category("기간권").get("id").asString();
        storedCategory = category("정액권").get("id").asString();
    }

    @AfterEach void clean() {
        for (long customer : customers) {
            jdbc.update("DELETE FROM passes WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
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
        String email = UUID.randomUUID() + "@product.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'상품','상품',?,'ACTIVE')",
                email, passwords.encode("product-test-password"), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var created = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "product-fixture");
        branches.add(Long.parseLong(created.id()));
        return Long.parseLong(created.id());
    }

    void member(long person, long branch, String role, boolean writes) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
        if (writes) jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                + "FROM branch_memberships m JOIN permission_definitions p ON p.code='operations:write' "
                + "WHERE m.employee_id=? AND m.branch_id=?", person, branch);
    }

    JsonNode category(String kind) {
        var created = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류 " + kind, "kind", kind), "product-fixture");
        return json.readTree(json.writeValueAsString(created));
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

    Map<String, Object> body(String name, long price, Map<String, Object> terms, String category, long... branchIds) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", token + " " + name); body.put("priceKrw", price); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", category);
        body.put("branchIds", java.util.Arrays.stream(branchIds).mapToObj(String::valueOf).toList());
        return body;
    }

    Product create(String name, long... branchIds) {
        return products.create(owner, "all", key(), body(name, 15000, time(600, null), timeCategory, branchIds), "product-fixture");
    }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    long id(Product product) { return Long.parseLong(product.id()); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "product-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> call(Session session, String method, String path, Object body) throws Exception {
        return http(session.client(), method, path, session.token(), body, "POST".equals(method) ? key() : null);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    int post(Session session, Map<String, Object> body) throws Exception { return call(session, "POST", "/api/products", body).statusCode(); }

    // ---- categories -----------------------------------------------------------------------------------------

    @Test void categoriesAreOwnerManagedImmutableIdempotentAndKeepHistory() throws Exception {
        try (var o = login(owner); var s = login(staffFirst)) {
            String code = "K" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            String key = key();
            var request = Map.<String, Object>of("code", code, "name", "새 분류", "kind", "시간권");
            var made = http(o.client(), "POST", "/api/product-categories", o.token(), request, key);
            assertThat(made.statusCode()).isEqualTo(201);
            assertThat(made.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(node(made).get("version").asLong()).isEqualTo(1);
            assertThat(node(made).get("status").asString()).isEqualTo("활성");
            assertThat(http(o.client(), "POST", "/api/product-categories", o.token(), request, key).body()).isEqualTo(made.body());
            assertThat(http(o.client(), "POST", "/api/product-categories", o.token(), Map.of("code", code, "name", "다름", "kind", "시간권"), key).statusCode()).isEqualTo(409);
            var duplicate = http(o.client(), "POST", "/api/product-categories", o.token(), request, key());
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(duplicate.body()).contains("CATEGORY_CODE_EXISTS");
            assertThat(http(s.client(), "POST", "/api/product-categories", s.token(), request, key()).statusCode()).isEqualTo(403);
            assertThat(http(o.client(), "POST", "/api/product-categories", o.token(), Map.of("code", "lower", "name", "x", "kind", "시간권"), key()).statusCode()).isEqualTo(422);
            assertThat(http(o.client(), "POST", "/api/product-categories", o.token(), request, null).statusCode()).isEqualTo(422);
            assertThat(call(s, "GET", "/api/product-categories?q=" + code, null).statusCode()).isEqualTo(200);
            assertThat(node(call(s, "GET", "/api/product-categories?q=" + code, null)).at("/meta/total").asLong()).isEqualTo(1);

            // A product keeps the category name it was sold under even after a rename.
            String id = node(made).get("id").asString();
            var product = products.create(owner, "all", key(), body("분류 이력", 9000, time(60, null), id, first), "product-fixture");
            var renamed = call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 1, "name", "바뀐 이름"));
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(node(renamed).get("version").asLong()).isEqualTo(2);
            var stale = call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 1, "name", "낡음"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(stale.body()).contains("latestVersion", "reloadUrl");
            assertThat(call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 2, "code", "OTHER")).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 2, "kind", "기간권")).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 2)).statusCode()).isEqualTo(422);
            assertThat(call(s, "PATCH", "/api/product-categories/" + id, Map.of("version", 2, "name", "직원 변경")).statusCode()).isEqualTo(403);
            assertThat(call(o, "PATCH", "/api/product-categories/" + id, Map.of("version", 2, "status", "비활성")).statusCode()).isEqualTo(200);
            var shown = node(call(o, "GET", "/api/products/" + product.id(), null));
            assertThat(shown.at("/category/name").asString()).isEqualTo("바뀐 이름");
            assertThat(shown.at("/category/status").asString()).isEqualTo("비활성");
            var history = node(call(o, "GET", "/api/products/" + product.id() + "/history", null));
            assertThat(history.at("/items/0/after/categoryName").asString()).isEqualTo("새 분류");
            // A deactivated category cannot receive new products; the existing product is untouched.
            assertThat(post(o, body("새 상품", 9000, time(60, null), id, first))).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/products/" + product.id(), Map.of("version", 1, "name", token + " 이름 변경")).statusCode()).isEqualTo(200);
            assertThat(call(o, "DELETE", "/api/product-categories/" + id, null).statusCode()).isEqualTo(405);
        }
    }

    // ---- creation and validation ----------------------------------------------------------------------------

    @Test void createsEveryKindAndRejectsForbiddenOrOutOfRangeConditions() throws Exception {
        try (var o = login(owner)) {
            var time = call(o, "POST", "/api/products", body("무기한 시간권", 15000, time(600, null), timeCategory, first));
            assertThat(time.statusCode()).isEqualTo(201);
            assertThat(time.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(time.headers().firstValue("Location")).isPresent();
            var created = node(time);
            assertThat(created.get("code").asString()).startsWith("PD-");
            assertThat(created.get("version").asLong()).isEqualTo(1);
            assertThat(created.at("/terms/kind").asString()).isEqualTo("시간권");
            assertThat(created.at("/terms/validDays").isNull()).isTrue();
            assertThat(created.get("canEdit").asBoolean()).isTrue();
            assertThat(created.at("/category/id").asString()).isEqualTo(timeCategory);
            assertThat(jdbc.queryForObject("SELECT valid_days FROM products WHERE id=?", Long.class, created.get("id").asLong())).isNull();

            var limited = node(call(o, "POST", "/api/products", body("기한 시간권", 9000, time(60, 30L), timeCategory, first)));
            assertThat(limited.at("/terms/validDays").asLong()).isEqualTo(30);
            var periodic = node(call(o, "POST", "/api/products", body("30일권", 90000, period(30), periodCategory, first)));
            assertThat(periodic.at("/terms/periodDays").asLong()).isEqualTo(30);
            assertThat(periodic.at("/terms").has("validDays")).isFalse();
            var credit = node(call(o, "POST", "/api/products", body("정액권", 50000, stored(50000, 2000, 90L), storedCategory, first, second)));
            assertThat(credit.at("/terms/creditKrw").asLong()).isEqualTo(50000);
            assertThat(credit.get("branchIds")).hasSize(2);

            long before = count("SELECT COUNT(*) FROM products WHERE created_by=?", owner);
            var invalid = new ArrayList<Map<String, Object>>();
            invalid.add(body("0원", 0, time(60, null), timeCategory, first));
            invalid.add(body("음수", -1, time(60, null), timeCategory, first));
            var text = body("문자 금액", 1, time(60, null), timeCategory, first); text.put("priceKrw", "15000"); invalid.add(text);
            var fraction = body("소수 금액", 1, time(60, null), timeCategory, first); fraction.put("priceKrw", 1.5); invalid.add(fraction);
            invalid.add(body("안전 범위 초과", 9007199254740992L, time(60, null), timeCategory, first));
            invalid.add(body("0분", 1000, time(0, null), timeCategory, first));
            invalid.add(body("32비트 초과", 1000, time(4294967296L, null), timeCategory, first));
            invalid.add(body("0일은 무기한이 아님", 1000, time(60, 0L), timeCategory, first));
            var noValid = time(60, null); noValid.remove("validDays");
            invalid.add(body("유효일 누락", 1000, noValid, timeCategory, first));
            var mixed = time(60, null); mixed.put("periodDays", 30);
            invalid.add(body("시간권에 기간 필드", 1000, mixed, timeCategory, first));
            var periodWithValidity = period(30); periodWithValidity.put("validDays", null);
            invalid.add(body("기간권에 유효일", 1000, periodWithValidity, periodCategory, first));
            invalid.add(body("0일 기간권", 1000, period(0), periodCategory, first));
            invalid.add(body("정액 단가 0", 1000, stored(1000, 0, null), storedCategory, first));
            var noRate = stored(1000, 100, null); noRate.remove("hourlyRateKrw");
            invalid.add(body("단가 누락", 1000, noRate, storedCategory, first));
            var storedWithMinutes = stored(1000, 100, null); storedWithMinutes.put("includedMinutes", 60);
            invalid.add(body("정액권에 시간 필드", 1000, storedWithMinutes, storedCategory, first));
            var unknownKind = time(60, null); unknownKind.put("kind", "월간권");
            invalid.add(body("알 수 없는 유형", 1000, unknownKind, timeCategory, first));
            var scalarTerms = body("terms 스칼라", 1000, time(60, null), timeCategory, first); scalarTerms.put("terms", "시간권"); invalid.add(scalarTerms);
            var blankName = body("x", 1000, time(60, null), timeCategory, first); blankName.put("name", " "); invalid.add(blankName);
            var longName = body("x", 1000, time(60, null), timeCategory, first); longName.put("name", "가".repeat(121)); invalid.add(longName);
            var badStatus = body("상태", 1000, time(60, null), timeCategory, first); badStatus.put("status", "삭제"); invalid.add(badStatus);
            invalid.add(body("분류 유형 불일치", 1000, time(60, null), periodCategory, first));
            invalid.add(body("없는 분류", 1000, time(60, null), "999999999", first));
            var noBranches = body("지점 없음", 1000, time(60, null), timeCategory, first); noBranches.put("branchIds", List.of()); invalid.add(noBranches);
            var duplicateBranches = body("중복 지점", 1000, time(60, null), timeCategory, first); duplicateBranches.put("branchIds", List.of("" + first, "" + first)); invalid.add(duplicateBranches);
            var numericBranches = body("숫자 지점", 1000, time(60, null), timeCategory, first); numericBranches.put("branchIds", List.of(first)); invalid.add(numericBranches);
            var unknownField = body("추가 필드", 1000, time(60, null), timeCategory, first); unknownField.put("code", "FORGED"); invalid.add(unknownField);
            for (var candidate : invalid) assertThat(post(o, candidate)).as("%s", candidate.get("name")).isEqualTo(422);
            // The input is valid but the branch does not exist: out-of-scope IDs are 403, never created.
            assertThat(post(o, body("없는 지점", 1000, time(60, null), timeCategory, 999999999L))).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM products WHERE created_by=?", owner)).isEqualTo(before);
            assertThat(call(o, "DELETE", "/api/products/" + created.get("id").asString(), null).statusCode()).isEqualTo(405);
        }
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(http(anonymous, "GET", "/api/products", null, null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test void createWritesInitialRevisionAuditAndEventsAndReplaysByKey() throws Exception {
        try (var s = login(staffFirst); var m = login(managerFirst)) {
            String key = key();
            var request = body("직원 등록", 12000, time(120, null), timeCategory, first);
            var made = http(s.client(), "POST", "/api/products", s.token(), request, key);
            assertThat(made.statusCode()).isEqualTo(201);
            long id = node(made).get("id").asLong();
            // Q6: a general employee with operations:write registers products for the branch.
            assertThat(jdbc.queryForMap("SELECT event_type,product_version,changed_by FROM product_revisions WHERE original_product_id=?", id))
                    .containsEntry("event_type", "CREATED").containsEntry("changed_by", java.math.BigInteger.valueOf(staffFirst))
                    .containsEntry("product_version", java.math.BigInteger.ZERO);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='products' AND entity_id=? AND action='CREATED'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='products' AND entity_id=? AND branch_id=?", id, first)).isEqualTo(1);
            // Replays require the same key and input; the stored result is returned without a second row.
            assertThat(http(s.client(), "POST", "/api/products", s.token(), request, key).body()).isEqualTo(made.body());
            assertThat(count("SELECT COUNT(*) FROM products WHERE id=?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM products WHERE created_by=?", staffFirst)).isEqualTo(1);
            assertThat(http(s.client(), "POST", "/api/products", s.token(), body("다른 입력", 1000, time(60, null), timeCategory, first), key).statusCode()).isEqualTo(409);
            assertThat(http(s.client(), "POST", "/api/products", s.token(), request, "short").statusCode()).isEqualTo(422);
            assertThat(http(s.client(), "POST", "/api/products", s.token(), request, null).statusCode()).isEqualTo(422);
            // The replay is authorized again: a revoked employee cannot read the stored result with the old key.
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", staffFirst);
            assertThat(http(s.client(), "POST", "/api/products", s.token(), request, key).statusCode()).isEqualTo(403);
            jdbc.update("UPDATE branch_memberships SET status='ACTIVE' WHERE employee_id=?", staffFirst);

            assertThat(post(s, body("두 지점", 1000, time(60, null), timeCategory, first, second))).isEqualTo(403);
            assertThat(post(m, body("지점 관리자 등록", 1000, time(60, null), timeCategory, first))).isEqualTo(201);
        }
        try (var none = login(staffNoGrant)) {
            assertThat(post(none, body("권한 없음", 1000, time(60, null), timeCategory, first))).isEqualTo(403);
        }
    }

    // ---- visibility and shared-product permissions ----------------------------------------------------------

    @Test void sharedProductsHideUnreadableBranchesAndHonourScopeAndRevocation() throws Exception {
        var a = create("A 첫째만", first);
        var b = create("B 둘째만", second);
        var c = create("C 공유", first, second);
        try (var s = login(staffFirst); var both = login(staffBoth); var o = login(owner)) {
            var list = node(call(s, "GET", "/api/products?q=" + token + "&sort=name&direction=asc", null));
            assertThat(list.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(list.at("/items/0/id").asString()).isEqualTo(a.id());
            assertThat(list.at("/items/1/id").asString()).isEqualTo(c.id());
            assertThat(list.at("/items/1/branchIds")).hasSize(1);
            assertThat(list.at("/items/1/branchIds/0").asString()).isEqualTo(Long.toString(first));
            assertThat(list.at("/items/0/canEdit").asBoolean()).isTrue();
            assertThat(list.at("/items/1/canEdit").asBoolean()).isFalse();
            assertThat(call(s, "GET", "/api/products/" + b.id(), null).statusCode()).isEqualTo(404);
            assertThat(call(s, "GET", "/api/products/" + c.id() + "?scope=" + second, null).statusCode()).isEqualTo(403);
            assertThat(call(s, "GET", "/api/products/" + a.id() + "?scope=" + first, null).statusCode()).isEqualTo(200);
            assertThat(node(call(s, "GET", "/api/products?q=" + token + "&scope=" + first, null)).at("/meta/total").asLong()).isEqualTo(2);
            assertThat(call(s, "GET", "/api/products?sort=password", null).statusCode()).isEqualTo(422);
            assertThat(call(s, "GET", "/api/products?status=" + enc("삭제"), null).statusCode()).isEqualTo(422);
            assertThat(call(s, "GET", "/api/products?categoryId=abc", null).statusCode()).isEqualTo(422);

            var all = node(call(both, "GET", "/api/products?q=" + token, null));
            assertThat(all.at("/meta/total").asLong()).isEqualTo(3);
            assertThat(all.at("/items/0/canEdit").asBoolean()).isTrue();
            var page = node(call(o, "GET", "/api/products?q=" + token + "&pageSize=2&page=2&sort=priceKrw", null));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(3);
            assertThat(page.get("items")).hasSize(1);
            assertThat(node(call(o, "GET", "/api/products?q=" + token + "&kind=" + enc("기간권"), null)).at("/meta/total").asLong()).isZero();
            assertThat(node(call(o, "GET", "/api/products?q=" + token + "&categoryId=" + timeCategory + "&status=" + enc("판매 중"), null)).at("/meta/total").asLong()).isEqualTo(3);

            // Revoking the membership removes every product from the reader immediately, without a new session.
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", staffFirst);
            assertThat(node(call(s, "GET", "/api/products?q=" + token, null)).at("/meta/total").asLong()).isZero();
            assertThat(call(s, "GET", "/api/products/" + a.id(), null).statusCode()).isEqualTo(404);
            assertThat(call(s, "PATCH", "/api/products/" + a.id(), Map.of("version", 1, "priceKrw", 1)).statusCode()).isEqualTo(404);
        }
    }

    @Test void sharedProductNeedsEveryBranchAndAdministrationNeedsManager() throws Exception {
        var shared = create("공유", first, second);
        var single = create("단일", first);
        try (var s = login(staffFirst); var m = login(managerFirst); var both = login(staffBoth); var o = login(owner)) {
            // Editing: operations:write on every persisted branch (Q6/Q7).
            assertThat(call(s, "PATCH", "/api/products/" + shared.id(), Map.of("version", 1, "priceKrw", 20000)).statusCode()).isEqualTo(403);
            assertThat(call(s, "PATCH", "/api/products/" + single.id(), Map.of("version", 1, "priceKrw", 20000)).statusCode()).isEqualTo(200);
            assertThat(call(m, "PATCH", "/api/products/" + shared.id(), Map.of("version", 1, "priceKrw", 20000)).statusCode()).isEqualTo(403);
            assertThat(call(both, "PATCH", "/api/products/" + shared.id(), Map.of("version", 1, "priceKrw", 20000)).statusCode()).isEqualTo(200);
            // Stopping and branch changes need a branch manager of every branch (or the owner).
            assertThat(call(s, "POST", "/api/products/" + single.id() + "/status", Map.of("version", 2, "status", "판매 중지")).statusCode()).isEqualTo(403);
            assertThat(call(both, "POST", "/api/products/" + shared.id() + "/status", Map.of("version", 2, "status", "판매 중지")).statusCode()).isEqualTo(403);
            assertThat(call(m, "POST", "/api/products/" + shared.id() + "/status", Map.of("version", 2, "status", "판매 중지")).statusCode()).isEqualTo(403);
            assertThat(call(m, "POST", "/api/products/" + single.id() + "/status", Map.of("version", 2, "status", "판매 중지")).statusCode()).isEqualTo(200);
            assertThat(call(o, "POST", "/api/products/" + shared.id() + "/status", Map.of("version", 2, "status", "판매 중지")).statusCode()).isEqualTo(200);
            assertThat(call(m, "PUT", "/api/products/" + single.id() + "/branches", Map.of("version", 3, "branchIds", List.of("" + first, "" + second))).statusCode()).isEqualTo(403);
            assertThat(call(both, "PUT", "/api/products/" + shared.id() + "/branches", Map.of("version", 3, "branchIds", List.of("" + first))).statusCode()).isEqualTo(403);
            assertThat(call(s, "GET", "/api/products/" + shared.id(), null).statusCode()).isEqualTo(200);
        }
    }

    // ---- updates, history and purchase snapshots ------------------------------------------------------------

    @Test void updatesCreateRevisionsAndNeverRewriteExistingPurchases() throws Exception {
        var product = create("변경 상품", first);
        long id = id(product);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,?)", "PT-" + token, "고객");
        long customer = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, "PT-" + token);
        customers.add(customer);
        jdbc.update("INSERT INTO payments(customer_id,branch_id,product_id,product_name_snapshot,purpose,method,amount_krw,status,"
                + "idempotency_key,issued_pass_quantity,approved_at,created_by) VALUES(?,?,?,?,'PASS','CASH',15000,'APPROVED',?,1,UTC_TIMESTAMP(6),?)",
                customer, first, id, product.name(), "payment-" + token, owner);
        long payment = jdbc.queryForObject("SELECT id FROM payments WHERE customer_id=?", Long.class, customer);
        long revision = jdbc.queryForObject("SELECT id FROM product_revisions WHERE original_product_id=?", Long.class, id);
        jdbc.update("INSERT INTO passes(customer_id,product_id,purchase_payment_id,purchase_branch_id,purchase_product_revision_id,kind,snapshot_price_krw,"
                + "snapshot_product_name,snapshot_category_code,snapshot_category_name,snapshot_minutes,remaining_minutes,started_at) "
                + "SELECT ?,?,?,?,?,'TIME',15000,?,c.code,c.name,600,600,UTC_TIMESTAMP(6) FROM product_categories c WHERE c.id=?",
                customer, id, payment, first, revision, product.name(), timeCategory);
        try (var o = login(owner)) {
            var changed = call(o, "PATCH", "/api/products/" + id, Map.of("version", 1, "name", token + " 새 이름",
                    "priceKrw", 18000, "terms", time(900, 60L)));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).get("version").asLong()).isEqualTo(2);
            assertThat(node(changed).at("/terms/includedMinutes").asLong()).isEqualTo(900);

            // Purchase terms stay as they were sold; the product row itself can never be removed while referenced.
            var pass = jdbc.queryForMap("SELECT snapshot_price_krw,snapshot_product_name,snapshot_minutes FROM passes WHERE customer_id=?", customer);
            assertThat(pass.get("snapshot_price_krw")).isEqualTo(java.math.BigInteger.valueOf(15000));
            assertThat(pass.get("snapshot_product_name")).isEqualTo(product.name());
            assertThat(pass.get("snapshot_minutes")).isEqualTo(600L);
            assertThat(jdbc.queryForObject("SELECT purchase_product_revision_id FROM passes WHERE customer_id=?", Long.class, customer))
                    .isEqualTo(revision);
            assertThat(jdbc.queryForObject("SELECT product_name_snapshot FROM payments WHERE id=?", String.class, payment)).isEqualTo(product.name());
            assertThatThrownBy(() -> jdbc.update("DELETE FROM products WHERE id=?", id)).hasMessageContaining("foreign key constraint");

            var history = node(call(o, "GET", "/api/products/" + id + "/history", null));
            assertThat(history.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(history.at("/items/0/version").asLong()).isEqualTo(2);
            assertThat(history.at("/items/0/changeType").asString()).isEqualTo("UPDATED");
            assertThat(history.at("/items/0/before/priceKrw").asLong()).isEqualTo(15000);
            assertThat(history.at("/items/0/after/priceKrw").asLong()).isEqualTo(18000);
            assertThat(history.at("/items/0/after/terms/includedMinutes").asLong()).isEqualTo(900);
            assertThat(history.at("/items/0/actorId").asString()).isEqualTo(Long.toString(owner));
            assertThat(history.at("/items/1/changeType").asString()).isEqualTo("CREATED");
            assertThat(history.at("/items/1/before").isNull()).isTrue();
            assertThat(history.at("/items/1/productId").asString()).isEqualTo(product.id());
            var ascending = node(call(o, "GET", "/api/products/" + id + "/history?direction=asc", null));
            assertThat(ascending.at("/items/0/changeType").asString()).isEqualTo("CREATED");
            assertThat(call(o, "GET", "/api/products/" + id + "/history?sort=version", null).statusCode()).isEqualTo(422);

            // Identical content is not a change: no version, revision or audit row.
            long audits = count("SELECT COUNT(*) FROM audit_events WHERE entity_type='products' AND entity_id=?", id);
            var same = call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "priceKrw", 18000));
            assertThat(same.statusCode()).isEqualTo(200);
            assertThat(node(same).get("version").asLong()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='products' AND entity_id=?", id)).isEqualTo(audits);

            var stale = call(o, "PATCH", "/api/products/" + id, Map.of("version", 1, "priceKrw", 1000));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/products/" + id);
            assertThat(call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "terms", period(30))).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "categoryId", periodCategory)).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/products/" + id, Map.of("version", 2)).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "kind", "기간권")).statusCode()).isEqualTo(422);
            assertThat(call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "priceKrw", 0)).statusCode()).isEqualTo(422);
            var explicitNull = new HashMap<String, Object>(); explicitNull.put("version", 2); explicitNull.put("name", null);
            assertThat(call(o, "PATCH", "/api/products/" + id, explicitNull).statusCode()).isEqualTo(422);
            var otherTime = category("시간권").get("id").asString();
            var recategorized = call(o, "PATCH", "/api/products/" + id, Map.of("version", 2, "categoryId", otherTime));
            assertThat(recategorized.statusCode()).isEqualTo(200);
            assertThat(node(recategorized).at("/category/id").asString()).isEqualTo(otherTime);
            assertThat(call(o, "PATCH", "/api/products/999999999", Map.of("version", 1, "priceKrw", 1000)).statusCode()).isEqualTo(404);
        }
    }

    // ---- stop / resume --------------------------------------------------------------------------------------

    @Test void stoppedProductsCannotBeSoldButKeepHistoryAndResume() throws Exception {
        var product = create("중지 대상", first);
        long id = id(product);
        try (var o = login(owner)) {
            var stopped = call(o, "POST", "/api/products/" + id + "/status", Map.of("version", 1, "status", "판매 중지"));
            assertThat(stopped.statusCode()).isEqualTo(200);
            assertThat(node(stopped).get("status").asString()).isEqualTo("판매 중지");
            assertThat(node(stopped).get("version").asLong()).isEqualTo(2);
            assertThat(node(call(o, "POST", "/api/products/" + id + "/status", Map.of("version", 2, "status", "판매 중지"))).get("version").asLong()).isEqualTo(2);
            assertThat(call(o, "POST", "/api/products/" + id + "/status", Map.of("version", 1, "status", "판매 중")).statusCode()).isEqualTo(409);
            assertThat(call(o, "POST", "/api/products/" + id + "/status", Map.of("version", 2, "status", "삭제")).statusCode()).isEqualTo(422);
            assertThat(call(o, "GET", "/api/products/" + id, null).statusCode()).isEqualTo(200);
            assertThat(node(call(o, "GET", "/api/products?q=" + token + "&status=" + enc("판매 중지"), null)).at("/meta/total").asLong()).isEqualTo(1);

            assertThatThrownBy(() -> products.requireSellable(id, first)).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(409);
                assertThat(error.error("r").code()).isEqualTo("PRODUCT_NOT_ON_SALE");
            });
            var resumed = call(o, "POST", "/api/products/" + id + "/status", Map.of("version", 2, "status", "판매 중"));
            assertThat(node(resumed).get("status").asString()).isEqualTo("판매 중");
            var snapshot = products.requireSellable(id, first);
            assertThat(snapshot.priceKrw()).isEqualTo(15000);
            assertThat(snapshot.terms()).isEqualTo(ProductTerms.Time.of(600, null));
            assertThat(snapshot.status()).isEqualTo("판매 중");
            assertThatThrownBy(() -> products.requireSellable(id, second)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.error("r").code()).isEqualTo("PRODUCT_NOT_AVAILABLE_AT_BRANCH"));
            assertThatThrownBy(() -> products.requireSellable(999999999L, first)).isInstanceOf(ResponseStatusException.class);

            // Stop and resume keep the same ID and add revisions instead of removing anything.
            assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", id)).isEqualTo(3);
            assertThat(call(o, "DELETE", "/api/products/" + id, null).statusCode()).isEqualTo(405);
            assertThat(count("SELECT COUNT(*) FROM products WHERE id=?", id)).isEqualTo(1);
        }
    }

    // ---- branches and history permission --------------------------------------------------------------------

    @Test void branchReplacementKeepsPastScopeInHistoryAndLimitsWhoCanReadIt() throws Exception {
        var product = create("지점 변경", first);
        long id = id(product);
        try (var o = login(owner); var m = login(managerFirst); var s = login(staffFirst)) {
            var changed = call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 1, "branchIds", List.of("" + first, "" + second)));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).get("version").asLong()).isEqualTo(2);
            assertThat(node(changed).get("branchIds")).hasSize(2);
            assertThat(node(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 2, "branchIds", List.of("" + second, "" + first)))).get("version").asLong()).isEqualTo(2);
            assertThat(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 1, "branchIds", List.of("" + first))).statusCode()).isEqualTo(409);
            assertThat(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 2, "branchIds", List.of())).statusCode()).isEqualTo(422);
            assertThat(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 2, "branchIds", List.of("999999999"))).statusCode()).isEqualTo(403);
            // The branch manager of one branch can neither add nor drop a branch of a two-branch product.
            assertThat(call(m, "PUT", "/api/products/" + id + "/branches", Map.of("version", 2, "branchIds", List.of("" + first))).statusCode()).isEqualTo(403);
            assertThat(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 2, "branchIds", List.of("" + first))).statusCode()).isEqualTo(200);

            var snapshots = node(call(o, "GET", "/api/products/" + id + "/history?direction=asc", null));
            assertThat(snapshots.at("/items/1/after/branchIds")).hasSize(2);
            assertThat(snapshots.at("/items/2/after/branchIds")).hasSize(1);
            assertThat(snapshots.at("/items/2/before/branchIds")).hasSize(2);
            // Now only sold at the first branch, yet the history still names the second one.
            assertThat(call(m, "GET", "/api/products/" + id, null).statusCode()).isEqualTo(200);
            assertThat(call(m, "GET", "/api/products/" + id + "/history", null).statusCode()).isEqualTo(403);
            assertThat(call(s, "GET", "/api/products/" + id + "/history", null).statusCode()).isEqualTo(403);
            assertThat(call(o, "PUT", "/api/products/" + id + "/branches", Map.of("version", 3, "branchIds", List.of("" + second))).statusCode()).isEqualTo(200);
            assertThat(call(m, "GET", "/api/products/" + id, null).statusCode()).isEqualTo(404);
        }
    }

    // ---- concurrency and rollback ---------------------------------------------------------------------------

    private int[] race(Callable<Object> a, Callable<Object> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return a.call(); });
            var two = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            int ok = 0, conflicts = 0;
            for (var future : List.of(one, two)) {
                try { future.get(30, TimeUnit.SECONDS); ok++; }
                catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) failure.getCause()).status()).isEqualTo(409);
                    conflicts++;
                }
            }
            return new int[] {ok, conflicts};
        }
    }

    @Test void concurrentSameVersionWritesYieldExactlyOneWinner() throws Exception {
        var price = create("가격 경합", first);
        long priceId = id(price);
        var result = race(() -> products.update(owner, priceId, "all", Map.of("version", 1, "priceKrw", 11000), "race-a"),
                () -> products.update(owner, priceId, "all", Map.of("version", 1, "priceKrw", 12000), "race-b"));
        assertThat(result).containsExactly(1, 1);
        assertThat(count("SELECT version FROM products WHERE id=?", priceId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", priceId)).isEqualTo(2);

        var mixed = create("가격과 지점 경합", first);
        long mixedId = id(mixed);
        result = race(() -> products.update(owner, mixedId, "all", Map.of("version", 1, "priceKrw", 11000), "race-c"),
                () -> products.replaceBranches(owner, mixedId, "all", Map.of("version", 1, "branchIds", List.of("" + first, "" + second)), "race-d"));
        assertThat(result).containsExactly(1, 1);
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", mixedId)).isEqualTo(2);

        var status = create("상태 경합", first);
        long statusId = id(status);
        result = race(() -> products.changeStatus(owner, statusId, "all", Map.of("version", 1, "status", "판매 중지"), "race-e"),
                () -> products.changeStatus(owner, statusId, "all", Map.of("version", 1, "status", "판매 중지"), "race-f"));
        assertThat(result).containsExactly(1, 1);
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", statusId)).isEqualTo(2);
    }

    @Test void failedCommandsRollBackProductRevisionAuditAndEventsTogether() {
        var product = create("롤백", first);
        long id = id(product);
        long revisions = count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", id);
        long audits = count("SELECT COUNT(*) FROM audit_events WHERE entity_type='products' AND entity_id=?", id);
        long events = count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='products' AND entity_id=?", id);
        transactions.executeWithoutResult(tx -> {
            products.changeStatus(owner, id, "all", Map.of("version", 1, "status", "판매 중지"), "rollback");
            tx.setRollbackOnly();
        });
        assertThat(count("SELECT version FROM products WHERE id=?", id)).isZero();
        assertThat(jdbc.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id)).isEqualTo("ON_SALE");
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=?", id)).isEqualTo(revisions);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='products' AND entity_id=?", id)).isEqualTo(audits);
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='products' AND entity_id=?", id)).isEqualTo(events);

        String key = key();
        long created = count("SELECT COUNT(*) FROM products WHERE created_by=?", owner);
        transactions.executeWithoutResult(tx -> {
            products.create(owner, "all", key, body("롤백 등록", 5000, time(30, null), timeCategory, first), "rollback-create");
            tx.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM products WHERE created_by=?", owner)).isEqualTo(created);
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key=?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE request_id='rollback-create'")).isZero();
    }
}
