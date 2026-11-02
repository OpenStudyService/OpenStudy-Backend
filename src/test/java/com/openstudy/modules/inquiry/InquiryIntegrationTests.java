package com.openstudy.modules.inquiry;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #79: HTTP and MySQL behaviour of the inquiry contract (collaboration-api.md, ADR-M7-001 M7-D02). The target
 * customer rule is checked against the real exact-match lookup, and the races run real concurrent requests.
 */
@MySqlIntegrationTest
class InquiryIntegrationTests {
    static final String PHONE = "01055550123", EMAIL_PART = "private-mail", MEMO = "비공개 응대 메모";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    long owner, manager, staff, colleague, assignee, outsider, dual;
    long first, second, third;
    long inScope, lookedUp, other, thirdCustomer;
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    int customerCount;

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "문의 관리자"); staff = person("EMPLOYEE", "접수 직원"); colleague = person("EMPLOYEE", "동료 직원");
        assignee = person("EMPLOYEE", "문의 담당"); outsider = person("EMPLOYEE", "타지점 직원"); dual = person("EMPLOYEE", "겸직 직원");
        first = branch("문의 첫 지점"); second = branch("문의 둘째 지점"); third = branch("문의 셋째 지점");
        member(manager, first, "MANAGER"); member(staff, first, "STAFF"); member(colleague, first, "STAFF");
        member(assignee, first, "STAFF"); member(outsider, second, "STAFF");
        member(dual, first, "STAFF"); member(dual, second, "STAFF");
        inScope = customer("범위 안 고객", first);
        lookedUp = customer("조회 고객", second);
        other = customer("범위 밖 고객", second);
        thirdCustomer = customer("셋째 지점 고객", third);
    }

    @AfterEach void clean() {
        for (long id : branches) {
            jdbc.update("DELETE e FROM inquiry_events e JOIN inquiries i ON i.id=e.inquiry_id WHERE i.branch_id=?", id);
            jdbc.update("DELETE FROM inquiries WHERE branch_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
        }
        jdbc.update("DELETE FROM customer_service_interactions WHERE customer_id IN (SELECT id FROM customers WHERE member_no LIKE ?)", token + "%");
        for (long id : branches) {
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
        }
        jdbc.update("DELETE FROM customers WHERE member_no LIKE ?", token + "%");
        for (long id : branches) jdbc.update("DELETE FROM branches WHERE id=?", id);
        for (long id : people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@inquiry.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("inquiry-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "inquiry-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    /** Contact data and a memo are filled in so a response that leaks them is caught. */
    long customer(String name, Long registeredBranch) {
        String memberNo = token + "-" + (++customerCount);
        jdbc.update("INSERT INTO customers(member_no,name,phone,email,memo,registered_branch_id) VALUES(?,?,?,?,?,?)",
                memberNo, token + name, PHONE, EMAIL_PART + customerCount + "@example.test", MEMO, registeredBranch);
        return jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, memberNo);
    }

    String memberNo(long customer) { return jdbc.queryForObject("SELECT member_no FROM customers WHERE id=?", String.class, customer); }

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "inquiry-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> send(Session session, String path, Object body) throws Exception {
        return http(session.client(), "POST", path, session.token(), body, null);
    }

    Map<String, Object> draft(long branch, long customer, String subject) {
        var body = new HashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("customerId", Long.toString(customer));
        body.put("subject", subject); body.put("body", "문의 내용: " + subject); body.put("source", "전화");
        return body;
    }

    HttpResponse<String> createRaw(Session session, Map<String, Object> body, String key) throws Exception {
        return http(session.client(), "POST", "/api/inquiries", session.token(), body, key);
    }

    JsonNode create(Session session, long branch, long customer, String subject) throws Exception {
        var response = createRaw(session, draft(branch, customer, subject), key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    JsonNode reload(Session session, JsonNode current) throws Exception { return node(get(session, "/api/inquiries/" + id(current))); }

    Map<String, Object> versioned(JsonNode current, Object... pairs) {
        var body = new HashMap<String, Object>();
        body.put("version", current.get("version").asLong());
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    HttpResponse<String> act(Session session, JsonNode current, String action, Object... pairs) throws Exception {
        return send(session, "/api/inquiries/" + id(current) + "/" + action, versioned(current, pairs));
    }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode answered(Session session, JsonNode current, String text) throws Exception { return ok(act(session, current, "answer", "answer", text)); }

    void lookup(Session session, long branch, long customer) throws Exception {
        var response = send(session, "/api/customer-lookups/check-in", Map.of("branchId", Long.toString(branch), "memberNo", memberNo(customer)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    static String key() { return UUID.randomUUID().toString(); }
    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    long id(JsonNode inquiry) { return inquiry.get("id").asLong(); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    long total(Session session, String query) throws Exception { return node(get(session, "/api/inquiries?" + query)).at("/meta/total").asLong(); }
    static String join(String left, String right) { return left.isEmpty() ? right : right.isEmpty() ? left : left + "&" + right; }
    long events(long inquiry) { return count("SELECT COUNT(*) FROM inquiry_events WHERE inquiry_id=?", inquiry); }

    JsonNode history(Session session, JsonNode inquiry) throws Exception {
        var response = get(session, "/api/inquiries/" + id(inquiry) + "/events");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response).get("items");
    }

    // ---- registration, answer and what the response may contain ---------------------------------------------

    @Test void anyBranchEmployeeRegistersAndAnswersAndTheCustomerIsOnlyIdentified() throws Exception {
        try (var s = login(staff); var c = login(colleague)) {
            var response = createRaw(s, draft(first, inScope, "자리 문의"), key());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(response.headers().firstValue("Location")).isPresent();
            var created = node(response);
            assertThat(created.get("status").asString()).isEqualTo("접수");
            assertThat(created.get("source").asString()).isEqualTo("전화");
            assertThat(created.get("createdBy").get("id").asString()).isEqualTo(Long.toString(staff));
            assertThat(created.get("assignee").isNull()).isTrue();
            assertThat(created.get("answer").isNull()).isTrue();
            assertThat(created.get("answeredAt").isNull()).isTrue();
            assertThat(created.get("version").asLong()).isEqualTo(1);
            assertThat(new TreeSet<>(created.get("customer").propertyNames())).containsExactly("id", "memberNo", "name");
            assertThat(created.get("customer").get("id").asString()).isEqualTo(Long.toString(inScope));
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE id=? AND branch_id=? AND customer_id=? AND created_by=? AND status='RECEIVED' AND source='PHONE'",
                    id(created), first, inScope, staff)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='inquiries' AND entity_id=? AND branch_id=?", id(created), first)).isEqualTo(1);

            // A colleague with no part in the registration answers it.
            var done = answered(c, created, "답변드립니다.");
            assertThat(done.get("status").asString()).isEqualTo("답변 완료");
            assertThat(done.get("answer").asString()).isEqualTo("답변드립니다.");
            assertThat(done.get("answeredBy").get("id").asString()).isEqualTo(Long.toString(colleague));
            assertThat(done.get("answeredAt").isNull()).isFalse();
            assertThat(done.get("version").asLong()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE id=? AND status='ANSWERED' AND answer_body=? AND answered_by=? AND answered_at IS NOT NULL",
                    id(created), "답변드립니다.", colleague)).isEqualTo(1);

            // The customer's contact data and memo reach no response: not create, detail, list or history.
            var bodies = List.of(response.body(), get(c, "/api/inquiries/" + id(created)).body(), get(c, "/api/inquiries").body(),
                    get(c, "/api/inquiries/" + id(created) + "/events").body());
            for (String raw : bodies) assertThat(raw).doesNotContain(PHONE, EMAIL_PART, MEMO, "phone", "email", "memo");
            var item = node(get(c, "/api/inquiries")).get("items").get(0);
            assertThat(new TreeSet<>(item.get("customer").propertyNames())).containsExactly("id", "memberNo", "name");
            assertThat(item.has("body")).isFalse();
            assertThat(get(c, "/api/inquiries/" + id(created)).body()).contains("문의 내용: 자리 문의");
        }
    }

    @Test void historyKeepsEveryChangeNewestFirstAndPages() throws Exception {
        try (var s = login(staff); var m = login(manager)) {
            var created = create(s, first, inScope, "이력 문의");
            var moving = ok(act(s, created, "status", "status", "처리 중"));
            var assigned = ok(act(m, moving, "assignee", "assigneeId", Long.toString(assignee)));
            answered(s, assigned, "첫 답변");

            var items = history(s, created);
            assertThat(items.size()).isEqualTo(4);
            assertThat(items.get(0).get("newStatus").asString()).isEqualTo("답변 완료");
            assertThat(items.get(0).get("actor").get("id").asString()).isEqualTo(Long.toString(staff));
            assertThat(items.get(1).get("oldStatus").asString()).isEqualTo(items.get(1).get("newStatus").asString()).isEqualTo("처리 중");
            assertThat(items.get(1).get("message").asString()).contains("문의 담당").contains("없음");
            assertThat(items.get(1).get("actor").get("id").asString()).isEqualTo(Long.toString(manager));
            assertThat(items.get(2).get("oldStatus").asString()).isEqualTo("접수");
            assertThat(items.get(2).get("newStatus").asString()).isEqualTo("처리 중");
            assertThat(items.get(3).get("oldStatus").isNull()).isTrue();
            assertThat(items.get(3).get("newStatus").asString()).isEqualTo("접수");
            for (var event : items) assertThat(new TreeSet<>(event.propertyNames())).containsExactly("actor", "at", "id", "message", "newStatus", "oldStatus");

            var page = node(get(s, "/api/inquiries/" + id(created) + "/events?page=2&pageSize=3"));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(4);
            assertThat(page.get("items").size()).isEqualTo(1);
        }
    }

    // ---- scope ------------------------------------------------------------------------------------------------

    @Test void aBranchOutsideTheRequestersScopeNeverShowsItsInquiries() throws Exception {
        JsonNode created;
        try (var s = login(staff)) { created = create(s, first, inScope, "범위 문의"); }
        try (var o = login(outsider)) {
            assertThat(get(o, "/api/inquiries/" + id(created)).statusCode()).isEqualTo(404);
            assertThat(get(o, "/api/inquiries/" + id(created) + "/events").statusCode()).isEqualTo(404);
            assertThat(act(o, created, "answer", "answer", "침입").statusCode()).isEqualTo(404);
            assertThat(act(o, created, "status", "status", "처리 중").statusCode()).isEqualTo(404);
            assertThat(act(o, created, "assignee", "assigneeId", Long.toString(outsider)).statusCode()).isEqualTo(404);
            assertThat(total(o, "")).isZero();
            assertThat(node(get(o, "/api/inquiries/summary")).at("/totals/total").asLong()).isZero();
            // Naming a branch that is not the requester's is refused, for reads, scoped reads and registration.
            assertThat(get(o, "/api/inquiries?scope=" + first).statusCode()).isEqualTo(403);
            assertThat(get(o, "/api/inquiries/summary?scope=" + first).statusCode()).isEqualTo(403);
            assertThat(createRaw(o, draft(first, inScope, "남의 지점"), key()).statusCode()).isEqualTo(403);
            // Even with the right branch in scope, the narrower scope hides it.
            assertThat(get(o, "/api/inquiries/" + id(created) + "?scope=" + second).statusCode()).isEqualTo(404);
        }
        assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=?", first)).isEqualTo(1);
        assertThat(events(id(created))).isEqualTo(1);
        try (var s = login(staff)) { assertThat(get(s, "/api/inquiries/" + id(created)).statusCode()).isEqualTo(200); }
    }

    @Test void revokedMembershipLosesAccessImmediately() throws Exception {
        JsonNode created;
        try (var s = login(staff)) { created = create(s, first, inScope, "회수 문의"); }
        try (var c = login(colleague)) {
            assertThat(get(c, "/api/inquiries/" + id(created)).statusCode()).isEqualTo(200);
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", colleague, first);
            assertThat(get(c, "/api/inquiries/" + id(created)).statusCode()).isEqualTo(404);
            assertThat(act(c, created, "answer", "answer", "회수 후").statusCode()).isEqualTo(404);
            assertThat(total(c, "")).isZero();
        }
    }

    // ---- the target customer ----------------------------------------------------------------------------------

    @Test void customerInTheRequestersLookupScopeIsAccepted() throws Exception {
        try (var s = login(staff)) {
            assertThat(create(s, first, inScope, "범위 안").at("/customer/id").asString()).isEqualTo(Long.toString(inScope));
        }
    }

    @Test void customerFoundByTheRequestersOwnExactLookupAtThisBranchIsAcceptedWithoutWideningTheScope() throws Exception {
        try (var s = login(staff)) {
            // Before the lookup nothing proves the requester may name this customer.
            assertThat(createRaw(s, draft(first, lookedUp, "조회 전"), key()).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/customers/" + lookedUp).statusCode()).isEqualTo(404);

            lookup(s, first, lookedUp);
            var created = create(s, first, lookedUp, "조회 후");
            assertThat(created.at("/customer/name").asString()).isEqualTo(token + "조회 고객");

            // Registering the inquiry gave no general access to the customer.
            assertThat(get(s, "/api/customers/" + lookedUp).statusCode()).isEqualTo(404);
            assertThat(node(get(s, "/api/customers?q=" + enc(token + "조회 고객"))).at("/meta/total").asLong()).isZero();
            assertThat(get(s, "/api/customers/" + other).statusCode()).isEqualTo(404);
        }
    }

    @Test void customerWithNeitherScopeNorLookupRecordIsNotFoundAndNothingIsSaved() throws Exception {
        try (var s = login(staff)) {
            var response = createRaw(s, draft(first, other, "근거 없음"), key());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).doesNotContain(token, PHONE);
            assertThat(createRaw(s, draft(first, 999_999_999L, "없는 고객"), key()).statusCode()).isEqualTo(404);
        }
        assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=?", first)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=?", staff)).isZero();
    }

    @Test void anotherEmployeesLookupDoesNotQualifyTheRequester() throws Exception {
        try (var s = login(staff); var c = login(colleague)) {
            lookup(s, first, lookedUp);
            assertThat(createRaw(c, draft(first, lookedUp, "남의 조회"), key()).statusCode()).isEqualTo(404);
            assertThat(create(s, first, lookedUp, "내 조회").get("status").asString()).isEqualTo("접수");
        }
    }

    @Test void aLookupAtAnotherBranchDoesNotQualifyThisBranch() throws Exception {
        try (var d = login(dual)) {
            // dual belongs to branches 1 and 2; the customer is registered at branch 3, which is outside dual's scope.
            lookup(d, second, thirdCustomer);
            assertThat(createRaw(d, draft(first, thirdCustomer, "다른 지점 조회"), key()).statusCode()).isEqualTo(404);
            assertThat(create(d, second, thirdCustomer, "같은 지점 조회").get("branchId").asString()).isEqualTo(Long.toString(second));
            // A customer registered at the other branch of the same person is inside the scope as usual.
            assertThat(create(d, first, other, "겸직 범위").get("status").asString()).isEqualTo("접수");
        }
    }

    @Test void ownerWithoutMembershipReachesEveryCustomerThroughTheScopeAndUnknownIdsAre404() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM branch_memberships WHERE employee_id=?", owner)).isZero();
        try (var o = login(owner)) {
            assertThat(create(o, first, thirdCustomer, "대표 등록").at("/customer/id").asString()).isEqualTo(Long.toString(thirdCustomer));
            assertThat(createRaw(o, draft(first, 999_999_999L, "없는 고객"), key()).statusCode()).isEqualTo(404);
        }
    }

    // ---- assignee ---------------------------------------------------------------------------------------------

    @Test void onlyTheCurrentAssigneeAndTheBranchManagerChangeTheAssignee() throws Exception {
        try (var s = login(staff); var c = login(colleague); var a = login(assignee); var m = login(manager); var o = login(owner)) {
            // No assignee: an ordinary employee, even the registrant, cannot assign; MANAGER and OWNER can.
            var open = create(s, first, inScope, "담당 없음");
            assertThat(reload(s, open).get("canChangeAssignee").asBoolean()).isFalse();
            assertThat(reload(m, open).get("canChangeAssignee").asBoolean()).isTrue();
            var denied = act(s, open, "assignee", "assigneeId", Long.toString(staff));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE id=? AND assigned_employee_id IS NULL", id(open))).isEqualTo(1);
            var byManager = ok(act(m, open, "assignee", "assigneeId", Long.toString(assignee)));
            assertThat(byManager.get("assignee").get("id").asString()).isEqualTo(Long.toString(assignee));
            assertThat(byManager.get("version").asLong()).isEqualTo(2);

            // With an assignee: that person may hand it on; another employee may not; the manager still may.
            assertThat(reload(a, byManager).get("canChangeAssignee").asBoolean()).isTrue();
            assertThat(reload(c, byManager).get("canChangeAssignee").asBoolean()).isFalse();
            assertThat(act(c, byManager, "assignee", "assigneeId", Long.toString(colleague)).statusCode()).isEqualTo(403);
            var handed = ok(act(a, byManager, "assignee", "assigneeId", Long.toString(colleague)));
            assertThat(handed.get("assignee").get("id").asString()).isEqualTo(Long.toString(colleague));
            // The previous assignee lost the right with the assignment.
            assertThat(act(a, handed, "assignee", "assigneeId", Long.toString(assignee)).statusCode()).isEqualTo(403);
            var released = ok(act(m, handed, "assignee", "assigneeId", null));
            assertThat(released.get("assignee").isNull()).isTrue();
            var byOwner = ok(act(o, released, "assignee", "assigneeId", Long.toString(manager)));
            assertThat(byOwner.get("assignee").get("id").asString()).isEqualTo(Long.toString(manager));

            // Each change is a history row whose status did not move, and the message tells who replaced whom.
            var items = history(m, open);
            assertThat(items.size()).isEqualTo(5);
            assertThat(items.get(0).get("message").asString()).contains("없음").contains("문의 관리자");
            assertThat(items.get(1).get("message").asString()).contains("동료 직원").contains("없음");
            assertThat(items.get(2).get("message").asString()).contains("문의 담당").contains("동료 직원");
            for (int index = 0; index < 4; index++) {
                assertThat(items.get(index).get("oldStatus").asString()).isEqualTo(items.get(index).get("newStatus").asString()).isEqualTo("접수");
            }
        }
    }

    @Test void onlyAnActiveEmployeeOfTheSameBranchCanBeAssigned() throws Exception {
        try (var m = login(manager); var s = login(staff)) {
            var created = create(s, first, inScope, "담당자 검증");
            for (long invalid : List.of(outsider, owner, 999_999_999L)) {
                var response = act(m, created, "assignee", "assigneeId", Long.toString(invalid));
                assertThat(response.statusCode()).as("assignee %d", invalid).isEqualTo(422);
                assertThat(code(response)).isEqualTo("INVALID_ASSIGNEE");
                // The creation path applies the same rule.
                var body = draft(first, inScope, "잘못된 담당");
                body.put("assigneeId", Long.toString(invalid));
                var rejected = createRaw(s, body, key());
                assertThat(rejected.statusCode()).isEqualTo(422);
                assertThat(code(rejected)).isEqualTo("INVALID_ASSIGNEE");
            }
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", colleague, first);
            assertThat(code(act(m, created, "assignee", "assigneeId", Long.toString(colleague)))).isEqualTo("INVALID_ASSIGNEE");
            jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", assignee);
            assertThat(code(act(m, created, "assignee", "assigneeId", Long.toString(assignee)))).isEqualTo("INVALID_ASSIGNEE");
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=? AND assigned_employee_id IS NOT NULL", first)).isZero();
            assertThat(events(id(created))).isEqualTo(1);
        }
    }

    @Test void registeringWithAnAssigneeAndAStartingStatus() throws Exception {
        try (var s = login(staff)) {
            var body = draft(first, inScope, "담당 지정 등록");
            body.put("assigneeId", Long.toString(assignee)); body.put("status", "처리 중"); body.put("source", "방문");
            var response = createRaw(s, body, key());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            var created = node(response);
            assertThat(created.get("status").asString()).isEqualTo("처리 중");
            assertThat(created.get("source").asString()).isEqualTo("방문");
            assertThat(created.at("/assignee/name").asString()).isEqualTo("문의 담당");
            var initial = history(s, created).get(0);
            assertThat(initial.get("oldStatus").isNull()).isTrue();
            assertThat(initial.get("newStatus").asString()).isEqualTo("처리 중");
        }
    }

    // ---- answer and status -----------------------------------------------------------------------------------

    @Test void aReAnswerReplacesTheAnswerAndTheReplacedOneStaysInTheHistory() throws Exception {
        try (var s = login(staff); var c = login(colleague)) {
            var created = create(s, first, inScope, "재답변");
            var one = answered(s, created, "첫 번째 답변");
            var two = answered(c, one, "두 번째 답변");
            assertThat(two.get("answer").asString()).isEqualTo("두 번째 답변");
            assertThat(two.get("answeredBy").get("id").asString()).isEqualTo(Long.toString(colleague));
            assertThat(two.get("status").asString()).isEqualTo("답변 완료");
            var items = history(s, created);
            assertThat(items.size()).isEqualTo(3);
            assertThat(items.get(0).get("message").asString()).isEqualTo("첫 번째 답변");
            assertThat(items.get(0).get("oldStatus").asString()).isEqualTo("답변 완료");
            assertThat(items.get(0).get("newStatus").asString()).isEqualTo("답변 완료");
            assertThat(items.get(1).get("message").isNull()).isTrue();
            assertThat(items.get(1).get("oldStatus").asString()).isEqualTo("접수");
            assertThat(items.get(1).get("newStatus").asString()).isEqualTo("답변 완료");

            // Status goes back to 처리 중 with the answer kept; answering again keeps that answer in the history too.
            var reopened = ok(act(s, two, "status", "status", "처리 중"));
            assertThat(reopened.get("status").asString()).isEqualTo("처리 중");
            assertThat(reopened.get("answer").asString()).isEqualTo("두 번째 답변");
            assertThat(reopened.get("answeredBy").get("id").asString()).isEqualTo(Long.toString(colleague));
            assertThat(reopened.get("answeredAt").isNull()).isFalse();
            var three = answered(s, reopened, "세 번째 답변");
            assertThat(three.get("answer").asString()).isEqualTo("세 번째 답변");
            var latest = history(s, created).get(0);
            assertThat(latest.get("message").asString()).isEqualTo("두 번째 답변");
            assertThat(latest.get("oldStatus").asString()).isEqualTo("처리 중");
            assertThat(latest.get("newStatus").asString()).isEqualTo("답변 완료");
        }
    }

    @Test void statusChangeAcceptsOnlyReceivedAndInProgressAndIgnoresARepeat() throws Exception {
        try (var s = login(staff)) {
            var created = create(s, first, inScope, "상태 전이");
            var moving = ok(act(s, created, "status", "status", "처리 중"));
            assertThat(moving.get("status").asString()).isEqualTo("처리 중");
            assertThat(moving.get("version").asLong()).isEqualTo(2);
            // 답변 완료 comes from answering only; the request is invalid and changes nothing.
            var refused = act(s, moving, "status", "status", "답변 완료");
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(code(refused)).isEqualTo("VALIDATION_FAILED");
            assertThat(act(s, moving, "status", "status", "완료").statusCode()).isEqualTo(422);
            assertThat(reload(s, moving).get("status").asString()).isEqualTo("처리 중");
            // The same status is not a change: same version, no history row.
            var same = ok(act(s, moving, "status", "status", "처리 중"));
            assertThat(same.get("version").asLong()).isEqualTo(2);
            assertThat(events(id(created))).isEqualTo(2);
            var back = ok(act(s, moving, "status", "status", "접수"));
            assertThat(back.get("status").asString()).isEqualTo("접수");
            assertThat(events(id(created))).isEqualTo(3);
        }
    }

    // ---- validation -------------------------------------------------------------------------------------------

    @Test void invalidInputIsRefusedWithoutChangingAnything() throws Exception {
        try (var s = login(staff)) {
            var created = create(s, first, inScope, "검증");
            String path = "/api/inquiries/" + id(created);
            // version is required and numeric; unknown or immutable fields are refused.
            assertThat(send(s, path + "/answer", Map.of("answer", "버전 없음")).statusCode()).isEqualTo(422);
            assertThat(send(s, path + "/answer", versioned(created, "answer", "x", "subject", "제목 변경")).statusCode()).isEqualTo(422);
            assertThat(send(s, path + "/status", versioned(created, "status", "처리 중", "branchId", "1")).statusCode()).isEqualTo(422);
            assertThat(act(s, created, "answer", "answer", "  ").statusCode()).isEqualTo(422);
            assertThat(act(s, created, "answer", "answer", "가".repeat(5001)).statusCode()).isEqualTo(422);
            assertThat(act(s, created, "answer").statusCode()).isEqualTo(422);
            // The assignee field may be null but must be present, and a non-string id is invalid.
            assertThat(send(s, path + "/assignee", Map.of("version", 1)).statusCode()).isEqualTo(422);
            assertThat(act(s, created, "assignee", "assigneeId", 5).statusCode()).isEqualTo(422);
            assertThat(act(s, created, "assignee", "assigneeId", "abc").statusCode()).isEqualTo(422);
            assertThat(reload(s, created).get("version").asLong()).isEqualTo(1);
            assertThat(events(id(created))).isEqualTo(1);

            // Registration.
            String key = key();
            var base = draft(first, inScope, "등록 검증");
            assertThat(createRaw(s, base, null).statusCode()).isEqualTo(422);
            assertThat(createRaw(s, base, "short").statusCode()).isEqualTo(422);
            for (var change : List.<Map<String, Object>>of(Map.of("subject", " "), Map.of("subject", "가".repeat(201)),
                    Map.of("body", ""), Map.of("body", "가".repeat(20001)), Map.of("source", "우편"), Map.of("status", "답변 완료"),
                    Map.of("status", "종료"), Map.of("customerId", "0"), Map.of("branchId", "abc"), Map.of("extra", "x"),
                    Map.of("createdBy", Long.toString(manager)))) {
                var body = new HashMap<>(base);
                body.putAll(change);
                assertThat(createRaw(s, body, key).statusCode()).as(change.toString()).isEqualTo(422);
            }
            var missing = new HashMap<>(base);
            missing.remove("customerId");
            assertThat(createRaw(s, missing, key).statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/inquiries?status=" + enc("끝")).statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/inquiries?sort=subject").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/inquiries?assigneeId=x").statusCode()).isEqualTo(422);
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=?", first)).isEqualTo(1);
        }
    }

    // ---- versions and request keys ----------------------------------------------------------------------------

    @Test void aStaleVersionIsAConflictForEveryCommandAndSavesNothing() throws Exception {
        try (var s = login(staff); var c = login(colleague); var m = login(manager)) {
            var created = create(s, first, inScope, "버전 충돌");
            var current = ok(act(c, created, "status", "status", "처리 중"));
            for (var stale : List.of(act(s, created, "answer", "answer", "낡은 답변"), act(s, created, "status", "status", "접수"),
                    act(m, created, "assignee", "assigneeId", Long.toString(assignee)))) {
                assertThat(stale.statusCode()).isEqualTo(409);
                var error = node(stale);
                assertThat(error.get("code").asString()).isEqualTo("VERSION_CONFLICT");
                assertThat(error.get("latestVersion").asLong()).isEqualTo(current.get("version").asLong());
                assertThat(error.get("reloadUrl").asString()).isEqualTo("/api/inquiries/" + id(created));
            }
            var after = reload(s, created);
            assertThat(after.get("status").asString()).isEqualTo("처리 중");
            assertThat(after.get("answer").isNull()).isTrue();
            assertThat(after.get("assignee").isNull()).isTrue();
            assertThat(events(id(created))).isEqualTo(2);
            // A permission failure outranks a version conflict, so a bystander learns nothing from the version.
            assertThat(act(s, created, "assignee", "assigneeId", Long.toString(staff)).statusCode()).isEqualTo(403);
        }
    }

    @Test void theSameRequestKeyReplaysAndADifferentInputUnderItIsRefused() throws Exception {
        try (var s = login(staff)) {
            String key = key();
            var body = draft(first, inScope, "멱등 문의");
            var one = createRaw(s, body, key);
            var two = createRaw(s, body, key);
            assertThat(one.statusCode()).isEqualTo(201);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(node(two).get("id").asString()).isEqualTo(node(one).get("id").asString());
            body.put("subject", "다른 제목");
            var changed = createRaw(s, body, key);
            assertThat(changed.statusCode()).isEqualTo(409);
            assertThat(code(changed)).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM inquiry_events e JOIN inquiries i ON i.id=e.inquiry_id WHERE i.branch_id=?", first)).isEqualTo(1);
        }
    }

    @Test void theSameKeyForADifferentRequesterIsAnIndependentRequest() throws Exception {
        try (var s = login(staff); var c = login(colleague)) {
            String key = key();
            var a = createRaw(s, draft(first, inScope, "키 공유 A"), key);
            var b = createRaw(c, draft(first, inScope, "키 공유 B"), key);
            assertThat(a.statusCode()).isEqualTo(201);
            assertThat(b.statusCode()).isEqualTo(201);
            assertThat(node(a).get("id").asString()).isNotEqualTo(node(b).get("id").asString());
        }
    }

    // ---- list, summary ----------------------------------------------------------------------------------------

    @Test void summaryCountsEqualTheListTotalsForTheSameFilters() throws Exception {
        long extra = customer("추가 고객", second);
        try (var o = login(owner)) {
            var a = create(o, first, inScope, "수치 하나");
            var b = create(o, first, inScope, "수치 둘");
            var c = create(o, first, other, "수치 셋");
            var d = create(o, second, other, "수치 넷");
            var e = create(o, second, extra, "수치 다섯");
            create(o, second, extra, "수치 여섯");
            ok(act(o, b, "status", "status", "처리 중"));
            var assigned = ok(act(o, c, "assignee", "assigneeId", Long.toString(assignee)));
            answered(o, assigned, "답변");
            answered(o, d, "답변");
            ok(act(o, e, "status", "status", "처리 중"));

            var filters = List.of("", "assigneeId=" + assignee, "customerId=" + other, "q=" + enc("수치 "), "q=" + enc(token + "추가"),
                    "q=" + enc("없는 검색어"), "customerId=" + extra + "&q=" + enc("여섯"));
            for (String scope : List.of("", "scope=" + first, "scope=" + second)) {
                for (String filter : filters) {
                    String query = join(scope, filter);
                    var summary = node(get(o, "/api/inquiries/summary" + (query.isEmpty() ? "" : "?" + query)));
                    long received = total(o, join(query, "status=" + enc("접수"))), progress = total(o, join(query, "status=" + enc("처리 중"))),
                            answered = total(o, join(query, "status=" + enc("답변 완료")));
                    var totals = summary.get("totals");
                    assertThat(totals.get("total").asLong()).as(query).isEqualTo(total(o, query));
                    assertThat(totals.get("received").asLong()).as(query).isEqualTo(received);
                    assertThat(totals.get("inProgress").asLong()).as(query).isEqualTo(progress);
                    assertThat(totals.get("answered").asLong()).as(query).isEqualTo(answered);
                    assertThat(totals.get("unresolved").asLong()).as(query).isEqualTo(received + progress);
                    assertThat(totals.get("total").asLong()).as(query).isEqualTo(received + progress + answered);
                    assertThat(summary.get("asOf").asString()).isNotBlank();
                    long rows = 0;
                    for (var branch : summary.get("branches")) {
                        long inBranch = total(o, join("scope=" + branch.get("branchId").asString(), filter));
                        assertThat(branch.get("total").asLong()).as(query + " branch " + branch).isEqualTo(inBranch);
                        assertThat(branch.get("unresolved").asLong()).isEqualTo(branch.get("received").asLong() + branch.get("inProgress").asLong());
                        rows += branch.get("total").asLong();
                    }
                    assertThat(rows).as(query).isEqualTo(totals.get("total").asLong());
                }
            }
            // The fixture really covers the three states, and the filters narrow as named.
            var all = node(get(o, "/api/inquiries/summary?scope=" + first + "&q=" + enc("수치 "))).get("totals");
            assertThat(all.get("total").asLong()).isEqualTo(3);
            assertThat(all.get("unresolved").asLong()).isEqualTo(2);
            assertThat(total(o, "q=" + enc(token + "추가"))).isEqualTo(2);
            assertThat(total(o, "assigneeId=" + assignee)).isEqualTo(1);
            assertThat(id(a)).isPositive();
        }
    }

    @Test void listSortsPagesAndAnEmployeeSeesOnlyTheirBranches() throws Exception {
        JsonNode older, newer;
        try (var s = login(staff); var o = login(outsider)) {
            older = create(s, first, inScope, "정렬 하나");
            newer = create(s, first, inScope, "정렬 둘");
            create(o, second, other, "다른 지점 문의");
            jdbc.update("UPDATE inquiries SET created_at='2030-01-01 00:00:00.000000' WHERE id=?", id(older));
            jdbc.update("UPDATE inquiries SET created_at='2030-01-02 00:00:00.000000' WHERE id=?", id(newer));
            var desc = node(get(s, "/api/inquiries"));
            assertThat(desc.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(desc.get("items").get(0).get("id").asString()).isEqualTo(newer.get("id").asString());
            var asc = node(get(s, "/api/inquiries?direction=asc&pageSize=1"));
            assertThat(asc.get("items").get(0).get("id").asString()).isEqualTo(older.get("id").asString());
            assertThat(asc.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(node(get(s, "/api/inquiries?page=2&pageSize=1&direction=asc")).get("items").get(0).get("id").asString()).isEqualTo(newer.get("id").asString());
            assertThat(node(get(s, "/api/inquiries?sort=updatedAt")).get("items").size()).isEqualTo(2);
            assertThat(total(o, "")).isEqualTo(1);
            assertThat(total(s, "customerId=" + other)).isZero();
        }
    }

    // ---- real concurrency -----------------------------------------------------------------------------------

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
            for (var future : running) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }

    @Test void concurrentAnswersOnTheSameVersionLeaveExactlyOneWinner() throws Exception {
        JsonNode created;
        try (var s = login(staff)) { created = create(s, first, inScope, "동시 답변"); }
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 6; index++) sessions.add(login(index % 2 == 0 ? colleague : staff));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < sessions.size(); index++) {
                var session = sessions.get(index);
                String text = "동시 답변 " + index;
                calls.add(() -> act(session, created, "answer", "answer", text));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsExactly(200, 409, 409, 409, 409, 409);
            for (var result : results) if (result.statusCode() == 409) assertThat(code(result)).isEqualTo("VERSION_CONFLICT");
            String stored = jdbc.queryForObject("SELECT answer_body FROM inquiries WHERE id=?", String.class, id(created));
            var winner = results.stream().filter(result -> result.statusCode() == 200).findFirst().orElseThrow();
            assertThat(node(winner).get("answer").asString()).isEqualTo(stored);
            assertThat(events(id(created))).isEqualTo(2);
            assertThat(count("SELECT version FROM inquiries WHERE id=?", id(created))).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }

    @Test void anAnswerRacingAnAssigneeChangeOnTheSameVersionAppliesExactlyOne() throws Exception {
        JsonNode created;
        try (var s = login(staff)) { created = create(s, first, inScope, "경합"); }
        try (var s = login(colleague); var m = login(manager)) {
            var results = race(List.<Callable<HttpResponse<String>>>of(() -> act(s, created, "answer", "answer", "경합 답변"),
                    () -> act(m, created, "assignee", "assigneeId", Long.toString(assignee))));
            assertThat(statuses(results)).containsExactly(200, 409);
            boolean answeredFirst = jdbc.queryForObject("SELECT answer_body IS NOT NULL FROM inquiries WHERE id=?", Boolean.class, id(created));
            boolean assignedFirst = jdbc.queryForObject("SELECT assigned_employee_id IS NOT NULL FROM inquiries WHERE id=?", Boolean.class, id(created));
            assertThat(answeredFirst ^ assignedFirst).isTrue();
            assertThat(events(id(created))).isEqualTo(2);
            assertThat(count("SELECT version FROM inquiries WHERE id=?", id(created))).isEqualTo(1);

            // Whoever lost reloads and succeeds with the new version; the end state holds both changes.
            var current = reload(s, created);
            if (answeredFirst) ok(act(m, current, "assignee", "assigneeId", Long.toString(assignee)));
            else answered(s, current, "경합 답변");
            var end = reload(s, created);
            assertThat(end.get("status").asString()).isEqualTo("답변 완료");
            assertThat(end.at("/assignee/id").asString()).isEqualTo(Long.toString(assignee));
            assertThat(events(id(created))).isEqualTo(3);
        }
    }

    @Test void concurrentRegistrationsWithTheSameKeyCreateOneInquiry() throws Exception {
        String key = key();
        var body = draft(first, inScope, "동시 등록");
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 6; index++) sessions.add(login(staff));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (var session : sessions) calls.add(() -> createRaw(session, body, key));
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(201);
            assertThat(results.stream().map(result -> node(result).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM inquiries WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND idempotency_key=?", staff, key)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM inquiry_events e JOIN inquiries i ON i.id=e.inquiry_id WHERE i.branch_id=?", first)).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }
}
