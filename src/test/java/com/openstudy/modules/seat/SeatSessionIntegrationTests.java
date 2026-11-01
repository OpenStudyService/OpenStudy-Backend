package com.openstudy.modules.seat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.repository.SeatSessionRepository;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/** Issue #62: HTTP and MySQL behaviour of the exact-match lookup, check-in, check-out and deduction (seat-usage-api.md). */
@MySqlIntegrationTest
@Import(SeatSessionIntegrationTests.TimeConfiguration.class)
class SeatSessionIntegrationTests {
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
    @MockitoSpyBean SeatSessionRepository sessions;
    @LocalServerPort int port;

    long owner, staff, otherStaff, outsider, first, second;
    String timeCategory, periodCategory, storedCategory;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    int seatNumber;

    /** null follows the real clock. Tests that need exact instants pin it. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock seatClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.fixed = null;
        owner = person("OWNER", "사장");
        staff = person("EMPLOYEE", "첫지점 직원"); otherStaff = person("EMPLOYEE", "첫지점 동료"); outsider = person("EMPLOYEE", "둘째지점 직원");
        first = branch("이용 첫 지점"); second = branch("이용 둘째 지점");
        member(staff, first); member(otherStaff, first); member(outsider, second);
        timeCategory = category("시간권"); periodCategory = category("기간권"); storedCategory = category("정액권");
    }

    @AfterEach void clean() {
        Mockito.reset(sessions);
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

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@usage.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("usage-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "usage-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
    }

    String category(String kind) {
        var made = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류 " + kind, "kind", kind), "usage-fixture");
        return json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    long seat(long branch) {
        String no = "U-" + ++seatNumber;
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'A',?,'GENERAL','AVAILABLE')", branch, no);
        return jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", Long.class, branch, no);
    }

    long customer(long branch, String name, String phone) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name); body.put("phone", phone);
        body.put("email", "secret@customer.test"); body.put("memo", "내부 메모");
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "usage-fixture").id());
        customerIds.add(id);
        return id;
    }

    long customer(long branch, String name) { return customer(branch, name, "010" + (10_000_000 + (int) (Math.random() * 89_999_999))); }

    String memberNo(long customer) { return jdbc.queryForObject("SELECT member_no FROM customers WHERE id=?", String.class, customer); }

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

    /** A real sale, so the pass carries the purchase-time snapshot and every foreign key is genuine. */
    long pass(long customer, long branch, Map<String, Object> terms) {
        String kind = (String) terms.get("kind");
        var product = new LinkedHashMap<String, Object>();
        product.put("name", token + " " + kind); product.put("priceKrw", 15000L); product.put("terms", terms); product.put("status", "판매 중");
        product.put("categoryId", "시간권".equals(kind) ? timeCategory : "기간권".equals(kind) ? periodCategory : storedCategory);
        product.put("branchIds", List.of(Long.toString(branch)));
        String productId = products.create(owner, "all", key(), product, "usage-fixture").id();
        var sale = new LinkedHashMap<String, Object>();
        sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(branch));
        sale.put("productId", productId); sale.put("method", "카드");
        return Long.parseLong(payments.create(owner, "all", key(), sale, "usage-fixture").passId());
    }

    long timePass(long customer, long branch) { return pass(customer, branch, time(600, null)); }

    void expire(long pass, Instant at) { jdbc.update("UPDATE passes SET expires_at=? WHERE id=?", Timestamp.from(at), pass); }

    static String key() { return UUID.randomUUID().toString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    Instant at(String sql, Object... args) { return jdbc.queryForObject(sql, Timestamp.class, args).toInstant(); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "usage-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> checkIn(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/seat-sessions", session.token(), body, key);
    }

    HttpResponse<String> checkOut(Session session, long id, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/seat-sessions/" + id + "/check-out", session.token(), body, key);
    }

    HttpResponse<String> lookup(Session session, Object body) throws Exception {
        return http(session.client(), "POST", "/api/customer-lookups/check-in", session.token(), body, null);
    }

    static Map<String, Object> entry(long seat, long customer, long pass) {
        var body = new LinkedHashMap<String, Object>();
        body.put("seatId", Long.toString(seat)); body.put("customerId", Long.toString(customer)); body.put("passId", Long.toString(pass));
        return body;
    }

    long entered(Session session, long seat, long customer, long pass) throws Exception {
        var response = checkIn(session, entry(seat, customer, pass), key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response).get("id").asLong();
    }

    JsonNode left(Session session, long id) throws Exception {
        var response = checkOut(session, id, Map.of(), key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(code(response)).isEqualTo(code);
    }

    Map<String, Object> byPhone(long branch, String phone) { return Map.of("branchId", Long.toString(branch), "phone", phone); }
    Map<String, Object> byMemberNo(long branch, String memberNo) { return Map.of("branchId", Long.toString(branch), "memberNo", memberNo); }

    // ---- the contract's examples ----------------------------------------------------------------------------

    record Example(String name, Map<String, Object> terms, String out, Instant expires, long used, long deductedMinutes, long deductedKrw,
            long overageMinutes, long overageKrw, Long remainingMinutes, Long remainingKrw, String passStatus, Long ledgerMinutes, Long ledgerKrw) {}

    static Instant time(String clockTime) { return Instant.parse("2026-11-02T" + clockTime + "Z"); }

    /** seat-usage-api.md "예시": all eight rows, with the values calculated by hand in the #60 PR. */
    @Test void everyContractExampleGivesTheHandCalculatedResult() throws Exception {
        List<Example> examples = List.of(
                new Example("1 시간권 잔량 충분", time(120, null), "11:30:20", null, 91, 91, 0, 0, 0, 29L, null, "ACTIVE", -91L, 0L),
                new Example("2 시간권 잔량 부족", time(60, null), "11:30:20", null, 91, 60, 0, 31, 0, 0L, null, "EXHAUSTED", -60L, 0L),
                new Example("3 정액권 정수 금액", stored(10_000, 1_500, null), "11:30:20", null, 91, 0, 2_275, 0, 0, null, 7_725L, "ACTIVE", 0L, -2_275L),
                new Example("4 정액권 금액 올림", stored(10_000, 1_000, null), "11:30:20", null, 91, 0, 1_517, 0, 0, null, 8_483L, "ACTIVE", 0L, -1_517L),
                new Example("5 정액권 잔액 부족", stored(500, 1_000, null), "11:30:20", null, 91, 0, 500, 0, 1_017, null, 0L, "EXHAUSTED", 0L, -500L),
                new Example("6 기간권 만료 걸침", period(30), "11:00:00", time("10:30:30"), 60, 0, 0, 29, 0, null, null, "ACTIVE", null, null),
                new Example("7 시간권 만료 걸침", time(120, null), "11:00:00", time("10:30:30"), 60, 31, 0, 29, 0, 89L, null, "ACTIVE", -31L, 0L),
                new Example("8 즉시 퇴실", time(120, null), "10:00:00", null, 0, 0, 0, 0, 0, 120L, null, "ACTIVE", null, null));
        for (var example : examples) {
            clock.fixed = TEN.minusSeconds(3600);
            long customer = customer(first, "예시 고객");
            long pass = pass(customer, first, example.terms());
            if (example.expires() != null) expire(pass, example.expires());
            long seat = seat(first);
            clock.fixed = TEN;
            long id;
            try (var s = login(staff)) {
                var entered = checkIn(s, entry(seat, customer, pass), key());
                assertThat(entered.statusCode()).as(example.name()).isEqualTo(201);
                id = node(entered).get("id").asLong();
            }
            clock.fixed = time(example.out());
            try (var s = login(staff)) {
                var result = left(s, id);
                assertThat(result.get("status").asString()).as(example.name()).isEqualTo("종료");
                assertThat(result.get("usedMinutes").asLong()).as(example.name()).isEqualTo(example.used());
                assertThat(result.get("deductedMinutes").asLong()).as(example.name()).isEqualTo(example.deductedMinutes());
                assertThat(result.get("deductedKrw").asLong()).as(example.name()).isEqualTo(example.deductedKrw());
                assertThat(result.get("overageMinutes").asLong()).as(example.name()).isEqualTo(example.overageMinutes());
                assertThat(result.get("overageKrw").asLong()).as(example.name()).isEqualTo(example.overageKrw());
                assertThat(result.get("passRemainingMinutes").isNull() ? null : result.get("passRemainingMinutes").asLong()).as(example.name()).isEqualTo(example.remainingMinutes());
                assertThat(result.get("passRemainingCreditKrw").isNull() ? null : result.get("passRemainingCreditKrw").asLong()).as(example.name()).isEqualTo(example.remainingKrw());
                assertThat(result.get("checkedInAt").asString()).isEqualTo("2026-11-02T10:00:00Z");
                assertThat(result.get("checkedOutAt").asString()).isEqualTo("2026-11-02T" + example.out() + "Z");
            }
            var row = jdbc.queryForMap("SELECT status,billed_minutes,billed_krw,overage_minutes,overage_krw FROM seat_sessions WHERE id=?", id);
            assertThat(row.get("status")).as(example.name()).isEqualTo("CLOSED");
            assertThat(((Number) row.get("billed_minutes")).longValue()).as(example.name()).isEqualTo(example.used());
            assertThat(((Number) row.get("billed_krw")).longValue()).as(example.name()).isEqualTo(example.deductedKrw());
            assertThat(((Number) row.get("overage_minutes")).longValue()).as(example.name()).isEqualTo(example.overageMinutes());
            assertThat(((Number) row.get("overage_krw")).longValue()).as(example.name()).isEqualTo(example.overageKrw());
            assertThat(jdbc.queryForObject("SELECT remaining_minutes FROM passes WHERE id=?", Long.class, pass)).as(example.name()).isEqualTo(example.remainingMinutes());
            assertThat(jdbc.queryForObject("SELECT remaining_credit_krw FROM passes WHERE id=?", Long.class, pass)).as(example.name()).isEqualTo(example.remainingKrw());
            assertThat(text("SELECT status FROM passes WHERE id=?", pass)).as(example.name()).isEqualTo(example.passStatus());
            if (example.ledgerMinutes() == null) {
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).as(example.name()).isZero();
            } else {
                var ledger = jdbc.queryForMap("SELECT * FROM pass_balance_events WHERE pass_id=?", pass);
                assertThat(ledger.get("kind")).isEqualTo("USE");
                assertThat(((Number) ledger.get("delta_minutes")).longValue()).as(example.name()).isEqualTo(example.ledgerMinutes());
                assertThat(((Number) ledger.get("delta_krw")).longValue()).as(example.name()).isEqualTo(example.ledgerKrw());
                assertThat(ledger.get("event_key")).isEqualTo("USE-" + id);
                assertThat(((Number) ledger.get("seat_session_id")).longValue()).isEqualTo(id);
                assertThat(((Number) ledger.get("actor_employee_id")).longValue()).isEqualTo(staff);
            }
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", seat)).as(example.name()).isZero();
        }
    }

    @Test void theChargeUsesThePurchaseTimeRateAfterTheProductChanges() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "정액 고객");
        long pass = pass(customer, first, stored(10_000, 1_000, null));
        jdbc.update("UPDATE products SET hourly_rate_krw=9000 WHERE id=(SELECT product_id FROM passes WHERE id=?)", pass);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            clock.fixed = TEN.plusSeconds(30 * 60);
            assertThat(left(s, id).get("deductedKrw").asLong()).isEqualTo(500);
        }
    }

    // ---- check-in -------------------------------------------------------------------------------------------

    @Test void checkInCreatesOneSessionWithActorReasonAuditAndEventsAndTheSameKeyReplaysIt() throws Exception {
        long customer = customer(first, "입실 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        var body = entry(seat, customer, pass);
        body.put("reason", "  QR 미연동 현장 배정 ");
        String key = key();
        try (var s = login(staff)) {
            var made = checkIn(s, body, key);
            assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
            var session = node(made);
            long id = session.get("id").asLong();
            assertThat(made.headers().firstValue("Location")).contains("/api/seat-sessions/" + id);
            assertThat(session.get("status").asString()).isEqualTo("이용 중");
            assertThat(session.get("version").asLong()).isEqualTo(1);
            assertThat(session.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(session.at("/seat/id").asString()).isEqualTo(Long.toString(seat));
            assertThat(session.at("/seat/zone").asString()).isEqualTo("A");
            assertThat(session.at("/customer/id").asString()).isEqualTo(Long.toString(customer));
            assertThat(session.at("/customer").size()).isEqualTo(3);
            assertThat(session.at("/pass/kind").asString()).isEqualTo("시간권");
            assertThat(session.at("/pass/purchaseBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(session.get("checkedOutAt").isNull()).isTrue();
            assertThat(session.get("usedMinutes").asLong()).isZero();
            assertThat(session.get("passRemainingMinutes").asLong()).isEqualTo(600);
            assertThat(session.get("passRemainingCreditKrw").isNull()).isTrue();
            assertThat(session.at("/checkedInBy/id").asString()).isEqualTo(Long.toString(staff));
            assertThat(session.at("/checkedInBy/name").asString()).isEqualTo("첫지점 직원");
            assertThat(session.get("checkInReason").asString()).isEqualTo("QR 미연동 현장 배정");
            assertThat(session.get("moves").isEmpty()).isTrue();

            var row = jdbc.queryForMap("SELECT * FROM seat_sessions WHERE id=?", id);
            assertThat(row.get("checked_in_at")).isEqualTo(row.get("effective_checked_in_at"));
            assertThat(((Number) row.get("exception_actor_id")).longValue()).isEqualTo(staff);
            assertThat(row.get("exception_reason")).isEqualTo("QR 미연동 현장 배정");
            assertThat((String) row.get("start_request_key")).startsWith("SIN-").hasSizeLessThanOrEqualTo(120).doesNotContain(key);
            assertThat(row.get("end_request_key")).isNull();

            var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=?", id);
            assertThat(audit.get("action")).isEqualTo("CHECKED_IN");
            assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(staff);
            assertThat(((Number) audit.get("branch_id")).longValue()).isEqualTo(first);
            assertThat(audit.get("reason")).isEqualTo("QR 미연동 현장 배정");
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, seat)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seat-sessions' AND entity_id=?", first, id)).isEqualTo(1);

            // The seat now shows the session; the detail agrees with the creation response.
            var shown = node(get(s, "/api/seats/" + seat));
            assertThat(shown.get("status").asString()).isEqualTo("사용 중");
            assertThat(shown.at("/activeSession/id").asString()).isEqualTo(Long.toString(id));
            assertThat(node(get(s, "/api/seat-sessions/" + id))).isEqualTo(session);

            // Same key, same input: the first status and body, and nothing new anywhere.
            var replay = checkIn(s, body, key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay)).isEqualTo(session);
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='seat-sessions' AND entity_id=?", id)).isEqualTo(1);

            // Same key on the same seat, different input.
            var other = entry(seat, customer, pass);
            other.put("reason", "다른 사유");
            refused(checkIn(s, other, key), 409, "IDEMPOTENCY_CONFLICT");
        }
    }

    @Test void checkInInputIsValidatedStrictlyBeforeAnythingElseAndCarriesNoMinutesOrAmounts() throws Exception {
        long customer = customer(first, "검증 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        try (var s = login(staff)) {
            refused(checkIn(s, entry(seat, customer, pass), null), 422, "VALIDATION_FAILED");
            refused(checkIn(s, entry(seat, customer, pass), "short"), 422, "VALIDATION_FAILED");
            refused(checkIn(s, entry(seat, customer, pass), "k".repeat(129)), 422, "VALIDATION_FAILED");
            for (String field : List.of("minutes", "amountKrw", "deductedMinutes", "overageKrw", "checkedInAt")) {
                var body = entry(seat, customer, pass);
                body.put(field, 1);
                refused(checkIn(s, body, key()), 422, "VALIDATION_FAILED");
            }
            var longReason = entry(seat, customer, pass);
            longReason.put("reason", "가".repeat(501));
            refused(checkIn(s, longReason, key()), 422, "VALIDATION_FAILED");
            var nullReason = entry(seat, customer, pass);
            nullReason.put("reason", null);
            refused(checkIn(s, nullReason, key()), 422, "VALIDATION_FAILED");
            var badId = entry(seat, customer, pass);
            badId.put("seatId", "0");
            refused(checkIn(s, badId, key()), 422, "VALIDATION_FAILED");
            badId.put("seatId", seat);
            refused(checkIn(s, badId, key()), 422, "VALIDATION_FAILED");
            // CSRF applies to every state change.
            assertThat(http(s.client(), "POST", "/api/seat-sessions", null, entry(seat, customer, pass), key()).statusCode()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isZero();
            // A blank reason means no reason; 500 characters are allowed.
            var blank = entry(seat, customer, pass);
            blank.put("reason", "   ");
            var made = checkIn(s, blank, key());
            assertThat(made.statusCode()).isEqualTo(201);
            assertThat(node(made).get("checkInReason").isNull()).isTrue();
        }
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(http(anonymous, "GET", "/api/seat-sessions/1", null, null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test void checkInFollowsTheContractTableAndARefusalLeavesNothingBehind() throws Exception {
        long customer = customer(first, "거절 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        long blockedSeat = seat(first);
        jdbc.update("UPDATE seats SET status='BLOCKED' WHERE id=?", blockedSeat);
        long stranger = customer(first, "다른 고객");
        long strangersPass = timePass(stranger, first);
        try (var s = login(staff)) {
            // Seat missing, or at a branch the requester does not belong to.
            refused(checkIn(s, entry(999_999_999L, customer, pass), key()), 404, "NOT_FOUND");
            refused(checkIn(s, entry(seat(second), customer, pass), key()), 404, "NOT_FOUND");
            refused(checkIn(s, entry(blockedSeat, customer, pass), key()), 409, "SEAT_BLOCKED");
            // Pass missing, or another customer's.
            refused(checkIn(s, entry(seat, customer, 999_999_999L), key()), 404, "NOT_FOUND");
            refused(checkIn(s, entry(seat, customer, strangersPass), key()), 404, "NOT_FOUND");
            refused(checkIn(s, entry(seat, 999_999_999L, pass), key()), 404, "NOT_FOUND");

            jdbc.update("UPDATE customers SET status='RESTRICTED' WHERE id=?", customer);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='INACTIVE' WHERE id=?", customer);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='NORMAL' WHERE id=?", customer);

            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);

            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isZero();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND actor_employee_id=?", staff)).isZero();
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=?", staff)).isZero();

            long occupying = entered(s, seat, customer, pass);
            long otherSeat = seat(first);
            refused(checkIn(s, entry(seat, stranger, strangersPass), key()), 409, "SEAT_OCCUPIED");
            refused(checkIn(s, entry(otherSeat, customer, pass), key()), 409, "CUSTOMER_ALREADY_CHECKED_IN");
            assertThat(occupying).isPositive();
        }
    }

    @Test void aCustomerCheckedInAtAnotherBranchCannotCheckInAgain() throws Exception {
        long customer = customer(first, "두 지점 고객");
        long pass = timePass(customer, first);
        long secondPass = timePass(customer, first);
        try (var s = login(staff)) { entered(s, seat(first), customer, pass); }
        try (var s = login(outsider)) {
            refused(checkIn(s, entry(seat(second), customer, secondPass), key()), 409, "CUSTOMER_ALREADY_CHECKED_IN");
        }
    }

    @Test void theRefusalsAreCheckedInTheContractOrder() throws Exception {
        long customer = customer(first, "순서 고객");
        long seat = seat(first);
        long pass = timePass(customer, first);
        try (var s = login(staff)) {
            // Stopped branch wins over a stopped seat.
            jdbc.update("UPDATE seats SET status='BLOCKED' WHERE id=?", seat);
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);
            // A stopped seat wins over a pass that does not exist.
            refused(checkIn(s, entry(seat, customer, 999_999_999L), key()), 409, "SEAT_BLOCKED");
            jdbc.update("UPDATE seats SET status='AVAILABLE' WHERE id=?", seat);
            // A missing pass wins over the customer's status.
            jdbc.update("UPDATE customers SET status='RESTRICTED' WHERE id=?", customer);
            refused(checkIn(s, entry(seat, customer, 999_999_999L), key()), 404, "NOT_FOUND");
            // The customer's status wins over the pass's.
            jdbc.update("UPDATE passes SET status='REFUNDED' WHERE id=?", pass);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='NORMAL' WHERE id=?", customer);
            // Refunded wins over expired, expired over exhausted.
            expire(pass, Instant.now().minusSeconds(60));
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "PASS_REFUNDED");
            jdbc.update("UPDATE passes SET status='EXHAUSTED', remaining_minutes=0 WHERE id=?", pass);
            refused(checkIn(s, entry(seat, customer, pass), key()), 409, "PASS_EXPIRED");
        }
    }

    @Test void onlyAnActiveUnexpiredPassWithABalanceChecksIn() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "이용권 고객");
        long seat = seat(first);
        try (var s = login(staff)) {
            long refunded = timePass(customer, first);
            jdbc.update("UPDATE passes SET status='REFUNDED' WHERE id=?", refunded);
            refused(checkIn(s, entry(seat, customer, refunded), key()), 409, "PASS_REFUNDED");

            // Stored status is still ACTIVE: expiry is judged from the clock, at the boundary [start, expiry).
            long expired = timePass(customer, first);
            expire(expired, TEN);
            refused(checkIn(s, entry(seat, customer, expired), key()), 409, "PASS_EXPIRED");
            assertThat(text("SELECT status FROM passes WHERE id=?", expired)).isEqualTo("ACTIVE");

            long storedExpired = pass(customer, first, stored(1000, 600, 30L));
            expire(storedExpired, TEN.minusNanos(1000));
            refused(checkIn(s, entry(seat, customer, storedExpired), key()), 409, "PASS_EXPIRED");

            // Issued earlier: ck_passes_period_activation wants the expiry after the start.
            clock.fixed = TEN.minusSeconds(60);
            long periodExpired = pass(customer, first, period(30));
            clock.fixed = TEN;
            expire(periodExpired, TEN);
            refused(checkIn(s, entry(seat, customer, periodExpired), key()), 409, "PASS_EXPIRED");

            long exhausted = timePass(customer, first);
            jdbc.update("UPDATE passes SET status='EXHAUSTED', remaining_minutes=0 WHERE id=?", exhausted);
            refused(checkIn(s, entry(seat, customer, exhausted), key()), 409, "PASS_EXHAUSTED");

            // Zero balance is exhausted even if the stored status has not caught up.
            long zeroMinutes = timePass(customer, first);
            jdbc.update("UPDATE passes SET remaining_minutes=0 WHERE id=?", zeroMinutes);
            refused(checkIn(s, entry(seat, customer, zeroMinutes), key()), 409, "PASS_EXHAUSTED");
            long zeroCredit = pass(customer, first, stored(1000, 600, null));
            jdbc.update("UPDATE passes SET remaining_credit_krw=0 WHERE id=?", zeroCredit);
            refused(checkIn(s, entry(seat, customer, zeroCredit), key()), 409, "PASS_EXHAUSTED");
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isZero();

            // Never issued any more, but still a value of the V1 enum: only ACTIVE checks in.
            long notStarted = pass(customer, first, period(30));
            jdbc.update("UPDATE passes SET status='NOT_STARTED', started_at=NULL, expires_at=NULL WHERE id=?", notStarted);
            refused(checkIn(s, entry(seat, customer, notStarted), key()), 409, "INVALID_STATE");

            // One microsecond before expiry is still inside the period.
            long lastMoment = timePass(customer, first);
            expire(lastMoment, TEN.plusNanos(1000));
            assertThat(checkIn(s, entry(seat, customer, lastMoment), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void passesWithoutExpiryNeverExpireAndAPeriodPassIgnoresBalance() throws Exception {
        clock.fixed = TEN.plusSeconds(3_650L * 24 * 60 * 60);
        long timeCustomer = customer(first, "무기한 시간권");
        long storedCustomer = customer(first, "무기한 정액권");
        long periodCustomer = customer(first, "기간권");
        long timePass = pass(timeCustomer, first, time(60, null));
        long storedPass = pass(storedCustomer, first, stored(1000, 600, null));
        long periodPass = pass(periodCustomer, first, period(1));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM passes WHERE id=?", Timestamp.class, timePass)).isNull();
        clock.fixed = clock.fixed.plusSeconds(10L * 365 * 24 * 60 * 60);
        expire(periodPass, clock.fixed.plusSeconds(60));
        try (var s = login(staff)) {
            assertThat(checkIn(s, entry(seat(first), timeCustomer, timePass), key()).statusCode()).isEqualTo(201);
            assertThat(checkIn(s, entry(seat(first), storedCustomer, storedPass), key()).statusCode()).isEqualTo(201);
            assertThat(checkIn(s, entry(seat(first), periodCustomer, periodPass), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void aCustomerCheckedInCanNoLongerBeRefundedAndThePassCountsAsUsedOnceTheStayEnds() throws Exception {
        long customer = customer(first, "환불 고객");
        long pass = timePass(customer, first);
        long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", pass);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            for (int stage = 0; stage < 2; stage++) {
                assertThatThrownBy(() -> payments.refund(owner, payment, "all", key(), Map.of("reason", "변심"), "usage-fixture"))
                        .isInstanceOfSatisfying(ApiException.class, refusal -> assertThat(refusal.error("x").code()).isEqualTo("PASS_ALREADY_USED"));
                if (stage == 0) left(s, id);
            }
        }
    }

    // ---- other branches (M5-D01·D05·D06) --------------------------------------------------------------------

    @Test void aCustomerOfAnotherBranchIsFoundCheckedInAndOutThereWithoutEverJoiningItsCustomerList() throws Exception {
        long customer = customer(second, "타지점 고객");
        long pass = timePass(customer, second);
        long seat = seat(first);
        String memberNo = memberNo(customer);
        // A stopped purchase branch does not stop the pass: it works at every active branch (M5-D01).
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", second);
        try (var s = login(staff)) {
            assertThat(get(s, "/api/customers/" + customer).statusCode()).isEqualTo(404);
            var found = node(lookup(s, byMemberNo(first, memberNo)));
            assertThat(found.at("/items/0/customer/id").asString()).isEqualTo(Long.toString(customer));
            assertThat(found.at("/items/0/usablePasses/0/id").asString()).isEqualTo(Long.toString(pass));
            assertThat(found.at("/items/0/usablePasses/0/purchaseBranchId").asString()).isEqualTo(Long.toString(second));

            long id = entered(s, seat, customer, pass);
            // The session is this branch's record although the customer is not its customer.
            var session = node(get(s, "/api/seat-sessions/" + id));
            assertThat(session.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(session.at("/pass/purchaseBranchId").asString()).isEqualTo(Long.toString(second));
            assertThat(session.at("/customer").size()).isEqualTo(3);
            assertThat(session.toString()).doesNotContain("secret@customer.test", "내부 메모");

            // Stays pinned: a clock going back would look like a forged session activity time.
            clock.fixed = Instant.now().plusSeconds(90);
            var closed = left(s, id);
            assertThat(closed.get("status").asString()).isEqualTo("종료");
            assertThat(closed.get("deductedMinutes").asLong()).isEqualTo(2);
            assertThat(closed.at("/customer").size()).isEqualTo(3);
            assertThat(closed.toString()).doesNotContain("secret@customer.test", "내부 메모");

            // Finding and serving the customer widened nothing.
            assertThat(get(s, "/api/customers/" + customer).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/customers/" + customer + "/pass-history").statusCode()).isIn(404, 405);
            assertThat(get(s, "/api/customers?q=" + memberNo).body()).doesNotContain(memberNo);
            assertThat(get(s, "/api/customers").body()).doesNotContain(memberNo);
            assertThat(get(s, "/api/passes/" + pass).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/passes").body()).doesNotContain("\"" + pass + "\"");

            // Events: seat and session go to the branch of use, the balance to the branch of purchase.
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, seat)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seat-sessions' AND entity_id=?", first, id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='passes' AND entity_id=?", second, pass)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='passes'", first)).isZero();
        }
    }

    @Test void onlyTheBranchOfTheSessionReachesItWhateverBranchTheCustomerBelongsTo() throws Exception {
        long customer = customer(second, "지점 기준 고객");
        long pass = timePass(customer, second);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            // The customer's own branch has no business with a session at another branch.
            try (var other = login(outsider)) {
                assertThat(get(other, "/api/seat-sessions/" + id).statusCode()).isEqualTo(404);
                refused(checkOut(other, id, Map.of(), key()), 404, "NOT_FOUND");
                refused(checkOut(other, 999_999_999L, Map.of(), key()), 404, "NOT_FOUND");
            }
            assertThat(jdbc.queryForObject("SELECT status FROM seat_sessions WHERE id=?", String.class, id)).isEqualTo("ACTIVE");
            assertThat(get(s, "/api/seat-sessions/" + id + "?scope=" + first).statusCode()).isEqualTo(200);
            assertThat(get(s, "/api/seat-sessions/" + id + "?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/seat-sessions/" + id + "?scope=abc").statusCode()).isEqualTo(422);
            try (var o = login(owner)) {
                assertThat(get(o, "/api/seat-sessions/" + id).statusCode()).isEqualTo(200);
                assertThat(get(o, "/api/seat-sessions/" + id + "?scope=" + second).statusCode()).isEqualTo(404);
                assertThat(get(o, "/api/seat-sessions/999999999").statusCode()).isEqualTo(404);
            }
            // Any member of the branch may finish it, not only the one who checked it in; no grant is needed.
            try (var colleague = login(otherStaff)) {
                assertThat(left(colleague, id).get("status").asString()).isEqualTo("종료");
            }
        }
    }

    @Test void theOwnerHandlesAnyBranchWithoutMembershipAndARevokedMemberLosesAccessAtOnce() throws Exception {
        long customer = customer(first, "권한 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        try (var o = login(owner)) {
            long id = entered(o, seat, customer, pass);
            assertThat(left(o, id).get("status").asString()).isEqualTo("종료");
        }
        try (var s = login(staff)) {
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", staff, first);
            refused(checkIn(s, entry(seat, customer, pass), key()), 404, "NOT_FOUND");
            jdbc.update("UPDATE branch_memberships SET status='ACTIVE' WHERE employee_id=? AND branch_id=?", staff, first);
        }
    }

    @Test void theSessionDetailShowsMovesWithSeatsAndActor() throws Exception {
        long customer = customer(first, "이동 고객");
        long pass = timePass(customer, first);
        long from = seat(first);
        long to = seat(first);
        try (var s = login(staff)) {
            long id = entered(s, from, customer, pass);
            // Moves arrive with #63; the detail already reads their table.
            jdbc.update("INSERT INTO seat_session_moves(seat_session_id,from_seat_id,to_seat_id,actor_employee_id,reason,request_key,moved_at) "
                    + "VALUES(?,?,?,?,'창가 요청',?,'2026-11-02 10:10:10.000000')", id, from, to, staff, "SMV-" + id);
            var detail = node(get(s, "/api/seat-sessions/" + id));
            assertThat(detail.get("moves").size()).isEqualTo(1);
            assertThat(detail.at("/moves/0/fromSeat/id").asString()).isEqualTo(Long.toString(from));
            assertThat(detail.at("/moves/0/toSeat/seatNo").asString()).startsWith("U-");
            assertThat(detail.at("/moves/0/movedAt").asString()).isEqualTo("2026-11-02T10:10:10Z");
            assertThat(detail.at("/moves/0/movedBy/name").asString()).isEqualTo("첫지점 직원");
            assertThat(detail.at("/moves/0/reason").asString()).isEqualTo("창가 요청");
        }
    }

    // ---- exact-match lookup ---------------------------------------------------------------------------------

    @Test void lookupFindsOnlyExactMatchesWithTheMinimumDataAndRecordsWhoAsked() throws Exception {
        clock.fixed = TEN;
        long customer = customer(second, "타지점 홍길동", "01099998888");
        long usable = pass(customer, second, time(100, null));
        long usableCredit = pass(customer, second, stored(3000, 600, 30L));
        long period = pass(customer, first, period(30));
        long exhausted = pass(customer, second, time(100, null));
        jdbc.update("UPDATE passes SET status='EXHAUSTED', remaining_minutes=0 WHERE id=?", exhausted);
        long expired = pass(customer, second, time(100, null));
        expire(expired, TEN);
        long refunded = pass(customer, second, time(100, null));
        jdbc.update("UPDATE passes SET status='REFUNDED' WHERE id=?", refunded);
        long zero = pass(customer, second, stored(1000, 600, null));
        jdbc.update("UPDATE passes SET remaining_credit_krw=0 WHERE id=?", zero);
        try (var s = login(staff)) {
            var response = lookup(s, byPhone(first, "010-9999 8888"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            var items = node(response).get("items");
            assertThat(items.size()).isEqualTo(1);
            var item = items.get(0);
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("customer", "status", "activeSession", "usablePasses");
            assertThat(item.at("/customer").propertyNames()).containsExactlyInAnyOrder("id", "memberNo", "name");
            assertThat(item.at("/customer/name").asString()).isEqualTo("타지점 홍길동");
            assertThat(item.get("status").asString()).isEqualTo("정상");
            assertThat(item.get("activeSession").isNull()).isTrue();
            assertThat(response.body()).doesNotContain("01099998888", "secret@customer.test", "내부 메모", "15000");
            var passes = item.get("usablePasses");
            assertThat(passes.size()).isEqualTo(3);
            assertThat(passes.get(0).propertyNames()).containsExactlyInAnyOrder("id", "kind", "productName", "remainingMinutes",
                    "remainingCreditKrw", "expiresAt", "purchaseBranchId");
            assertThat(passes.findValuesAsString("id")).containsExactly(Long.toString(usable), Long.toString(usableCredit), Long.toString(period));
            assertThat(passes.get(0).get("remainingMinutes").asLong()).isEqualTo(100);
            assertThat(passes.get(0).get("expiresAt").isNull()).isTrue();
            assertThat(passes.get(1).get("remainingCreditKrw").asLong()).isEqualTo(3000);
            assertThat(passes.get(2).get("kind").asString()).isEqualTo("기간권");
            assertThat(passes.get(2).get("purchaseBranchId").asString()).isEqualTo(Long.toString(first));

            var row = jdbc.queryForMap("SELECT * FROM customer_service_interactions WHERE customer_id=?", customer);
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
            assertThat(((Number) row.get("employee_id")).longValue()).isEqualTo(staff);
            assertThat(row.get("reason")).isEqualTo("입실 정확 일치 조회(연락처)");
            assertThat(at("SELECT verified_at FROM customer_service_interactions WHERE customer_id=?", customer)).isEqualTo(TEN);

            assertThat(lookup(s, byMemberNo(first, memberNo(customer))).statusCode()).isEqualTo(200);
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=? AND reason='입실 정확 일치 조회(회원번호)'", customer)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='customer-lookups' AND actor_employee_id=?", staff)).isZero();
        }
    }

    @Test void partialNameCaseAndWildcardLookupsFindNobodyAndOnlyTheMethodIsAudited() throws Exception {
        long customer = customer(first, "정확 일치 고객", "01077776666");
        String memberNo = "SEAT-ABC" + customer;
        jdbc.update("UPDATE customers SET member_no=? WHERE id=?", memberNo, customer);
        try (var s = login(staff)) {
            for (String wrong : List.of(memberNo.substring(0, 6), memberNo.substring(1), memberNo + "0", memberNo.toLowerCase(), "%", "SEAT%", "SEAT-_BC" + customer, "정확 일치 고객", "정확")) {
                var response = lookup(s, byMemberNo(first, wrong));
                assertThat(response.statusCode()).as(wrong).isEqualTo(200);
                assertThat(node(response).get("items").isEmpty()).as(wrong).isTrue();
            }
            for (String wrong : List.of("010777766", "01077776667", "0107777666600")) {
                var response = lookup(s, byPhone(first, wrong));
                assertThat(response.statusCode()).as(wrong).isIn(200, 422);
                if (response.statusCode() == 200) assertThat(node(response).get("items").isEmpty()).as(wrong).isTrue();
            }
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=?", customer)).isZero();
            var rows = jdbc.queryForList("SELECT * FROM audit_events WHERE entity_type='customer-lookups' AND actor_employee_id=? ORDER BY id", staff);
            assertThat(rows).isNotEmpty().allSatisfy(row -> {
                assertThat(row.get("action")).isEqualTo("CHECK_IN_LOOKUP_NO_MATCH");
                assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
                assertThat(row.get("entity_id")).isNull();
            });
            assertThat(json.readTree((String) rows.get(0).get("after_json")).get("method").asString()).isEqualTo("회원번호");
            assertThat(json.readTree((String) rows.get(rows.size() - 1).get("after_json")).get("method").asString()).isEqualTo("연락처");
            // The searched values themselves are never stored: they may belong to someone who is not a customer.
            assertThat(rows.toString()).doesNotContain("01077776667", "정확 일치 고객", memberNo.substring(0, 6));
        }
    }

    @Test void lookupInputIsValidatedAndTheBranchMustBeTheRequestersOwn() throws Exception {
        long customer = customer(first, "입력 고객");
        try (var s = login(staff)) {
            refused(lookup(s, Map.of("branchId", Long.toString(first))), 422, "VALIDATION_FAILED");
            refused(lookup(s, Map.of("branchId", Long.toString(first), "memberNo", "A", "phone", "01012345678")), 422, "VALIDATION_FAILED");
            refused(lookup(s, Map.of("memberNo", "A")), 422, "VALIDATION_FAILED");
            refused(lookup(s, byPhone(first, "1234")), 422, "VALIDATION_FAILED");
            refused(lookup(s, byPhone(first, "010123456789")), 422, "VALIDATION_FAILED");
            refused(lookup(s, byPhone(first, "010abc45678")), 422, "VALIDATION_FAILED");
            refused(lookup(s, byMemberNo(first, "A".repeat(41))), 422, "VALIDATION_FAILED");
            refused(lookup(s, Map.of("branchId", Long.toString(first), "memberNo", "A", "name", "홍")), 422, "VALIDATION_FAILED");
            refused(lookup(s, Map.of("branchId", Long.toString(first), "q", "홍")), 422, "VALIDATION_FAILED");
            // A branch of someone else: the requester is no valid member of the lookup branch.
            refused(lookup(s, byMemberNo(second, memberNo(customer))), 403, "FORBIDDEN");
            assertThat(http(s.client(), "POST", "/api/customer-lookups/check-in", null, byMemberNo(first, "A"), null).statusCode()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=?", customer)).isZero();
            // The condition travels in the body, never in the URL.
            assertThat(get(s, "/api/customer-lookups/check-in").statusCode()).isIn(404, 405);
        }
    }

    @Test void aSharedPhoneReturnsAtMostTenCustomersInMemberNumberOrderAndEveryoneIsRecorded() throws Exception {
        List<Long> family = new ArrayList<>();
        for (int index = 0; index < 11; index++) family.add(customer(first, "가족" + index, "0212345678"));
        try (var s = login(outsider)) {
            var items = node(lookup(s, byPhone(second, "02-1234-5678"))).get("items");
            assertThat(items.size()).isEqualTo(10);
            var numbers = new ArrayList<String>();
            items.forEach(item -> numbers.add(item.at("/customer/memberNo").asString()));
            assertThat(numbers).isSorted();
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE employee_id=? AND branch_id=?", outsider, second)).isEqualTo(10);
        }
    }

    @Test void lookupShowsRestrictedCustomersAndWhereSomeoneIsAlreadyCheckedIn() throws Exception {
        long customer = customer(first, "상태 고객", "01055554444");
        long pass = timePass(customer, first);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            jdbc.update("UPDATE customers SET status='RESTRICTED' WHERE id=?", customer);
            try (var other = login(outsider)) {
                var item = node(lookup(other, byPhone(second, "01055554444"))).at("/items/0");
                assertThat(item.get("status").asString()).isEqualTo("이용 제한");
                assertThat(item.at("/activeSession/id").asString()).isEqualTo(Long.toString(id));
                assertThat(item.at("/activeSession/branchId").asString()).isEqualTo(Long.toString(first));
                assertThat(item.at("/activeSession/checkedInAt").asString()).isNotBlank();
            }
        }
    }

    @Test void aStoppedBranchStillLooksUpAndAnOwnerIsRecordedInAuditWhenItHasNoMembershipRow() throws Exception {
        long customer = customer(first, "사장 조회 고객", "01033332222");
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var o = login(owner)) {
            var found = lookup(o, byMemberNo(first, memberNo(customer)));
            assertThat(found.statusCode()).isEqualTo(200);
            assertThat(node(found).get("items").size()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=?", customer)).isZero();
            var row = jdbc.queryForMap("SELECT * FROM audit_events WHERE entity_type='customer-lookups' AND action='CHECK_IN_LOOKUP' AND actor_employee_id=?", owner);
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
            var after = json.readTree((String) row.get("after_json"));
            assertThat(after.get("method").asString()).isEqualTo("회원번호");
            assertThat(after.get("customerIds").get(0).asString()).isEqualTo(Long.toString(customer));

            // With a membership row the regular trail is used.
            member(owner, first);
            assertThat(lookup(o, byPhone(first, "01033332222")).statusCode()).isEqualTo(200);
            assertThat(count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=? AND employee_id=?", customer, owner)).isEqualTo(1);

            assertThat(node(lookup(o, byPhone(first, "01000000000"))).get("items").isEmpty()).isTrue();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action='CHECK_IN_LOOKUP_NO_MATCH' AND actor_employee_id=?", owner)).isEqualTo(1);
        }
    }

    // ---- check-out ------------------------------------------------------------------------------------------

    @Test void checkOutRecordsActorReasonAuditAndEventsAndReleasesTheSeat() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "퇴실 고객");
        long pass = pass(customer, first, time(120, null));
        long seat = seat(first);
        try (var s = login(staff)) {
            long id = entered(s, seat, customer, pass);
            clock.fixed = TEN.plusSeconds(1800);
            var response = checkOut(s, id, Map.of("reason", " 조기 퇴실 "), key());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var closed = node(response);
            assertThat(closed.get("version").asLong()).isEqualTo(2);
            assertThat(closed.get("usedMinutes").asLong()).isEqualTo(30);
            assertThat(closed.at("/checkedInBy/id").asString()).isEqualTo(Long.toString(staff));
            assertThat(node(get(s, "/api/seat-sessions/" + id))).isEqualTo(closed);

            var row = jdbc.queryForMap("SELECT * FROM seat_sessions WHERE id=?", id);
            assertThat(row.get("checked_out_at")).isEqualTo(row.get("effective_checked_out_at"));
            assertThat(row.get("active_seat_id")).isNull();
            assertThat(row.get("active_customer_id")).isNull();
            assertThat((String) row.get("end_request_key")).startsWith("SOUT-");
            // Check-in actor stays in the exception columns; the check-out actor and reason go to the audit log.
            assertThat(((Number) row.get("exception_actor_id")).longValue()).isEqualTo(staff);
            var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='CHECKED_OUT'", id);
            assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(staff);
            assertThat(audit.get("reason")).isEqualTo("조기 퇴실");
            var after = json.readTree((String) audit.get("after_json"));
            assertThat(after.get("usedMinutes").asLong()).isEqualTo(30);
            assertThat(after.get("deductedMinutes").asLong()).isEqualTo(30);
            assertThat(jdbc.queryForObject("SELECT reason FROM pass_balance_events WHERE seat_session_id=?", String.class, id)).isEqualTo("조기 퇴실");

            assertThat(node(get(s, "/api/seats/" + seat)).get("status").asString()).isEqualTo("사용 가능");
            // The same customer and seat can start again with the rest of the pass.
            long again = entered(s, seat, customer, pass);
            clock.fixed = clock.fixed.plusSeconds(60);
            assertThat(left(s, again).get("passRemainingMinutes").asLong()).isEqualTo(89);
            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(2);
            // Issue amount + ledger = balance (FR-PASS-09).
            assertThat(count("SELECT 120 + SUM(delta_minutes) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(89);
        }
    }

    @Test void aRepeatedCheckOutNeverDeductsTwice() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "반복 퇴실 고객");
        long pass = pass(customer, first, time(120, null));
        String key = key();
        long id;
        try (var s = login(staff)) { id = entered(s, seat(first), customer, pass); }
        clock.fixed = time("11:30:20");
        try (var s = login(staff)) {
            var done = checkOut(s, id, Map.of("reason", "퇴실"), key);
            assertThat(done.statusCode()).isEqualTo(200);
            assertThat(node(done).get("passRemainingMinutes").asLong()).isEqualTo(29);

            // Same key, same input: the first result, even when the clock has moved on.
            clock.fixed = time("11:50:20");
            var replay = checkOut(s, id, Map.of("reason", "퇴실"), key);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(node(replay)).isEqualTo(node(done));
            // Same key, different input.
            refused(checkOut(s, id, Map.of("reason", "다른 사유"), key), 409, "IDEMPOTENCY_CONFLICT");
            // Another key, or the same key from a colleague, finds a closed session.
            refused(checkOut(s, id, Map.of("reason", "퇴실"), key()), 409, "SESSION_ALREADY_CLOSED");
            try (var colleague = login(otherStaff)) {
                refused(checkOut(colleague, id, Map.of("reason", "퇴실"), key), 409, "SESSION_ALREADY_CLOSED");
            }

            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(1);
            assertThat(count("SELECT remaining_minutes FROM passes WHERE id=?", pass)).isEqualTo(29);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='CHECKED_OUT'", id)).isEqualTo(1);
            assertThat(count("SELECT billed_minutes FROM seat_sessions WHERE id=?", id)).isEqualTo(91);
            assertThat(text("SELECT checked_out_at FROM seat_sessions WHERE id=?", id)).startsWith("2026-11-02 11:30:20");
        }
    }

    @Test void theLedgerKeyStopsASecondChargeEvenWhenEverythingElseAllowsIt() throws Exception {
        long customer = customer(first, "방어선 고객");
        long pass = pass(customer, first, time(120, null));
        clock.fixed = TEN;
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            jdbc.update("INSERT INTO pass_balance_events(pass_id,seat_session_id,kind,delta_minutes,event_key) VALUES(?,?,'USE',-5,?)", pass, id, "USE-" + id);
            clock.fixed = TEN.plusSeconds(600);
            refused(checkOut(s, id, Map.of(), key()), 409, "SESSION_ALREADY_CLOSED");
            // Everything rolled back: session open, balance untouched, nothing recorded as done.
            assertThat(text("SELECT status FROM seat_sessions WHERE id=?", id)).isEqualTo("ACTIVE");
            assertThat(count("SELECT remaining_minutes FROM passes WHERE id=?", pass)).isEqualTo(120);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='CHECKED_OUT'", id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='seat-sessions:check-out'", staff)).isZero();
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='passes' AND entity_id=? AND branch_id=?", pass, first)).isEqualTo(1);
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO pass_balance_events(pass_id,kind,delta_minutes,event_key) "
                + "SELECT pass_id,'USE',-1,event_key FROM pass_balance_events WHERE pass_id=?", pass)).isInstanceOf(DataAccessException.class);
    }

    @Test void nothingStopsACheckOutButItsOwnClosureAndTheOverlongStayIsRecorded() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "퇴실 허용 고객");
        long pass = pass(customer, first, time(120, 30L));
        long id;
        try (var s = login(staff)) { id = entered(s, seat(first), customer, pass); }
        expire(pass, TEN.plusSeconds(600));
        jdbc.update("UPDATE customers SET status='INACTIVE' WHERE id=?", customer);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        clock.fixed = TEN.plusSeconds(1200);
        try (var s = login(staff)) {
            var closed = left(s, id);
            assertThat(closed.get("usedMinutes").asLong()).isEqualTo(20);
            assertThat(closed.get("deductedMinutes").asLong()).isEqualTo(10);
            assertThat(closed.get("overageMinutes").asLong()).isEqualTo(10);
            assertThat(get(s, "/api/seat-sessions/" + id).statusCode()).isEqualTo(200);
            assertThat(lookup(s, byMemberNo(first, memberNo(customer))).statusCode()).isEqualTo(200);
        }
    }

    @Test void checkOutTakesNoMinutesOrAmountsAndAlwaysNeedsAKey() throws Exception {
        long customer = customer(first, "요청 고객");
        long pass = timePass(customer, first);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            for (String field : List.of("minutes", "usedMinutes", "deductedMinutes", "deductedKrw", "overageMinutes", "amountKrw", "checkedOutAt"))
                refused(checkOut(s, id, Map.of(field, 1), key()), 422, "VALIDATION_FAILED");
            refused(checkOut(s, id, Map.of("reason", "가".repeat(501)), key()), 422, "VALIDATION_FAILED");
            refused(checkOut(s, id, Map.of("reason", 1), key()), 422, "VALIDATION_FAILED");
            refused(checkOut(s, id, Map.of(), null), 422, "VALIDATION_FAILED");
            refused(checkOut(s, id, Map.of(), "short"), 422, "VALIDATION_FAILED");
            refused(checkOut(s, id, null, key()), 422, "VALIDATION_FAILED");
            assertThat(http(s.client(), "POST", "/api/seat-sessions/" + id + "/check-out", null, Map.of(), key()).statusCode()).isEqualTo(403);
            assertThat(text("SELECT status FROM seat_sessions WHERE id=?", id)).isEqualTo("ACTIVE");
            assertThat(left(s, id).get("reason")).isNull();
        }
    }

    @Test void aClockSteppedBackBeforeCheckInClosesTheStayWithZeroMinutes() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "시계 고객");
        long pass = timePass(customer, first);
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            jdbc.update("UPDATE seat_sessions SET checked_in_at=?, effective_checked_in_at=? WHERE id=?",
                    Timestamp.from(TEN.plusSeconds(30)), Timestamp.from(TEN.plusSeconds(30)), id);
            var closed = left(s, id);
            assertThat(closed.get("usedMinutes").asLong()).isZero();
            assertThat(closed.get("status").asString()).isEqualTo("종료");
        }
    }

    // ---- idempotency expiry ---------------------------------------------------------------------------------

    @Test void aKeyReusedAfterItsRecordExpiredStartsNothingNew() throws Exception {
        long customer = customer(first, "만료 키 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        String inKey = key(), outKey = key();
        try (var s = login(staff)) {
            var made = node(checkIn(s, entry(seat, customer, pass), inKey));
            long id = made.get("id").asLong();
            assertThat(checkOut(s, id, Map.of(), outKey).statusCode()).isEqualTo(200);
            jdbc.update("UPDATE idempotency_records SET expires_at='2020-01-01 00:00:00' WHERE actor_employee_id=?", staff);
            // The stored request key is a hash of the same values, so uk_seat_sessions_3 refuses the second start.
            refused(checkIn(s, entry(seat, customer, pass), inKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(checkOut(s, id, Map.of(), outKey), 409, "SESSION_ALREADY_CLOSED");
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isLessThanOrEqualTo(1);
        }
    }

    // ---- database constraints behind the checks ---------------------------------------------------------------

    @Test void uniqueKeysAreTranslatedWhenARaceSlipsPastTheChecks() throws Exception {
        long seat = seat(first);
        long seated = customer(first, "먼저 온 고객");
        long seatedPass = timePass(seated, first);
        long late = customer(first, "늦은 고객");
        long latePass = timePass(late, first);
        try (var s = login(staff)) {
            entered(s, seat, seated, seatedPass);
            // Pretend the check-in checks read before the rival committed: only the database can refuse now.
            doReturn(false).when(sessions).existsBySeatIdAndStatus(anyLong(), eq(SeatSession.Status.ACTIVE));
            doReturn(false).when(sessions).existsByCustomerIdAndStatus(anyLong(), eq(SeatSession.Status.ACTIVE));
            refused(checkIn(s, entry(seat, late, latePass), key()), 409, "SEAT_OCCUPIED");
            refused(checkIn(s, entry(seat(first), seated, seatedPass), key()), 409, "CUSTOMER_ALREADY_CHECKED_IN");
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id IN (?,?)", seated, late)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='seat-sessions:check-in'", staff)).isEqualTo(1);
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

    static List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).toList(); }

    @Test void onlyOneOfManyCustomersGetsTheSameSeat() throws Exception {
        try (var s = login(staff)) {
            for (int round = 0; round < 3; round++) {
                long seat = seat(first);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    long customer = customer(first, "동시 좌석 " + index);
                    long pass = timePass(customer, first);
                    calls.add(() -> checkIn(s, entry(seat, customer, pass), key()));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(status -> status == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
                responses.stream().filter(response -> response.statusCode() != 201).forEach(response -> refused(response, 409, "SEAT_OCCUPIED"));
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE seat_id=?", seat)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", seat)).isEqualTo(1);
            }
        }
    }

    @Test void onlyOneOfManySeatsTakesTheSameCustomer() throws Exception {
        try (var s = login(staff); var o = login(owner)) {
            for (int round = 0; round < 3; round++) {
                long customer = customer(first, "동시 고객");
                long pass = timePass(customer, first);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    long seat = seat(first);
                    // Two people at the desk, not one retrying.
                    Session who = index % 2 == 0 ? s : o;
                    calls.add(() -> checkIn(who, entry(seat, customer, pass), key()));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(status -> status == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
                responses.stream().filter(response -> response.statusCode() != 201).forEach(response -> refused(response, 409, "CUSTOMER_ALREADY_CHECKED_IN"));
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isEqualTo(1);
            }
        }
    }

    @Test void theSameCheckInSentManyTimesAtOnceCreatesOneSessionAndEveryoneGetsIt() throws Exception {
        long customer = customer(first, "중복 전송 고객");
        long pass = timePass(customer, first);
        long seat = seat(first);
        String key = key();
        try (var s = login(staff)) {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) calls.add(() -> checkIn(s, entry(seat, customer, pass), key));
            var responses = race(calls);
            assertThat(statuses(responses)).allMatch(status -> status == 201);
            assertThat(responses.stream().map(this::node).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE customer_id=?", customer)).isEqualTo(1);
        }
    }

    @Test void concurrentCheckOutsCloseOnceAndChargeOnce() throws Exception {
        try (var s = login(staff); var colleague = login(otherStaff)) {
            for (int round = 0; round < 3; round++) {
                long customer = customer(first, "동시 퇴실 고객");
                long pass = pass(customer, first, stored(100_000, 6_000, null));
                long id = entered(s, seat(first), customer, pass);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    Session who = index % 2 == 0 ? s : colleague;
                    calls.add(() -> checkOut(who, id, Map.of(), key()));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(status -> status == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
                responses.stream().filter(response -> response.statusCode() != 200).forEach(response -> refused(response, 409, "SESSION_ALREADY_CLOSED"));
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(1);
                // What the ledger took is exactly what left the balance.
                assertThat(count("SELECT delta_krw + (100000 - remaining_credit_krw) FROM pass_balance_events e JOIN passes p ON p.id=e.pass_id WHERE p.id=?", pass)).isZero();
                assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_id=? AND entity_type='seat-sessions' AND action='CHECKED_OUT'", id)).isEqualTo(1);
            }
        }
    }

    @Test void theSameCheckOutSentManyTimesAtOnceChargesOnceAndEveryoneGetsTheSameResult() throws Exception {
        long customer = customer(first, "같은 키 퇴실 고객");
        long pass = pass(customer, first, time(600, null));
        String key = key();
        try (var s = login(staff)) {
            long id = entered(s, seat(first), customer, pass);
            clock.fixed = Instant.now().plusSeconds(300);
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) calls.add(() -> checkOut(s, id, Map.of(), key));
            var responses = race(calls);
            assertThat(statuses(responses)).allMatch(status -> status == 200);
            assertThat(responses.stream().map(this::node).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(1);
        }
    }

    @Test void aSeatIsEitherCheckedIntoOrStoppedNeverBoth() throws Exception {
        long manager = person("EMPLOYEE", "점장");
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'MANAGER','ACTIVE')", manager, first);
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='operations:write' WHERE m.employee_id=?", manager);
        int checkedInFirst = 0, stoppedFirst = 0;
        try (var s = login(staff); var m = login(manager)) {
            for (int round = 0; round < 12; round++) {
                long customer = customer(first, "경합 고객");
                long pass = timePass(customer, first);
                long seat = seat(first);
                var body = Map.<String, Object>of("version", 1, "status", "운영 중단", "reason", "점검");
                // The head start sweeps across the moment the two collide, so both orders really happen.
                long headStart = round * 3L;
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> { Thread.sleep(headStart); return checkIn(s, entry(seat, customer, pass), key()); },
                        () -> http(m.client(), "POST", "/api/seats/" + seat + "/status", m.token(), body, null)));
                var entering = responses.get(0);
                var stopping = responses.get(1);
                if (entering.statusCode() == 201) {
                    refused(stopping, 409, "SEAT_OCCUPIED");
                    assertThat(text("SELECT status FROM seats WHERE id=?", seat)).isEqualTo("AVAILABLE");
                    assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", seat)).isEqualTo(1);
                    checkedInFirst++;
                } else {
                    refused(entering, 409, "SEAT_BLOCKED");
                    assertThat(stopping.statusCode()).isEqualTo(200);
                    assertThat(text("SELECT status FROM seats WHERE id=?", seat)).isEqualTo("BLOCKED");
                    assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE seat_id=?", seat)).isZero();
                    stoppedFirst++;
                }
            }
        }
        assertThat(checkedInFirst + stoppedFirst).isEqualTo(12);
    }

    @Test void aPassIsEitherRefundedOrUsedNeverBoth() throws Exception {
        long seller = person("EMPLOYEE", "환불 직원");
        member(seller, first);
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='payments:write' WHERE m.employee_id=?", seller);
        try (var s = login(staff); var r = login(seller)) {
            for (int round = 0; round < 6; round++) {
                long customer = customer(first, "환불 경합 고객");
                long pass = timePass(customer, first);
                long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", pass);
                long seat = seat(first);
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> checkIn(s, entry(seat, customer, pass), key()),
                        () -> http(r.client(), "POST", "/api/payments/" + payment + "/refund", r.token(), Map.of("reason", "변심"), key())));
                var entering = responses.get(0);
                var refunding = responses.get(1);
                if (entering.statusCode() == 201) {
                    refused(refunding, 409, "PASS_ALREADY_USED");
                    assertThat(text("SELECT status FROM passes WHERE id=?", pass)).isEqualTo("ACTIVE");
                } else {
                    refused(entering, 409, "PASS_REFUNDED");
                    assertThat(refunding.statusCode()).isEqualTo(200);
                    assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE pass_id=?", pass)).isZero();
                }
            }
        }
    }
}
