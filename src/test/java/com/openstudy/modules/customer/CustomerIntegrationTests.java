package com.openstudy.modules.customer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class CustomerIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired TransactionTemplate transactions;
    @LocalServerPort int port;

    long owner, writerFirst, readerFirst, writerSecond, writerBoth, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), created = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER");
        writerFirst = person("EMPLOYEE"); readerFirst = person("EMPLOYEE");
        writerSecond = person("EMPLOYEE"); writerBoth = person("EMPLOYEE");
        first = branch("고객 첫 지점"); second = branch("고객 둘째 지점");
        member(writerFirst, first, true); member(readerFirst, first, false);
        member(writerSecond, second, true);
        member(writerBoth, first, true); member(writerBoth, second, true);
    }

    @AfterEach void clean() {
        for (long customer : created) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customer_status_events WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE entity_type='customers' AND entity_id=?", customer);
            jdbc.update("DELETE FROM audit_events WHERE entity_type='customers' AND entity_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        String actors = people.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
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
        String email = UUID.randomUUID() + "@customer.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'고객','고객',?,'ACTIVE')",
                email, passwords.encode("customer-test-password"), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "customer-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, boolean writes) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        if (writes) jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                + "FROM branch_memberships m JOIN permission_definitions p ON p.code='members:write' "
                + "WHERE m.employee_id=? AND m.branch_id=?", person, branch);
    }

    Map<String, Object> body(long branch, String name, String phone) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name); body.put("phone", phone);
        return body;
    }

    long register(long branch, String name, String phone) {
        var made = customers.create(owner, "all", key(), body(branch, name, phone), "customer-fixture");
        long id = Long.parseLong(made.id());
        created.add(id);
        return id;
    }

    /** A pre-V2 row: no registration branch and no payment, so only OWNER may see it. */
    long legacy(String name) {
        String memberNo = "L-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO customers(member_no,name,phone) VALUES(?,?,'01000000000')", memberNo, name);
        long id = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, memberNo);
        created.add(id);
        return id;
    }

    void paidAt(long customer, long branch) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,failed_at,created_by) "
                + "VALUES(?,?,'ROOM','CASH',1000,'FAILED',?,CURRENT_TIMESTAMP(6),?)", customer, branch, UUID.randomUUID().toString(), owner);
    }

    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "customer-test-password"), null).statusCode()).isEqualTo(200);
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
        return http(session.client(), method, path, session.token(), body, null);
    }

    HttpResponse<String> create(Session session, Object body, String key) throws Exception {
        var response = http(session.client(), "POST", "/api/customers", session.token(), body, key);
        if (response.statusCode() == 201) {
            long id = node(response).get("id").asLong();
            if (!created.contains(id)) created.add(id);
        }
        return response;
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    List<String> names(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> result = new ArrayList<>();
        node(response).get("items").forEach(item -> result.add(item.get("name").asString()));
        return result;
    }

    // ---- registration ---------------------------------------------------------------------------------------

    @Test void registrationValidatesInputNormalizesPhoneAndReplaysByKey() throws Exception {
        try (var w = login(writerFirst)) {
            var input = body(first, "  홍길동  ", "010-1234 5678");
            input.put("email", "");
            input.put("memo", "창가 자리 선호");
            String key = key();
            var made = create(w, input, key);
            assertThat(made.statusCode()).isEqualTo(201);
            var customer = node(made);
            long id = customer.get("id").asLong();
            assertThat(made.headers().firstValue("Location")).contains("/api/customers/" + id);
            assertThat(customer.get("name").asString()).isEqualTo("홍길동");
            assertThat(customer.get("phone").asString()).isEqualTo("01012345678");
            assertThat(customer.get("email").isNull()).isTrue();
            assertThat(customer.get("memo").asString()).isEqualTo("창가 자리 선호");
            assertThat(customer.get("status").asString()).isEqualTo("정상");
            assertThat(customer.get("statusReason").isNull()).isTrue();
            assertThat(customer.get("registeredBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(customer.get("version").asLong()).isEqualTo(1);
            assertThat(customer.get("memberNo").asString()).isNotBlank().hasSizeLessThanOrEqualTo(40);
            assertThat(customer.get("canEdit").asBoolean()).isTrue();

            // Same key and input: the first result again, no second customer, audit row or event.
            var replay = create(w, input, key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay).get("id").asLong()).isEqualTo(id);
            assertThat(count("SELECT COUNT(*) FROM customers WHERE member_no=?", customer.get("memberNo").asString())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='CREATED'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='customers' AND entity_id=? AND branch_id=?", id, first)).isEqualTo(1);
            // The audit row records who and when, not the contact details themselves.
            String after = jdbc.queryForObject("SELECT after_json FROM audit_events WHERE entity_type='customers' AND entity_id=?", String.class, id);
            assertThat(after).doesNotContain("01012345678").doesNotContain("홍길동");

            input.put("name", "다른 이름");
            var conflict = create(w, input, key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(node(conflict).get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");

            // Required fields, formats and server-owned fields are rejected with 422.
            assertThat(create(w, Map.of("registeredBranchId", "" + first, "phone", "01012345678"), key()).statusCode()).isEqualTo(422);
            assertThat(create(w, Map.of("registeredBranchId", "" + first, "name", "이름"), key()).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "이름", "0101234"), key()).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "이름", "010123456789"), key()).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "이름", "010-abcd-5678"), key()).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "가".repeat(101), "01012345678"), key()).statusCode()).isEqualTo(422);
            var badEmail = body(first, "이름", "01012345678");
            badEmail.put("email", "not-an-email");
            assertThat(create(w, badEmail, key()).statusCode()).isEqualTo(422);
            var longMemo = body(first, "이름", "01012345678");
            longMemo.put("memo", "가".repeat(1001));
            assertThat(create(w, longMemo, key()).statusCode()).isEqualTo(422);
            var memberNo = body(first, "이름", "01012345678");
            memberNo.put("memberNo", "M-CLIENT");
            assertThat(create(w, memberNo, key()).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "이름", "01012345678"), null).statusCode()).isEqualTo(422);
            assertThat(create(w, body(first, "이름", "01012345678"), "short").statusCode()).isEqualTo(422);
            // Duplicate phone numbers are allowed; merging duplicates is M13.
            assertThat(create(w, body(first, "동명이인", "01012345678"), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void registrationNeedsMembersWriteAtAnOperatingBranch() throws Exception {
        try (var r = login(readerFirst); var w = login(writerFirst); var o = login(owner)) {
            var denied = create(r, body(first, "권한 없음", "01012345678"), key());
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(create(w, body(second, "다른 지점", "01012345678"), key()).statusCode()).isEqualTo(403);
            assertThat(create(w, body(999_999_999L, "없는 지점", "01012345678"), key()).statusCode()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM customers WHERE name IN ('권한 없음','다른 지점','없는 지점')")).isZero();

            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", second);
            var stopped = create(o, body(second, "중지 지점", "01012345678"), key());
            assertThat(stopped.statusCode()).isEqualTo(409);
            assertThat(node(stopped).get("code").asString()).isEqualTo("BRANCH_NOT_ACTIVE");
            assertThat(count("SELECT COUNT(*) FROM customers WHERE name='중지 지점'")).isZero();
        }
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(http(anonymous, "GET", "/api/customers", null, null, null).statusCode()).isEqualTo(401);
        }
    }

    // ---- branch scope ---------------------------------------------------------------------------------------

    @Test void listAndDetailOnlyShowCustomersRegisteredOrPaidInScope() throws Exception {
        long here = register(first, "범위 첫 지점", "01011112222");
        long there = register(second, "범위 둘째 지점", "01033334444");
        long unassigned = legacy("범위 미지정");
        try (var r = login(readerFirst); var s = login(writerSecond); var both = login(writerBoth); var o = login(owner)) {
            // Reading needs a valid membership only, not members:write.
            assertThat(names(call(r, "GET", "/api/customers?q=" + enc("범위"), null))).containsExactly("범위 첫 지점");
            assertThat(call(r, "GET", "/api/customers/" + here, null).statusCode()).isEqualTo(200);
            assertThat(node(call(r, "GET", "/api/customers/" + here, null)).get("canEdit").asBoolean()).isFalse();
            var hidden = call(r, "GET", "/api/customers/" + there, null);
            assertThat(hidden.statusCode()).isEqualTo(404);
            assertThat(call(r, "GET", "/api/customers/999999999", null).statusCode()).isEqualTo(404);
            assertThat(call(r, "GET", "/api/customers/" + unassigned, null).statusCode()).isEqualTo(404);
            assertThat(node(call(r, "GET", "/api/customers?q=" + enc("범위"), null)).at("/meta/total").asLong()).isEqualTo(1);

            assertThat(names(call(both, "GET", "/api/customers?q=" + enc("범위") + "&sort=name&direction=asc", null)))
                    .containsExactly("범위 둘째 지점", "범위 첫 지점");
            assertThat(names(call(both, "GET", "/api/customers?q=" + enc("범위") + "&scope=" + second, null))).containsExactly("범위 둘째 지점");
            assertThat(call(r, "GET", "/api/customers?scope=" + second, null).statusCode()).isEqualTo(403);

            // A payment at the second branch, whatever its status, brings the customer into that branch's scope.
            assertThat(call(s, "GET", "/api/customers/" + here, null).statusCode()).isEqualTo(404);
            paidAt(here, second);
            assertThat(call(s, "GET", "/api/customers/" + here, null).statusCode()).isEqualTo(200);
            assertThat(names(call(s, "GET", "/api/customers?q=" + enc("범위"), null))).containsExactlyInAnyOrder("범위 첫 지점", "범위 둘째 지점");

            // A customer without a registration branch is OWNER-only, and only in the all-branch scope.
            assertThat(names(call(o, "GET", "/api/customers?q=" + enc("범위"), null))).contains("범위 미지정");
            assertThat(names(call(o, "GET", "/api/customers?q=" + enc("범위") + "&scope=" + first, null))).doesNotContain("범위 미지정");
            assertThat(call(o, "GET", "/api/customers/" + unassigned, null).statusCode()).isEqualTo(200);
            assertThat(node(call(o, "GET", "/api/customers/" + unassigned, null)).get("registeredBranchId").isNull()).isTrue();
        }
    }

    @Test void searchUsesOnlyAllowedConditionsAndReturnsMinimalFields() throws Exception {
        long id = register(first, "검색 대상", "01098765432");
        register(first, "다른 고객", "01055556666");
        var stored = jdbc.queryForMap("SELECT member_no FROM customers WHERE id=?", id);
        String memberNo = (String) stored.get("member_no");
        jdbc.update("UPDATE customers SET email='search@customer.test', memo='메모' WHERE id=?", id);
        try (var r = login(readerFirst)) {
            assertThat(names(call(r, "GET", "/api/customers?q=" + enc("검색"), null))).containsExactly("검색 대상");
            assertThat(names(call(r, "GET", "/api/customers?q=" + memberNo, null))).containsExactly("검색 대상");
            // A member number matches exactly, never as a fragment.
            assertThat(names(call(r, "GET", "/api/customers?q=" + memberNo.substring(0, memberNo.length() - 1), null))).isEmpty();
            assertThat(names(call(r, "GET", "/api/customers?q=5432", null))).containsExactly("검색 대상");
            assertThat(names(call(r, "GET", "/api/customers?q=" + enc("8765-432"), null))).containsExactly("검색 대상");
            // Fewer than four digits is not a phone search.
            assertThat(names(call(r, "GET", "/api/customers?q=432", null))).isEmpty();
            // Email and memo are not search keys.
            assertThat(names(call(r, "GET", "/api/customers?q=" + enc("search@customer.test"), null))).isEmpty();
            assertThat(names(call(r, "GET", "/api/customers?q=" + enc("%"), null))).isEmpty();

            var item = node(call(r, "GET", "/api/customers?q=" + enc("검색"), null)).at("/items/0");
            List<String> fields = new ArrayList<>();
            item.propertyNames().forEach(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "memberNo", "name", "phone", "status", "registeredBranchId", "registeredAt");

            assertThat(call(r, "GET", "/api/customers?pageSize=101", null).statusCode()).isEqualTo(422);
            assertThat(call(r, "GET", "/api/customers?sort=phone", null).statusCode()).isEqualTo(422);
            assertThat(call(r, "GET", "/api/customers?status=VIP", null).statusCode()).isEqualTo(422);
            var paged = node(call(r, "GET", "/api/customers?pageSize=1&sort=name&direction=asc", null));
            assertThat(paged.get("items")).hasSize(1);
            assertThat(paged.at("/meta/total").asLong()).isEqualTo(2);
        }
    }

    // ---- update ---------------------------------------------------------------------------------------------

    @Test void updateChecksVersionImmutableFieldsAndAuditsTheChange() throws Exception {
        long id = register(first, "수정 대상", "01012340000");
        try (var w = login(writerFirst); var r = login(readerFirst)) {
            var memo = new HashMap<String, Object>();
            memo.put("version", 1); memo.put("memo", "주차 문의"); memo.put("phone", "010-9999-0000");
            var changed = call(w, "PATCH", "/api/customers/" + id, memo);
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).get("version").asLong()).isEqualTo(2);
            assertThat(node(changed).get("phone").asString()).isEqualTo("01099990000");
            assertThat(node(changed).get("name").asString()).isEqualTo("수정 대상");
            var audit = jdbc.queryForMap("SELECT actor_employee_id, after_json, occurred_at FROM audit_events "
                    + "WHERE entity_type='customers' AND entity_id=? AND action='UPDATED'", id);
            assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(writerFirst);
            assertThat(audit.get("occurred_at")).isNotNull();
            assertThat((String) audit.get("after_json")).contains("memo", "phone").doesNotContain("01099990000");

            var stale = call(w, "PATCH", "/api/customers/" + id, Map.of("version", 1, "name", "늦은 수정"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(node(stale).get("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/customers/" + id);

            // An identical request changes nothing and writes no audit row.
            assertThat(node(call(w, "PATCH", "/api/customers/" + id, Map.of("version", 2, "memo", "주차 문의"))).get("version").asLong()).isEqualTo(2);
            var clear = new HashMap<String, Object>();
            clear.put("version", 2); clear.put("memo", "");
            assertThat(node(call(w, "PATCH", "/api/customers/" + id, clear)).get("memo").isNull()).isTrue();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='UPDATED'", id)).isEqualTo(2);

            for (String field : List.of("memberNo", "registeredBranchId", "status")) {
                var immutable = call(w, "PATCH", "/api/customers/" + id, Map.of("version", 3, field, "x"));
                assertThat(immutable.statusCode()).isEqualTo(422);
                assertThat(node(immutable).get("code").asString()).isEqualTo("IMMUTABLE_FIELD");
            }
            assertThat(call(w, "PATCH", "/api/customers/" + id, Map.of("version", 3)).statusCode()).isEqualTo(422);
            assertThat(call(w, "PATCH", "/api/customers/" + id, Map.of("version", 3, "phone", "12")).statusCode()).isEqualTo(422);
            assertThat(call(w, "PATCH", "/api/customers/" + id, Map.of("version", 3, "nickname", "x")).statusCode()).isEqualTo(422);

            // members:write is required to change a customer even when it can be read.
            assertThat(call(r, "PATCH", "/api/customers/" + id, Map.of("version", 3, "name", "권한 없음")).statusCode()).isEqualTo(403);
            assertThat(call(r, "POST", "/api/customers/" + id + "/status",
                    Map.of("version", 3, "status", "비활성", "reason", "권한 없음")).statusCode()).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id=?", String.class, id)).isEqualTo("수정 대상");
            assertThat(call(w, "DELETE", "/api/customers/" + id, null).statusCode()).isEqualTo(405);
        }
    }

    @Test void aPaymentBranchWriterMayEditButAHiddenCustomerStays404() throws Exception {
        long id = register(first, "결제 지점 수정", "01012341111");
        try (var s = login(writerSecond)) {
            assertThat(call(s, "PATCH", "/api/customers/" + id, Map.of("version", 1, "name", "보이지 않음")).statusCode()).isEqualTo(404);
            paidAt(id, second);
            var changed = call(s, "PATCH", "/api/customers/" + id, Map.of("version", 1, "name", "둘째 지점에서 수정"));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).get("canEdit").asBoolean()).isTrue();
            assertThat(node(changed).get("registeredBranchId").asString()).isEqualTo(Long.toString(first));
        }
    }

    @Test void concurrentSameVersionWritesYieldExactlyOneWinner() throws Exception {
        long id = register(first, "경합", "01012342222");
        var result = race(() -> customers.update(writerFirst, id, "all", Map.of("version", 1, "name", "경합 A"), "race-a"),
                () -> customers.update(writerBoth, id, "all", Map.of("version", 1, "name", "경합 B"), "race-b"));
        assertThat(result).containsExactly(1, 1);
        assertThat(count("SELECT version FROM customers WHERE id=?", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='UPDATED'", id)).isEqualTo(1);
    }

    // ---- status ---------------------------------------------------------------------------------------------

    @Test void statusChangeNeedsReasonRecordsHistoryAndKeepsRelatedRows() throws Exception {
        long id = register(first, "상태 대상", "01012343333");
        paidAt(id, first);
        try (var w = login(writerFirst)) {
            String path = "/api/customers/" + id + "/status";
            assertThat(call(w, "POST", path, Map.of("version", 1, "status", "이용 제한")).statusCode()).isEqualTo(422);
            assertThat(call(w, "POST", path, Map.of("version", 1, "status", "이용 제한", "reason", " ")).statusCode()).isEqualTo(422);
            assertThat(call(w, "POST", path, Map.of("version", 1, "status", "RESTRICTED", "reason", "사유")).statusCode()).isEqualTo(422);
            assertThat(call(w, "POST", path, Map.of("version", 1, "status", "이용 제한", "reason", "가".repeat(501))).statusCode()).isEqualTo(422);

            var restricted = call(w, "POST", path, Map.of("version", 1, "status", "이용 제한", "reason", "소음 민원 반복"));
            assertThat(restricted.statusCode()).isEqualTo(200);
            assertThat(node(restricted).get("status").asString()).isEqualTo("이용 제한");
            assertThat(node(restricted).get("statusReason").asString()).isEqualTo("소음 민원 반복");
            assertThat(node(restricted).get("version").asLong()).isEqualTo(2);
            var event = jdbc.queryForMap("SELECT previous_status, new_status, reason, actor_employee_id FROM customer_status_events WHERE customer_id=?", id);
            assertThat(event.get("previous_status")).isEqualTo("NORMAL");
            assertThat(event.get("new_status")).isEqualTo("RESTRICTED");
            assertThat(event.get("reason")).isEqualTo("소음 민원 반복");
            assertThat(((Number) event.get("actor_employee_id")).longValue()).isEqualTo(writerFirst);

            // The same status is not a change: no history, audit or version bump.
            var same = call(w, "POST", path, Map.of("version", 2, "status", "이용 제한", "reason", "다시"));
            assertThat(node(same).get("version").asLong()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM customer_status_events WHERE customer_id=?", id)).isEqualTo(1);

            assertThat(call(w, "POST", path, Map.of("version", 1, "status", "비활성", "reason", "탈퇴")).statusCode()).isEqualTo(409);
            var inactive = call(w, "POST", path, Map.of("version", 2, "status", "비활성", "reason", "탈퇴 요청"));
            assertThat(node(inactive).get("status").asString()).isEqualTo("비활성");
            assertThat(count("SELECT COUNT(*) FROM customer_status_events WHERE customer_id=?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='STATUS_CHANGED'", id)).isEqualTo(2);
            // Deactivation keeps the customer, its payments and its visibility for history lookups.
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", id)).isEqualTo(1);
            assertThat(names(call(w, "GET", "/api/customers?status=" + enc("비활성") + "&q=" + enc("상태 대상"), null))).containsExactly("상태 대상");
            assertThat(names(call(w, "GET", "/api/customers?status=" + enc("정상") + "&q=" + enc("상태 대상"), null))).isEmpty();
        }
    }

    @Test void failedCommandsRollBackCustomerHistoryAuditAndEventsTogether() {
        long id = register(first, "롤백", "01012344444");
        long events = count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='customers' AND entity_id=?", id);
        transactions.executeWithoutResult(tx -> {
            customers.changeStatus(owner, id, "all", Map.of("version", 1, "status", "비활성", "reason", "롤백"), "rollback");
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM customers WHERE id=?", String.class, id)).isEqualTo("NORMAL");
        assertThat(count("SELECT version FROM customers WHERE id=?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM customer_status_events WHERE customer_id=?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customers' AND entity_id=? AND action='STATUS_CHANGED'", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='customers' AND entity_id=?", id)).isEqualTo(events);
    }

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
}
