package com.openstudy.modules.seat;

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
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/** Issue #63: HTTP and MySQL behaviour of the in-use seat move (seat-usage-api.md "좌석 이동"). */
@MySqlIntegrationTest
@Import(SeatSessionMoveIntegrationTests.TimeConfiguration.class)
class SeatSessionMoveIntegrationTests {
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

    /** writer and outsider hold operations:write at their own branch; staff is a plain member of the first one. */
    long owner, writer, staff, outsider, first, second;
    String timeCategory, storedCategory;
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
        @Bean @Primary MutableClock moveClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.fixed = null;
        owner = person("OWNER", "사장");
        writer = person("EMPLOYEE", "이동 담당"); staff = person("EMPLOYEE", "권한 없는 직원"); outsider = person("EMPLOYEE", "둘째지점 직원");
        first = branch("이동 첫 지점"); second = branch("이동 둘째 지점");
        member(writer, first); member(staff, first); member(outsider, second);
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='operations:write' WHERE m.employee_id IN (?,?)", writer, outsider);
        timeCategory = category("시간권"); storedCategory = category("정액권");
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
        String email = UUID.randomUUID() + "@move.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("move-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "move-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
    }

    String category(String kind) {
        var made = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류 " + kind, "kind", kind), "move-fixture");
        return json.readTree(json.writeValueAsString(made)).get("id").asString();
    }

    long seat(long branch) {
        String no = "M-" + ++seatNumber;
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'A',?,'GENERAL','AVAILABLE')", branch, no);
        return jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", Long.class, branch, no);
    }

    long customer(long branch, String name) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", name);
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        body.put("email", "secret@customer.test"); body.put("memo", "내부 메모");
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "move-fixture").id());
        customerIds.add(id);
        return id;
    }

    long pass(long customer, long branch, Map<String, Object> terms) {
        String kind = (String) terms.get("kind");
        var product = new LinkedHashMap<String, Object>();
        product.put("name", token + " " + kind); product.put("priceKrw", 15000L); product.put("terms", terms); product.put("status", "판매 중");
        product.put("categoryId", "시간권".equals(kind) ? timeCategory : storedCategory);
        product.put("branchIds", List.of(Long.toString(branch)));
        String productId = products.create(owner, "all", key(), product, "move-fixture").id();
        var sale = new LinkedHashMap<String, Object>();
        sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(branch));
        sale.put("productId", productId); sale.put("method", "카드");
        return Long.parseLong(payments.create(owner, "all", key(), sale, "move-fixture").passId());
    }

    Map<String, Object> time(long minutes) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", minutes); terms.put("validDays", null);
        return terms;
    }

    Map<String, Object> stored(long credit, long rate) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "정액권"); terms.put("creditKrw", credit); terms.put("hourlyRateKrw", rate); terms.put("validDays", null);
        return terms;
    }

    /** A customer with a fresh time pass, checked in at a fresh seat of the first branch; returns {session, seat}. */
    long[] sitting(Session s) throws Exception {
        long customer = customer(first, "이동 고객");
        long pass = pass(customer, first, time(600));
        long seat = seat(first);
        return new long[] {entered(s, seat, customer, pass), seat};
    }

    static String key() { return UUID.randomUUID().toString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "move-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> checkOut(Session session, long id, String key) throws Exception {
        return http(session.client(), "POST", "/api/seat-sessions/" + id + "/check-out", session.token(), Map.of(), key);
    }

    HttpResponse<String> move(Session session, long id, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/seat-sessions/" + id + "/move", session.token(), body, key);
    }

    HttpResponse<String> moveTo(Session session, long id, long seat) throws Exception { return move(session, id, to(seat), key()); }

    static Map<String, Object> to(long seat) {
        var body = new LinkedHashMap<String, Object>();
        body.put("toSeatId", Long.toString(seat));
        return body;
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
        var response = checkOut(session, id, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode moved(Session session, long id, long seat) throws Exception {
        var response = moveTo(session, id, seat);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).isEqualTo(code);
    }

    long seatOf(long session) { return count("SELECT seat_id FROM seat_sessions WHERE id=?", session); }
    long moveRows(long session) { return count("SELECT COUNT(*) FROM seat_session_moves WHERE seat_session_id=?", session); }

    // ---- acceptance: a move keeps the stay and swaps the occupancy ------------------------------------------

    @Test void aMoveKeepsTheStayAndPassSwapsTheOccupancyAndLeavesHistoryAuditAndEvents() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "이동 고객");
        long pass = pass(customer, first, time(600));
        long from = seat(first);
        long target = seat(first);
        try (var s = login(writer)) {
            long id = entered(s, from, customer, pass);
            var before = jdbc.queryForMap("SELECT * FROM seat_sessions WHERE id=?", id);
            clock.fixed = TEN.plusSeconds(1200);
            String key = key();
            var body = to(target);
            body.put("reason", "  창가 요청 ");
            var response = move(s, id, body, key);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var result = node(response);
            assertThat(result.get("id").asLong()).isEqualTo(id);
            assertThat(result.get("status").asString()).isEqualTo("이용 중");
            assertThat(result.get("version").asLong()).isEqualTo(2);
            assertThat(result.at("/seat/id").asString()).isEqualTo(Long.toString(target));
            assertThat(result.at("/pass/id").asString()).isEqualTo(Long.toString(pass));
            assertThat(result.at("/customer/id").asString()).isEqualTo(Long.toString(customer));
            assertThat(result.get("checkedInAt").asString()).isEqualTo("2026-11-02T10:00:00Z");
            assertThat(result.get("checkedOutAt").isNull()).isTrue();
            assertThat(result.get("moves").size()).isEqualTo(1);
            assertThat(result.at("/moves/0/fromSeat/id").asString()).isEqualTo(Long.toString(from));
            assertThat(result.at("/moves/0/toSeat/id").asString()).isEqualTo(Long.toString(target));
            assertThat(result.at("/moves/0/movedAt").asString()).isEqualTo("2026-11-02T10:20:00Z");
            assertThat(result.at("/moves/0/movedBy/id").asString()).isEqualTo(Long.toString(writer));
            assertThat(result.at("/moves/0/reason").asString()).isEqualTo("창가 요청");
            assertThat(node(get(s, "/api/seat-sessions/" + id))).isEqualTo(result);

            // The same row: only the seat changed, so the stay, pass and start time go on.
            var row = jdbc.queryForMap("SELECT * FROM seat_sessions WHERE id=?", id);
            assertThat(((Number) row.get("seat_id")).longValue()).isEqualTo(target);
            assertThat(((Number) row.get("active_seat_id")).longValue()).isEqualTo(target);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            for (String column : List.of("customer_id", "pass_id", "branch_id", "checked_in_at", "effective_checked_in_at", "start_request_key"))
                assertThat(row.get(column)).as(column).isEqualTo(before.get(column));
            assertThat(row.get("checked_out_at")).isNull();

            // The seat board agrees: the old seat is free, the new one shows the session.
            var old = node(get(s, "/api/seats/" + from));
            assertThat(old.get("status").asString()).isEqualTo("사용 가능");
            assertThat(old.get("activeSession").isNull()).isTrue();
            var now = node(get(s, "/api/seats/" + target));
            assertThat(now.get("status").asString()).isEqualTo("사용 중");
            assertThat(now.at("/activeSession/id").asString()).isEqualTo(Long.toString(id));

            var move = jdbc.queryForMap("SELECT * FROM seat_session_moves WHERE seat_session_id=?", id);
            assertThat(((Number) move.get("from_seat_id")).longValue()).isEqualTo(from);
            assertThat(((Number) move.get("to_seat_id")).longValue()).isEqualTo(target);
            assertThat(((Number) move.get("actor_employee_id")).longValue()).isEqualTo(writer);
            assertThat(move.get("reason")).isEqualTo("창가 요청");
            assertThat((String) move.get("request_key")).startsWith("SMV-").hasSizeLessThanOrEqualTo(120).doesNotContain(key);

            var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='SEAT_MOVED'", id);
            assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(writer);
            assertThat(((Number) audit.get("branch_id")).longValue()).isEqualTo(first);
            assertThat(audit.get("reason")).isEqualTo("창가 요청");
            assertThat(json.readTree((String) audit.get("before_json")).get("seatId").asString()).isEqualTo(Long.toString(from));
            assertThat(json.readTree((String) audit.get("after_json")).get("seatId").asString()).isEqualTo(Long.toString(target));
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, from)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, target)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seat-sessions' AND entity_id=?", first, id)).isEqualTo(2);

            // The vacated seat is usable by someone else straight away.
            long other = customer(first, "빈 좌석 고객");
            entered(s, from, other, pass(other, first, time(600)));
        }
    }

    @Test void theSameKeyReturnsTheFirstResultAndAnotherInputWithItConflicts() throws Exception {
        try (var s = login(writer)) {
            long id = sitting(s)[0];
            long target = seat(first);
            long third = seat(first);
            String key = key();
            var firstMove = move(s, id, to(target), key);
            assertThat(firstMove.statusCode()).isEqualTo(200);
            var replay = move(s, id, to(target), key);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(node(replay)).isEqualTo(node(firstMove));
            assertThat(moveRows(id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='SEAT_MOVED'", id)).isEqualTo(1);
            assertThat(count("SELECT version FROM seat_sessions WHERE id=?", id)).isEqualTo(1);
            // Another destination or another reason under the same key is a different input.
            refused(move(s, id, to(third), key), 409, "IDEMPOTENCY_CONFLICT");
            var reasoned = to(target);
            reasoned.put("reason", "다른 사유");
            refused(move(s, id, reasoned, key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(seatOf(id)).isEqualTo(target);
            assertThat(moveRows(id)).isEqualTo(1);
            // A new key moves again: the history grows in order.
            assertThat(moved(s, id, third).get("moves").size()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM seat_session_moves WHERE seat_session_id=? AND from_seat_id=? AND to_seat_id=?", id, target, third)).isEqualTo(1);
        }
    }

    // ---- permission and scope -------------------------------------------------------------------------------

    @Test void movingNeedsOperationsWriteAtTheSessionBranchAndAnythingElseIsNotFound() throws Exception {
        long id, target = seat(first);
        try (var s = login(writer)) { id = sitting(s)[0]; }
        long from = seatOf(id);
        try (var plain = login(staff)) {
            refused(moveTo(plain, id, target), 403, "FORBIDDEN");
            // Seeing the session is fine, moving it is not; and an ID that does not exist is 404.
            assertThat(get(plain, "/api/seat-sessions/" + id).statusCode()).isEqualTo(200);
            refused(moveTo(plain, 999_999_999L, target), 404, "NOT_FOUND");
        }
        try (var other = login(outsider)) {
            // The grant of another branch does not help: the session is not even visible there.
            refused(moveTo(other, id, target), 404, "NOT_FOUND");
        }
        assertThat(seatOf(id)).isEqualTo(from);
        assertThat(moveRows(id)).isZero();
        try (var o = login(owner)) {
            assertThat(moved(o, id, target).at("/seat/id").asString()).isEqualTo(Long.toString(target));
        }
        // Revoked at once: the next request already sees a branch it cannot reach.
        try (var s = login(writer)) {
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", writer, first);
            refused(moveTo(s, id, seat(first)), 404, "NOT_FOUND");
            jdbc.update("UPDATE branch_memberships SET status='ACTIVE' WHERE employee_id=? AND branch_id=?", writer, first);
        }
    }

    @Test void aCustomerOfAnotherBranchIsMovedByTheBranchOfTheStayWithoutAnyCustomerLookupRight() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long pass = pass(customer, second, time(600));
        long target = seat(first);
        try (var s = login(writer)) {
            long id = entered(s, seat(first), customer, pass);
            assertThat(moved(s, id, target).at("/seat/id").asString()).isEqualTo(Long.toString(target));
        }
    }

    // ---- refusals leave the old seat as it was --------------------------------------------------------------

    @Test void aRefusedMoveKeepsTheOldSeatAndLeavesNothingBehind() throws Exception {
        try (var s = login(writer); var o = login(owner)) {
            var sitting = sitting(s);
            long id = sitting[0], from = sitting[1];
            long occupied = sitting(s)[1];
            long blocked = seat(first);
            jdbc.update("UPDATE seats SET status='BLOCKED' WHERE id=?", blocked);
            long foreign = seat(second);
            long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=?", id);

            refused(moveTo(s, id, occupied), 409, "SEAT_OCCUPIED");
            refused(moveTo(s, id, blocked), 409, "SEAT_BLOCKED");
            refused(moveTo(s, id, foreign), 422, "VALIDATION_FAILED");
            refused(moveTo(s, id, 999_999_999L), 422, "VALIDATION_FAILED");
            refused(moveTo(s, id, from), 422, "VALIDATION_FAILED");
            var foreignAnswer = node(moveTo(s, id, foreign));
            assertThat(foreignAnswer.toString()).contains("toSeatId");
            // A foreign seat is not told apart from a missing one.
            assertThat(foreignAnswer.get("message")).isEqualTo(node(moveTo(s, id, 999_999_999L)).get("message"));

            assertThat(seatOf(id)).isEqualTo(from);
            assertThat(moveRows(id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=?", id)).isEqualTo(auditBefore);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='seat-sessions:move'", writer)).isZero();
            assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", from)).isEqualTo(1);
            assertThat(node(get(o, "/api/seats/" + from)).get("status").asString()).isEqualTo("사용 중");
        }
    }

    @Test void theInputIsValidatedStrictlyAndAKeyIsRequired() throws Exception {
        try (var s = login(writer)) {
            long id = sitting(s)[0];
            long target = seat(first);
            refused(move(s, id, Map.of(), key()), 422, "VALIDATION_FAILED");
            for (Object bad : new Object[] {"abc", "0", "-3", "01", "", 5, true})
                refused(move(s, id, Map.of("toSeatId", bad), key()), 422, "VALIDATION_FAILED");
            var unknown = to(target);
            unknown.put("branchId", "1");
            refused(move(s, id, unknown, key()), 422, "VALIDATION_FAILED");
            var longReason = to(target);
            longReason.put("reason", "가".repeat(501));
            refused(move(s, id, longReason, key()), 422, "VALIDATION_FAILED");
            var numberReason = to(target);
            numberReason.put("reason", 7);
            refused(move(s, id, numberReason, key()), 422, "VALIDATION_FAILED");
            refused(move(s, id, to(target), null), 422, "VALIDATION_FAILED");
            refused(move(s, id, to(target), "short"), 422, "VALIDATION_FAILED");
            assertThat(moveRows(id)).isZero();
            // The longest reason and a blank one are fine; blank means none.
            var edge = to(target);
            edge.put("reason", "가".repeat(500));
            assertThat(move(s, id, edge, key()).statusCode()).isEqualTo(200);
            var blank = to(seat(first));
            blank.put("reason", "   ");
            var result = node(move(s, id, blank, key()));
            assertThat(result.at("/moves/1/reason").isNull()).isTrue();
        }
    }

    @Test void aClosedStayCannotMoveAndTheRefusalsFollowTheContractOrder() throws Exception {
        try (var s = login(writer)) {
            var sitting = sitting(s);
            long id = sitting[0], from = sitting[1];
            long blocked = seat(first);
            jdbc.update("UPDATE seats SET status='BLOCKED' WHERE id=?", blocked);
            long closed = sitting(s)[0];
            long closedSeat = seatOf(closed);
            left(s, closed);
            long target = seat(first);
            refused(moveTo(s, closed, target), 409, "SESSION_ALREADY_CLOSED");
            assertThat(seatOf(closed)).isEqualTo(closedSeat);

            // Stopped branch wins over every seat-related refusal, even over a bad destination.
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(moveTo(s, id, blocked), 409, "BRANCH_NOT_ACTIVE");
            refused(moveTo(s, id, from), 409, "BRANCH_NOT_ACTIVE");
            refused(moveTo(s, id, target), 409, "BRANCH_NOT_ACTIVE");
            // A closed stay wins over a stopped branch.
            refused(moveTo(s, closed, target), 409, "SESSION_ALREADY_CLOSED");
            assertThat(seatOf(id)).isEqualTo(from);
            assertThat(moveRows(id)).isZero();
            // Check-out is still allowed at a stopped branch.
            assertThat(left(s, id).get("status").asString()).isEqualTo("종료");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);

            // With the branch running: a bad destination wins over a blocked one.
            long fresh = sitting(s)[0];
            refused(moveTo(s, fresh, seatOf(fresh)), 422, "VALIDATION_FAILED");
            refused(moveTo(s, fresh, blocked), 409, "SEAT_BLOCKED");
        }
    }

    // ---- the stay goes on: time and deduction ---------------------------------------------------------------

    @Test void checkOutAfterAMoveDeductsExactlyWhatItWouldHaveWithoutAny() throws Exception {
        for (var terms : List.of(time(600), stored(100_000, 6_000))) {
            clock.fixed = TEN;
            long plainCustomer = customer(first, "이동 없음");
            long plainPass = pass(plainCustomer, first, terms);
            long movedCustomer = customer(first, "이동 있음");
            long movedPass = pass(movedCustomer, first, terms);
            long plainSeat = seat(first), movedSeat = seat(first);
            try (var s = login(writer)) {
                long plain = entered(s, plainSeat, plainCustomer, plainPass);
                long movedSession = entered(s, movedSeat, movedCustomer, movedPass);
                clock.fixed = TEN.plusSeconds(1500);
                moved(s, movedSession, seat(first));
                clock.fixed = TEN.plusSeconds(2400);
                moved(s, movedSession, seat(first));
                clock.fixed = TEN.plusSeconds(5430);
                var a = left(s, plain);
                var b = left(s, movedSession);
                assertThat(b.get("usedMinutes").asLong()).isEqualTo(a.get("usedMinutes").asLong()).isEqualTo(91);
                for (String field : List.of("deductedMinutes", "deductedKrw", "overageMinutes", "overageKrw", "passRemainingMinutes", "passRemainingCreditKrw"))
                    assertThat(b.get(field)).as(field).isEqualTo(a.get(field));
                assertThat(b.get("checkedInAt")).isEqualTo(a.get("checkedInAt"));
                assertThat(b.get("checkedOutAt")).isEqualTo(a.get("checkedOutAt"));
                assertThat(b.get("moves").size()).isEqualTo(2);
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", movedPass)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", plainPass)).isEqualTo(1);
                assertThat(count("SELECT delta_minutes FROM pass_balance_events WHERE pass_id=?", movedPass))
                        .isEqualTo(count("SELECT delta_minutes FROM pass_balance_events WHERE pass_id=?", plainPass));
                assertThat(count("SELECT delta_krw FROM pass_balance_events WHERE pass_id=?", movedPass))
                        .isEqualTo(count("SELECT delta_krw FROM pass_balance_events WHERE pass_id=?", plainPass));
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id IS NOT NULL AND customer_id=?", movedCustomer)).isZero();
            }
        }
    }

    @Test void aMoveNeverTouchesThePassLedgerOrBalance() throws Exception {
        clock.fixed = TEN;
        long customer = customer(first, "원장 고객");
        long pass = pass(customer, first, time(120));
        try (var s = login(writer)) {
            long id = entered(s, seat(first), customer, pass);
            var before = jdbc.queryForMap("SELECT remaining_minutes, version, status FROM passes WHERE id=?", pass);
            clock.fixed = TEN.plusSeconds(1500);
            moved(s, id, seat(first));
            assertThat(jdbc.queryForMap("SELECT remaining_minutes, version, status FROM passes WHERE id=?", pass)).isEqualTo(before);
            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isZero();
        }
    }

    // ---- the database stops what a race slips past the checks -----------------------------------------------

    @Test void aUniqueKeyThatFiresAfterTheChecksStillAnswersSeatOccupiedAndRollsBack() throws Exception {
        try (var s = login(writer)) {
            var sitting = sitting(s);
            long id = sitting[0], from = sitting[1];
            long occupied = sitting(s)[1];
            doReturn(false).when(sessions).existsBySeatIdAndStatus(anyLong(), eq(SeatSession.Status.ACTIVE));
            refused(moveTo(s, id, occupied), 409, "SEAT_OCCUPIED");
            assertThat(seatOf(id)).isEqualTo(from);
            assertThat(moveRows(id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='SEAT_MOVED'", id)).isZero();
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='seat-sessions:move'", writer)).isZero();
        }
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

    static List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).toList(); }

    @Test void onlyOneOfManyStaysMovesToTheSameSeat() throws Exception {
        try (var s = login(writer)) {
            for (int round = 0; round < 3; round++) {
                long target = seat(first);
                var ids = new ArrayList<Long>();
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    long id = sitting(s)[0];
                    ids.add(id);
                    calls.add(() -> moveTo(s, id, target));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(status -> status == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
                responses.stream().filter(response -> response.statusCode() != 200).forEach(response -> refused(response, 409, "SEAT_OCCUPIED"));
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", target)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM seat_session_moves WHERE to_seat_id=?", target)).isEqualTo(1);
                // Every loser still sits where it did, so nobody lost a seat.
                for (long id : ids) assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE id=? AND active_seat_id IS NOT NULL", id)).isEqualTo(1);
            }
        }
    }

    @Test void theSameMoveSentManyTimesAtOnceMovesOnceAndEveryoneGetsTheSameResult() throws Exception {
        try (var s = login(writer)) {
            long id = sitting(s)[0];
            long target = seat(first);
            String key = key();
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) calls.add(() -> move(s, id, to(target), key));
            var responses = race(calls);
            assertThat(statuses(responses)).allMatch(status -> status == 200);
            assertThat(responses.stream().map(this::node).distinct().count()).isEqualTo(1);
            assertThat(moveRows(id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seat-sessions' AND entity_id=? AND action='SEAT_MOVED'", id)).isEqualTo(1);
        }
    }

    @Test void aSeatIsEitherMovedIntoOrCheckedIntoNeverBoth() throws Exception {
        int movedFirst = 0, enteredFirst = 0;
        try (var s = login(writer)) {
            for (int round = 0; round < 12; round++) {
                long id = sitting(s)[0];
                long target = seat(first);
                long customer = customer(first, "경합 입실 고객");
                long pass = pass(customer, first, time(600));
                long headStart = round * 3L;
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> { Thread.sleep(headStart); return moveTo(s, id, target); },
                        () -> checkIn(s, entry(target, customer, pass), key())));
                var moving = responses.get(0);
                var entering = responses.get(1);
                if (moving.statusCode() == 200) {
                    refused(entering, 409, "SEAT_OCCUPIED");
                    assertThat(seatOf(id)).isEqualTo(target);
                    movedFirst++;
                } else {
                    refused(moving, 409, "SEAT_OCCUPIED");
                    assertThat(entering.statusCode()).isEqualTo(201);
                    assertThat(seatOf(id)).isNotEqualTo(target);
                    enteredFirst++;
                }
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id=?", target)).isEqualTo(1);
            }
        }
        assertThat(movedFirst + enteredFirst).isEqualTo(12);
    }

    @Test void aMoveAndTheCheckOutOfTheSameStayAreSerialisedAndChargeOnce() throws Exception {
        int movedFirst = 0, closedFirst = 0;
        try (var s = login(writer); var colleague = login(owner)) {
            for (int round = 0; round < 12; round++) {
                if (clock.fixed == null) clock.fixed = Instant.now();
                long customer = customer(first, "이동·퇴실 경합");
                long pass = pass(customer, first, stored(100_000, 6_000));
                long id = entered(s, seat(first), customer, pass);
                long target = seat(first);
                clock.fixed = clock.fixed.plusSeconds(120);
                long headStart = round * 3L;
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> { Thread.sleep(headStart); return moveTo(s, id, target); },
                        () -> checkOut(colleague, id, key())));
                var moving = responses.get(0);
                var leaving = responses.get(1);
                assertThat(leaving.statusCode()).as(leaving.body()).isEqualTo(200);
                if (moving.statusCode() == 200) {
                    assertThat(seatOf(id)).isEqualTo(target);
                    movedFirst++;
                } else {
                    refused(moving, 409, "SESSION_ALREADY_CLOSED");
                    assertThat(moveRows(id)).isZero();
                    closedFirst++;
                }
                assertThat(text("SELECT status FROM seat_sessions WHERE id=?", id)).isEqualTo("CLOSED");
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id IS NOT NULL AND id=?", id)).isZero();
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isEqualTo(1);
                assertThat(count("SELECT delta_krw + (100000 - remaining_credit_krw) FROM pass_balance_events e JOIN passes p ON p.id=e.pass_id WHERE p.id=?", pass)).isZero();
            }
        }
        assertThat(movedFirst + closedFirst).isEqualTo(12);
    }

    @Test void twoStaysSwappingSeatsAtOnceNeitherDeadlockNorLoseASeat() throws Exception {
        try (var s = login(writer)) {
            for (int round = 0; round < 5; round++) {
                var a = sitting(s);
                var b = sitting(s);
                var responses = race(List.<Callable<HttpResponse<String>>>of(() -> moveTo(s, a[0], b[1]), () -> moveTo(s, b[0], a[1])));
                responses.forEach(response -> refused(response, 409, "SEAT_OCCUPIED"));
                assertThat(seatOf(a[0])).isEqualTo(a[1]);
                assertThat(seatOf(b[0])).isEqualTo(b[1]);
                assertThat(count("SELECT COUNT(*) FROM seat_sessions WHERE active_seat_id IN (?,?)", a[1], b[1])).isEqualTo(2);
            }
        }
    }

    @Test void aMoveAndAStopOfTheTargetSeatNeverBothSucceed() throws Exception {
        long manager = person("EMPLOYEE", "점장");
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'MANAGER','ACTIVE')", manager, first);
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='operations:write' WHERE m.employee_id=?", manager);
        int movedFirst = 0, stoppedFirst = 0;
        try (var s = login(writer); var m = login(manager)) {
            for (int round = 0; round < 12; round++) {
                long id = sitting(s)[0];
                long target = seat(first);
                var body = Map.<String, Object>of("version", 1, "status", "운영 중단", "reason", "점검");
                long headStart = round * 3L;
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> { Thread.sleep(headStart); return moveTo(s, id, target); },
                        () -> http(m.client(), "POST", "/api/seats/" + target + "/status", m.token(), body, null)));
                var moving = responses.get(0);
                var stopping = responses.get(1);
                if (moving.statusCode() == 200) {
                    refused(stopping, 409, "SEAT_OCCUPIED");
                    assertThat(text("SELECT status FROM seats WHERE id=?", target)).isEqualTo("AVAILABLE");
                    movedFirst++;
                } else {
                    refused(moving, 409, "SEAT_BLOCKED");
                    assertThat(stopping.statusCode()).isEqualTo(200);
                    assertThat(seatOf(id)).isNotEqualTo(target);
                    stoppedFirst++;
                }
            }
        }
        assertThat(movedFirst + stoppedFirst).isEqualTo(12);
    }
}
