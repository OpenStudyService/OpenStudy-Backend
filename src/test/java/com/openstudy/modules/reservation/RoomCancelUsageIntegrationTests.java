package com.openstudy.modules.reservation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.reservation.repository.RoomReservationSlotRepository;
import com.openstudy.modules.reservation.repository.RoomUsageRepository;
import com.openstudy.modules.reservation.service.RoomReservationService;
import com.openstudy.modules.reservation.service.RoomService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Issue #70: HTTP and MySQL behaviour of cancelling a booking with its refund and of the use of a room (space-operations-api.md). */
@MySqlIntegrationTest
class RoomCancelUsageIntegrationTests {
    /** Far from today, so a booking is never in the past. Times of the contract read as Korean wall clock. */
    static final String DAY = "2099-03-04";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired RoomService roomService;
    @Autowired RoomReservationService reservationService;
    @MockitoSpyBean RoomReservationSlotRepository slotRepository;
    @MockitoSpyBean RoomUsageRepository usageRepository;
    @MockitoSpyBean EmployeeAccountService employees;
    @LocalServerPort int port;

    final MutableClock clock = new MutableClock();
    Object serviceClock;
    long owner, cashier, reader, plain, operator, outsider, twoBranches, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    int roomNumber;

    /** null follows the real clock. Only the reservation service reads it, so sessions keep living on real time. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @BeforeEach void setup() {
        Object target = AopTestUtils.getUltimateTargetObject(reservationService);
        serviceClock = ReflectionTestUtils.getField(target, "clock");
        ReflectionTestUtils.setField(target, "clock", clock);
        owner = person("OWNER", "사장");
        cashier = person("EMPLOYEE", "계산 직원"); reader = person("EMPLOYEE", "장부 직원"); plain = person("EMPLOYEE", "일반 직원");
        operator = person("EMPLOYEE", "운영 직원"); outsider = person("EMPLOYEE", "둘째지점 직원"); twoBranches = person("EMPLOYEE", "겸임 직원");
        first = branch("취소 첫 지점"); second = branch("취소 둘째 지점");
        member(cashier, first, "payments:write");
        member(reader, first, "payments:read");
        member(plain, first);
        member(operator, first, "operations:write");
        member(outsider, second, "payments:write", "operations:write");
        member(twoBranches, first, "payments:write");
        member(twoBranches, second, "payments:read");
    }

    @AfterEach void clean() {
        Object target = AopTestUtils.getUltimateTargetObject(reservationService);
        ReflectionTestUtils.setField(target, "clock", serviceClock);
        Mockito.reset(slotRepository, usageRepository, employees);
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM room_usages WHERE reservation_id IN (SELECT id FROM room_reservations WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM room_reservations WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM rooms WHERE branch_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.branch_id=?", id);
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
        String email = UUID.randomUUID() + "@cancel.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("cancel-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "cancel-fixture");
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

    long customer(long branch) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", "룸 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "cancel-fixture").id());
        customerIds.add(id);
        return id;
    }

    long room(long branch) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomNo", "C-" + ++roomNumber); body.put("name", "룸 " + roomNumber);
        body.put("capacity", 4); body.put("ratePerHourKrw", 8000L);
        return Long.parseLong(roomService.create(owner, "all", key(), body, "cancel-fixture").id());
    }

    static String key() { return UUID.randomUUID().toString(); }
    static Instant kst(String time) { return OffsetDateTime.parse(DAY + "T" + (time.length() == 4 ? "0" : "") + time + ":00+09:00").toInstant(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    Instant instant(String sql, Object... args) { return jdbc.queryForObject(sql, Timestamp.class, args).toInstant(); }
    long refunds(long reservation) {
        return count("SELECT COUNT(*) FROM refunds f JOIN room_reservations r ON r.payment_id=f.payment_id WHERE r.id=?", reservation);
    }
    long heldSlots(long reservation) { return count("SELECT COUNT(*) FROM room_reservation_slots WHERE reservation_id=?", reservation); }
    long usages(long reservation) { return count("SELECT COUNT(*) FROM room_usages WHERE reservation_id=?", reservation); }
    String status(long reservation) { return text("SELECT status FROM room_reservations WHERE id=?", reservation); }
    String paymentStatus(long reservation) {
        return text("SELECT p.status FROM payments p JOIN room_reservations r ON r.payment_id=p.id WHERE r.id=?", reservation);
    }
    long events(long branch, String type, long id) {
        return count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type=? AND entity_id=?", branch, type, id);
    }
    long audits(String type, long id, String action) {
        return count("SELECT COUNT(*) FROM audit_events WHERE entity_type=? AND entity_id=? AND action=?", type, id, action);
    }

    static Map<String, Object> booking(long branch, long room, long customer, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomId", Long.toString(room)); body.put("customerId", Long.toString(customer));
        body.put("startAt", kst(start).toString()); body.put("endAt", kst(end).toString()); body.put("partySize", 2); body.put("method", "카드");
        return body;
    }

    static Map<String, Object> reasoned(long version, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version); body.put("reason", reason);
        return body;
    }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "cancel-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> book(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/room-reservations", session.token(), body, key);
    }

    HttpResponse<String> cancel(Session session, long id, long version, String reason, String key) throws Exception {
        return http(session.client(), "POST", "/api/room-reservations/" + id + "/cancel", session.token(), reasoned(version, reason), key);
    }

    HttpResponse<String> cancel(Session session, long id, long version) throws Exception { return cancel(session, id, version, "고객 요청", key()); }

    HttpResponse<String> start(Session session, long id, long version) throws Exception {
        return http(session.client(), "POST", "/api/room-reservations/" + id + "/start", session.token(), Map.of("version", version), null);
    }

    HttpResponse<String> complete(Session session, long id, long version) throws Exception {
        return http(session.client(), "POST", "/api/room-reservations/" + id + "/complete", session.token(), Map.of("version", version), null);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    long booked(Session session, Map<String, Object> body) throws Exception {
        var response = book(session, body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response).get("id").asLong();
    }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).isEqualTo(code);
    }

    void confirmed(long reservation) {
        assertThat(status(reservation)).isEqualTo("CONFIRMED");
        assertThat(paymentStatus(reservation)).isEqualTo("APPROVED");
        assertThat(refunds(reservation)).isZero();
    }

    static String calendar(long branch) { return "/api/room-reservations/calendar?branchId=" + branch + "&from=" + kst("00:00") + "&to=" + kst("23:00"); }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    // ---- cancelling: who and what ----------------------------------------------------------------------------

    @Test void cancellingNeedsPaymentsWriteAtTheReservationBranch() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var cash = login(cashier); var book = login(reader); var staff = login(plain); var ops = login(operator);
                var other = login(outsider); var both = login(twoBranches); var boss = login(owner)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            refused(cancel(book, id, 1), 403, "FORBIDDEN");
            refused(cancel(staff, id, 1), 403, "FORBIDDEN");
            refused(cancel(ops, id, 1), 403, "FORBIDDEN");
            assertThat(cancel(other, id, 1).statusCode()).as("outside the branch it does not exist").isEqualTo(404);
            var outOfScope = http(both.client(), "POST", "/api/room-reservations/" + id + "/cancel?scope=" + second, both.token(), reasoned(1, "범위 밖"), key());
            assertThat(outOfScope.statusCode()).isEqualTo(404);
            assertThat(cancel(cash, 999_999_999L, 1).statusCode()).isEqualTo(404);
            confirmed(id);
            assertThat(ok(cancel(boss, id, 1)).get("status").asString()).isEqualTo("취소");
            long next = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            assertThat(ok(cancel(cash, next, 1)).get("status").asString()).isEqualTo("취소");
        }
    }

    @Test void aReasonIsRequiredAndNothingButVersionAndReasonIsAccepted() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "15:00"));
            String path = "/api/room-reservations/" + id + "/cancel";
            refused(http(s.client(), "POST", path, s.token(), Map.of("version", 1), key()), 422, "VALIDATION_FAILED");
            refused(cancel(s, id, 1, "", key()), 422, "VALIDATION_FAILED");
            refused(cancel(s, id, 1, "   ", key()), 422, "VALIDATION_FAILED");
            refused(cancel(s, id, 1, "가".repeat(501), key()), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", path, s.token(), Map.of("version", 1, "reason", 5), key()), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", path, s.token(), Map.of("reason", "버전 없음"), key()), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", path, s.token(), Map.of("version", 1, "reason", "금액", "amountKrw", 1), key()), 422, "VALIDATION_FAILED");
            refused(cancel(s, id, 1, "키 없음", null), 422, "VALIDATION_FAILED");
            refused(cancel(s, id, 1, "짧은 키", "short"), 422, "VALIDATION_FAILED");
            confirmed(id);
            assertThat(ok(cancel(s, id, 1, "가".repeat(500), key())).get("cancelReason").asString()).hasSize(500);
        }
    }

    @Test void theStartInstantIsTheBoundary() throws Exception {
        long customer = customer(first);
        long room = room(first);
        Instant start = kst("14:00");
        try (var s = login(cashier)) {
            long allowed = booked(s, booking(first, room, customer, "14:00", "15:00"));
            clock.fixed = start.minusNanos(1000);
            var view = ok(cancel(s, allowed, 1));
            assertThat(view.get("cancelledAt").asString()).isEqualTo(start.minusNanos(1000).toString());
            clock.fixed = null;

            long atTheInstant = booked(s, booking(first, room, customer, "14:00", "15:00"));
            clock.fixed = start.minusNanos(1000);
            assertThat(ok(get(s, "/api/room-reservations/" + atTheInstant)).get("canCancel").asBoolean()).isTrue();
            clock.fixed = start;
            assertThat(ok(get(s, "/api/room-reservations/" + atTheInstant)).get("canCancel").asBoolean()).isFalse();
            refused(cancel(s, atTheInstant, 1), 409, "RESERVATION_NOT_CANCELLABLE");
            clock.fixed = start.plusSeconds(1);
            refused(cancel(s, atTheInstant, 1), 409, "RESERVATION_NOT_CANCELLABLE");
            confirmed(atTheInstant);
            assertThat(heldSlots(atTheInstant)).isEqualTo(1);
        }
    }

    @Test void onlyAConfirmedReservationCanBeCancelled() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            long started = booked(s, booking(first, room, customer, "10:00", "11:00"));
            ok(start(s, started, 1));
            refused(cancel(s, started, 2), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(status(started)).isEqualTo("IN_USE");

            ok(complete(s, started, 2));
            refused(cancel(s, started, 3), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(status(started)).isEqualTo("COMPLETED");
            assertThat(refunds(started)).isZero();
            assertThat(paymentStatus(started)).isEqualTo("APPROVED");

            long cancelled = booked(s, booking(first, room, customer, "12:00", "13:00"));
            ok(cancel(s, cancelled, 1));
            refused(cancel(s, cancelled, 2), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(refunds(cancelled)).isEqualTo(1);

            var body = new LinkedHashMap<>(booking(first, room, customer, "15:00", "16:00"));
            body.put("mockOutcome", "FAILED");
            long unpaid = booked(s, body);
            refused(cancel(s, unpaid, 1), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(status(unpaid)).isEqualTo("PAYMENT_FAILED");
            assertThat(paymentStatus(unpaid)).isEqualTo("FAILED");
            assertThat(refunds(unpaid)).isZero();
        }
    }

    @Test void aCancellationKeepsTheLedgerThePaymentTheReservationAndTheSlotsInStep() throws Exception {
        long customer = customer(first);
        long room = room(first);
        Instant at = kst("09:30");
        try (var s = login(cashier); var book = login(reader)) {
            long id = booked(s, booking(first, room, customer, "14:00", "17:00"));
            long payment = count("SELECT payment_id FROM room_reservations WHERE id=?", id);
            Instant approved = instant("SELECT approved_at FROM payments WHERE id=?", payment);
            assertThat(heldSlots(id)).isEqualTo(3);
            clock.fixed = at;
            var view = ok(cancel(s, id, 1, "  일정 변경  ", key()));

            assertThat(view.get("status").asString()).isEqualTo("취소");
            assertThat(view.get("version").asLong()).isEqualTo(2);
            assertThat(view.get("canCancel").asBoolean()).isFalse();
            assertThat(view.get("cancelReason").asString()).isEqualTo("일정 변경");
            assertThat(view.get("cancelledAt").asString()).isEqualTo(at.toString());
            assertThat(view.at("/cancelledBy/id").asString()).isEqualTo(Long.toString(cashier));
            assertThat(view.at("/cancelledBy/name").asString()).isEqualTo("계산 직원");
            assertThat(view.at("/billing/paymentStatus").asString()).isEqualTo("환불");
            assertThat(view.at("/billing/amountKrw").asLong()).as("the amount of the booking stays").isEqualTo(24000);
            assertThat(view.get("usage").isNull()).isTrue();

            var refund = jdbc.queryForMap("SELECT amount_krw a, processing_branch_id b, processed_by p, reason r, idempotency_key k FROM refunds WHERE payment_id=?", payment);
            assertThat(((Number) refund.get("a")).longValue()).isEqualTo(24000);
            assertThat(((Number) refund.get("b")).longValue()).as("the original payment's branch").isEqualTo(first);
            assertThat(((Number) refund.get("p")).longValue()).isEqualTo(cashier);
            assertThat(refund.get("r")).isEqualTo("일정 변경");
            assertThat(refund.get("k").toString()).startsWith("RFD-").hasSizeLessThanOrEqualTo(120);
            assertThat(instant("SELECT refunded_at FROM refunds WHERE payment_id=?", payment)).isEqualTo(at);

            assertThat(text("SELECT status FROM payments WHERE id=?", payment)).isEqualTo("REFUNDED");
            assertThat(count("SELECT amount_krw FROM payments WHERE id=?", payment)).isEqualTo(24000);
            assertThat(instant("SELECT approved_at FROM payments WHERE id=?", payment)).isEqualTo(approved);
            assertThat(status(id)).isEqualTo("CANCELLED");
            assertThat(text("SELECT cancel_reason FROM room_reservations WHERE id=?", id)).isEqualTo("일정 변경");
            assertThat(heldSlots(id)).isZero();

            var paid = ok(get(book, "/api/payments/" + payment));
            assertThat(paid.get("status").asString()).isEqualTo("환불");
            assertThat(paid.get("purpose").asString()).isEqualTo("스터디룸");
            assertThat(paid.get("canRefund").asBoolean()).isFalse();
            assertThat(paid.get("roomReservationId").asString()).isEqualTo(Long.toString(id));
            assertThat(paid.at("/refund/amountKrw").asLong()).isEqualTo(24000);
            assertThat(paid.at("/refund/reason").asString()).isEqualTo("일정 변경");
            assertThat(paid.at("/refund/refundedAt").asString()).isEqualTo(at.toString());
            assertThat(paid.at("/refund/processingBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(paid.at("/refund/processedBy/name").asString()).isEqualTo("계산 직원");

            refused(http(s.client(), "POST", "/api/payments/" + payment + "/refund", s.token(), Map.of("reason", "또 환불"), key()), 409, "PAYMENT_NOT_REFUNDABLE");
            assertThat(refunds(id)).isEqualTo(1);
        }
    }

    @Test void aCancellationLeavesAuditAndEventsWithoutAmounts() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "15:00"));
            long payment = count("SELECT payment_id FROM room_reservations WHERE id=?", id);
            ok(cancel(s, id, 1, "고객 요청", key()));
            assertThat(audits("room-reservations", id, "CANCELLED")).isEqualTo(1);
            assertThat(audits("payments", payment, "REFUNDED")).isEqualTo(1);
            assertThat(text("SELECT reason FROM audit_events WHERE entity_type='room-reservations' AND entity_id=? AND action='CANCELLED'", id)).isEqualTo("고객 요청");
            assertThat(text("SELECT CAST(before_json AS CHAR) FROM audit_events WHERE entity_type='room-reservations' AND entity_id=? AND action='CANCELLED'", id)).contains("CONFIRMED");
            assertThat(text("SELECT CAST(after_json AS CHAR) FROM audit_events WHERE entity_type='room-reservations' AND entity_id=? AND action='CANCELLED'", id)).contains("CANCELLED", "refundId");
            assertThat(events(first, "room-reservations", id)).as("booking and cancellation").isEqualTo(2);
            assertThat(events(first, "payments", payment)).as("charge and refund").isEqualTo(2);
        }
    }

    @Test void aFailureAnywhereRollsTheCancellationBackAndTheSameKeyStillWorks() throws Exception {
        long customer = customer(first);
        long room = room(first);
        String requestKey = key();
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "16:00"));
            long payment = count("SELECT payment_id FROM room_reservations WHERE id=?", id);
            doThrow(new IllegalStateException("slot delete failed")).when(slotRepository).deleteAllOf(anyLong());
            assertThat(cancel(s, id, 1, "고객 요청", requestKey).statusCode()).isEqualTo(500);
            confirmed(id);
            assertThat(count("SELECT version FROM room_reservations WHERE id=?", id)).isZero();
            assertThat(count("SELECT version FROM payments WHERE id=?", payment)).isZero();
            assertThat(heldSlots(id)).isEqualTo(2);
            assertThat(audits("room-reservations", id, "CANCELLED")).isZero();
            assertThat(audits("payments", payment, "REFUNDED")).isZero();
            assertThat(events(first, "payments", payment)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='room-reservations:cancel'", cashier)).isZero();
            Mockito.reset(slotRepository);
            ok(cancel(s, id, 1, "고객 요청", requestKey));
            assertThat(refunds(id)).isEqualTo(1);
            assertThat(heldSlots(id)).isZero();
        }
    }

    // ---- booking again ---------------------------------------------------------------------------------------

    @Test void theSameHourCanBeBookedAgainAndTheCancelledRecordsStay() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            long cancelled = booked(s, booking(first, room, customer, "14:00", "16:00"));
            long oldPayment = count("SELECT payment_id FROM room_reservations WHERE id=?", cancelled);
            refused(book(s, booking(first, room, another, "15:00", "16:00"), key()), 409, "ROOM_TIME_CONFLICT");
            ok(cancel(s, cancelled, 1));

            long rebooked = booked(s, booking(first, room, another, "14:00", "16:00"));
            long newPayment = count("SELECT payment_id FROM room_reservations WHERE id=?", rebooked);
            assertThat(rebooked).isNotEqualTo(cancelled);
            assertThat(newPayment).isNotEqualTo(oldPayment);
            assertThat(status(cancelled)).isEqualTo("CANCELLED");
            assertThat(paymentStatus(cancelled)).isEqualTo("REFUNDED");
            assertThat(status(rebooked)).isEqualTo("CONFIRMED");
            assertThat(paymentStatus(rebooked)).isEqualTo("APPROVED");
            assertThat(heldSlots(cancelled)).isZero();
            assertThat(heldSlots(rebooked)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM payments WHERE id IN (?,?)", oldPayment, newPayment)).isEqualTo(2);
            assertThat(refunds(rebooked)).isZero();

            var items = ok(get(s, calendar(first))).get("items");
            assertThat(items.size()).as("the calendar lists every status but a failed payment").isEqualTo(2);
            refused(book(s, booking(first, room, customer, "14:00", "15:00"), key()), 409, "ROOM_TIME_CONFLICT");

            ok(cancel(s, rebooked, 1));
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id IN (?,?)", oldPayment, newPayment)).as("one refund per payment").isEqualTo(2);
        }
    }

    // ---- request keys and versions -----------------------------------------------------------------------------

    @Test void retryingWithTheSameKeyAnswersTheFirstResultAndRefundsOnce() throws Exception {
        long customer = customer(first);
        long room = room(first);
        String requestKey = key();
        try (var s = login(cashier); var other = login(twoBranches)) {
            long id = booked(s, booking(first, room, customer, "14:00", "16:00"));
            long payment = count("SELECT payment_id FROM room_reservations WHERE id=?", id);
            var firstTry = ok(cancel(s, id, 1, "고객 요청", requestKey));
            var again = ok(cancel(s, id, 1, "고객 요청", requestKey));
            assertThat(again).isEqualTo(firstTry);
            assertThat(refunds(id)).isEqualTo(1);
            assertThat(audits("room-reservations", id, "CANCELLED")).isEqualTo(1);
            assertThat(audits("payments", payment, "REFUNDED")).isEqualTo(1);
            assertThat(events(first, "room-reservations", id)).isEqualTo(2);

            refused(cancel(s, id, 1, "다른 사유", requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(cancel(s, id, 2, "고객 요청", requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(cancel(other, id, 1, "고객 요청", requestKey), 409, "VERSION_CONFLICT");
            refused(cancel(s, id, 1, "고객 요청", key()), 409, "VERSION_CONFLICT");
            refused(cancel(s, id, 2, "고객 요청", key()), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(refunds(id)).isEqualTo(1);
        }
    }

    @Test void aRefusalIsNotStoredAndAnExpiredKeyNeverRefundsTwice() throws Exception {
        long customer = customer(first);
        long room = room(first);
        String requestKey = key();
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "16:00"));
            refused(cancel(s, id, 9, "고객 요청", requestKey), 409, "VERSION_CONFLICT");
            clock.fixed = kst("09:00").minus(Duration.ofDays(3));
            ok(cancel(s, id, 1, "고객 요청", requestKey));
            clock.fixed = kst("09:00").minus(Duration.ofDays(1));
            refused(cancel(s, id, 1, "고객 요청", requestKey), 409, "VERSION_CONFLICT");
            refused(cancel(s, id, 2, "고객 요청", requestKey), 409, "RESERVATION_NOT_CANCELLABLE");
            assertThat(refunds(id)).isEqualTo(1);
        }
    }

    @Test void aStaleVersionIsAConflictWithItsReloadAddress() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "15:00"));
            var stale = cancel(s, id, 7);
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/room-reservations/" + id);
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(1);
            refused(start(s, id, 7), 409, "VERSION_CONFLICT");
            ok(start(s, id, 1));
            refused(complete(s, id, 1), 409, "VERSION_CONFLICT");
            refused(cancel(s, id, 1), 409, "VERSION_CONFLICT");
            stillInUse(id);
        }
    }

    void stillInUse(long id) {
        assertThat(status(id)).isEqualTo("IN_USE");
        assertThat(refunds(id)).isZero();
    }

    // ---- using the room ------------------------------------------------------------------------------------------

    @Test void anyEmployeeOfTheBranchStartsAndCompletes() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var cash = login(cashier); var staff = login(plain); var ops = login(operator); var book = login(reader);
                var other = login(outsider); var both = login(twoBranches); var boss = login(owner)) {
            long id = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            assertThat(start(other, id, 1).statusCode()).as("outside the branch it does not exist").isEqualTo(404);
            assertThat(complete(other, id, 1).statusCode()).isEqualTo(404);
            assertThat(http(both.client(), "POST", "/api/room-reservations/" + id + "/start?scope=" + second, both.token(), Map.of("version", 1), null).statusCode()).isEqualTo(404);
            assertThat(start(staff, 999_999_999L, 1).statusCode()).isEqualTo(404);
            ok(start(staff, id, 1));
            ok(complete(staff, id, 2));
            long next = booked(cash, booking(first, room, customer, "11:00", "12:00"));
            ok(start(ops, next, 1));
            ok(complete(book, next, 2));
            long third = booked(cash, booking(first, room, customer, "12:00", "13:00"));
            ok(start(boss, third, 1));
            ok(complete(boss, third, 2));
        }
    }

    @Test void theBodyIsTheVersionAlone() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            String path = "/api/room-reservations/" + id + "/start";
            refused(http(s.client(), "POST", path, s.token(), Map.of(), null), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", path, s.token(), Map.of("version", "1"), null), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", path, s.token(), Map.of("version", 1, "startedAt", "2099-01-01T00:00:00Z"), null), 422, "VALIDATION_FAILED");
            ok(start(s, id, 1));
            refused(http(s.client(), "POST", "/api/room-reservations/" + id + "/complete", s.token(), Map.of("version", 2, "endedAt", "x"), null), 422, "VALIDATION_FAILED");
            assertThat(status(id)).isEqualTo("IN_USE");
        }
    }

    @Test void startingAndCompletingWriteTheUsageAndMoveTheStatus() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var cash = login(cashier); var staff = login(plain); var ops = login(operator)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            long roomVersion = count("SELECT version FROM rooms WHERE id=?", room);
            refused(complete(staff, id, 1), 409, "INVALID_STATE");
            clock.fixed = kst("13:50");
            var started = ok(start(staff, id, 1));
            assertThat(started.get("status").asString()).isEqualTo("이용 중");
            assertThat(started.get("version").asLong()).isEqualTo(2);
            assertThat(started.get("canCancel").asBoolean()).isFalse();
            assertThat(started.at("/usage/startedAt").asString()).as("server time, not the booked start").isEqualTo(kst("13:50").toString());
            assertThat(started.at("/usage/startedBy/name").asString()).isEqualTo("일반 직원");
            assertThat(started.at("/usage/endedAt").isNull()).isTrue();
            assertThat(started.at("/usage/endedBy").isNull()).isTrue();
            assertThat(fields(started.get("usage"))).containsExactlyInAnyOrder("startedAt", "endedAt", "startedBy", "endedBy");
            assertThat(status(id)).isEqualTo("IN_USE");
            assertThat(instant("SELECT started_at FROM room_usages WHERE reservation_id=?", id)).isEqualTo(kst("13:50"));
            assertThat(count("SELECT started_by FROM room_usages WHERE reservation_id=?", id)).isEqualTo(plain);
            assertThat(count("SELECT version FROM rooms WHERE id=?", room)).as("using a room does not touch its ledger").isEqualTo(roomVersion);
            refused(start(staff, id, 2), 409, "INVALID_STATE");

            clock.fixed = kst("15:10");
            var done = ok(complete(ops, id, 2));
            assertThat(done.get("status").asString()).isEqualTo("완료");
            assertThat(done.get("version").asLong()).isEqualTo(3);
            assertThat(done.at("/usage/startedAt").asString()).isEqualTo(kst("13:50").toString());
            assertThat(done.at("/usage/endedAt").asString()).isEqualTo(kst("15:10").toString());
            assertThat(done.at("/usage/startedBy/name").asString()).isEqualTo("일반 직원");
            assertThat(done.at("/usage/endedBy/name").asString()).isEqualTo("운영 직원");
            assertThat(status(id)).isEqualTo("COMPLETED");
            assertThat(instant("SELECT ended_at FROM room_usages WHERE reservation_id=?", id)).isEqualTo(kst("15:10"));
            assertThat(usages(id)).isEqualTo(1);
            refused(start(staff, id, 3), 409, "INVALID_STATE");
            refused(complete(staff, id, 3), 409, "INVALID_STATE");

            assertThat(audits("room-reservations", id, "IN_USE")).isEqualTo(1);
            assertThat(audits("room-reservations", id, "COMPLETED")).isEqualTo(1);
            assertThat(text("SELECT CAST(before_json AS CHAR) FROM audit_events WHERE entity_type='room-reservations' AND entity_id=? AND action='COMPLETED'", id)).contains("IN_USE");
            assertThat(events(first, "room-reservations", id)).as("booking, start and completion").isEqualTo(3);
            assertThat(paymentStatus(id)).as("using a room moves no money").isEqualTo("APPROVED");
        }
    }

    @Test void aCancelledOrUnpaidReservationCannotStart() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long cancelled = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            ok(cancel(cash, cancelled, 1));
            refused(start(staff, cancelled, 2), 409, "INVALID_STATE");
            refused(complete(staff, cancelled, 2), 409, "INVALID_STATE");
            var body = new LinkedHashMap<>(booking(first, room, customer, "11:00", "12:00"));
            body.put("mockOutcome", "FAILED");
            long unpaid = booked(cash, body);
            refused(start(staff, unpaid, 1), 409, "INVALID_STATE");
            assertThat(usages(cancelled) + usages(unpaid)).isZero();
        }
    }

    @Test void theBookedPeriodIsNeverComparedAtTheStart() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long early = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            long late = booked(cash, booking(first, room, customer, "16:00", "17:00"));
            clock.fixed = kst("14:00").minus(Duration.ofHours(5));
            ok(start(s, early, 1));
            ok(complete(s, early, 2));
            clock.fixed = kst("22:00");
            assertThat(ok(start(s, late, 1)).at("/usage/startedAt").asString()).isEqualTo(kst("22:00").toString());
        }
    }

    @Test void aRoomOutOfServiceRefusesTheStartButNotTheCompletion() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier); var ops = login(operator)) {
            long using = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            long waiting = booked(cash, booking(first, room, customer, "12:00", "13:00"));
            ok(start(s, using, 1));
            ok(http(ops.client(), "POST", "/api/rooms/" + room + "/status", ops.token(), Map.of("version", 1, "status", "운영 중단", "reason", "수리"), null));
            var refused = start(s, waiting, 1);
            refused(refused, 409, "ROOM_BLOCKED");
            assertThat(usages(waiting)).isZero();
            assertThat(ok(complete(s, using, 2)).get("status").asString()).isEqualTo("완료");
            refused(start(s, waiting, 1), 409, "ROOM_BLOCKED");
            assertThat(ok(cancel(cash, waiting, 1)).get("status").asString()).as("the manager cancels what stays booked").isEqualTo("취소");
        }
    }

    @Test void aStoppedBranchAllowsRefundsAndCompletionsOnly() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long refundable = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            long using = booked(cash, booking(first, room, customer, "12:00", "13:00"));
            long waiting = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            ok(start(s, using, 1));
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);

            refused(start(s, waiting, 1), 409, "BRANCH_NOT_ACTIVE");
            refused(book(cash, booking(first, room, customer, "20:00", "21:00"), key()), 409, "BRANCH_NOT_ACTIVE");
            assertThat(ok(cancel(cash, refundable, 1)).get("status").asString()).isEqualTo("취소");
            assertThat(refunds(refundable)).isEqualTo(1);
            assertThat(text("SELECT processing_branch_id FROM refunds f JOIN room_reservations r ON r.payment_id=f.payment_id WHERE r.id=?", refundable)).isEqualTo(Long.toString(first));
            assertThat(ok(complete(s, using, 2)).get("status").asString()).isEqualTo("완료");
            assertThat(ok(get(s, "/api/room-reservations/" + using)).get("status").asString()).isEqualTo("완료");
        }
    }

    @Test void onlyOneReservationUsesARoomAtATime() throws Exception {
        long customer = customer(first);
        long room = room(first), other = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long using = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            long next = booked(cash, booking(first, room, customer, "11:00", "12:00"));
            long elsewhere = booked(cash, booking(first, other, customer, "10:00", "11:00"));
            ok(start(s, using, 1));
            refused(start(s, next, 1), 409, "ROOM_IN_USE");
            assertThat(status(next)).isEqualTo("CONFIRMED");
            assertThat(usages(next)).isZero();
            ok(start(s, elsewhere, 1));
            ok(complete(s, using, 2));
            ok(start(s, next, 1));
        }
    }

    @Test void theStartChecksRunInTheContractOrder() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier); var ops = login(operator)) {
            long using = booked(cash, booking(first, room, customer, "09:00", "10:00"));
            long cancelled = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            long waiting = booked(cash, booking(first, room, customer, "11:00", "12:00"));
            ok(start(s, using, 1));
            ok(cancel(cash, cancelled, 1));
            refused(start(s, waiting, 1), 409, "ROOM_IN_USE");
            ok(http(ops.client(), "POST", "/api/rooms/" + room + "/status", ops.token(), Map.of("version", 1, "status", "운영 중단", "reason", "수리"), null));
            refused(start(s, waiting, 1), 409, "ROOM_BLOCKED");
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(start(s, waiting, 1), 409, "BRANCH_NOT_ACTIVE");
            refused(start(s, cancelled, 2), 409, "INVALID_STATE");
            refused(start(s, cancelled, 1), 409, "VERSION_CONFLICT");
        }
    }

    // ---- completing early ---------------------------------------------------------------------------------------

    List<Instant> slotsOf(long reservation) {
        return jdbc.queryForList("SELECT slot_start_at FROM room_reservation_slots WHERE reservation_id=? ORDER BY slot_start_at", Timestamp.class, reservation)
                .stream().map(Timestamp::toInstant).toList();
    }

    @Test void completingEarlyFreesTheHoursNotStartedAndKeepsTheOneInProgress() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "17:00"));
            clock.fixed = kst("14:05");
            ok(start(s, id, 1));
            refused(book(cash, booking(first, room, another, "16:00", "17:00"), key()), 409, "ROOM_TIME_CONFLICT");
            clock.fixed = kst("15:30");
            ok(complete(s, id, 2));
            assertThat(slotsOf(id)).containsExactly(kst("14:00"), kst("15:00"));
            assertThat(text("SELECT CAST(after_json AS CHAR) FROM audit_events WHERE entity_type='room-reservations' AND entity_id=? AND action='COMPLETED'", id)).contains("\"releasedSlots\": 1");

            refused(book(cash, booking(first, room, another, "15:00", "16:00"), key()), 422, "RESERVATION_TIME_INVALID");
            refused(book(cash, booking(first, room, another, "15:00", "17:00"), key()), 422, "RESERVATION_TIME_INVALID");
            long rebooked = booked(cash, booking(first, room, another, "16:00", "17:00"));
            assertThat(status(rebooked)).isEqualTo("CONFIRMED");
            assertThat(slotsOf(rebooked)).containsExactly(kst("16:00"));
            assertThat(status(id)).as("the completed booking and its payment stay").isEqualTo("COMPLETED");
            assertThat(paymentStatus(id)).isEqualTo("APPROVED");
            assertThat(refunds(id)).as("no partial refund").isZero();
            assertThat(ok(get(s, "/api/room-reservations/" + id)).get("endAt").asString()).as("the booked period stays").isEqualTo(kst("17:00").toString());
        }
    }

    @Test void anHourStartingAtTheInstantOfCompletionHasStarted() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first), other = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long exact = booked(cash, booking(first, room, customer, "14:00", "17:00"));
            clock.fixed = kst("14:00");
            ok(start(s, exact, 1));
            clock.fixed = kst("16:00");
            ok(complete(s, exact, 2));
            assertThat(slotsOf(exact)).containsExactly(kst("14:00"), kst("15:00"), kst("16:00"));
            refused(book(cash, booking(first, room, another, "16:00", "17:00"), key()), 422, "RESERVATION_TIME_INVALID");

            clock.fixed = null;
            long before = booked(cash, booking(first, other, customer, "14:00", "17:00"));
            clock.fixed = kst("14:00");
            ok(start(s, before, 1));
            clock.fixed = kst("16:00").minusNanos(1000);
            ok(complete(s, before, 2));
            assertThat(slotsOf(before)).containsExactly(kst("14:00"), kst("15:00"));
            assertThat(status(booked(cash, booking(first, other, another, "16:00", "17:00")))).isEqualTo("CONFIRMED");
        }
    }

    @Test void completingBeforeTheBookedStartFreesEveryHour() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "16:00"));
            clock.fixed = kst("13:00");
            ok(start(s, id, 1));
            clock.fixed = kst("13:30");
            ok(complete(s, id, 2));
            assertThat(slotsOf(id)).isEmpty();
            assertThat(status(booked(cash, booking(first, room, another, "14:00", "16:00")))).isEqualTo("CONFIRMED");
        }
    }

    @Test void completingLateFreesNothing() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "16:00"));
            clock.fixed = kst("14:00");
            ok(start(s, id, 1));
            clock.fixed = kst("17:00");
            ok(complete(s, id, 2));
            assertThat(slotsOf(id)).containsExactly(kst("14:00"), kst("15:00"));
        }
    }

    @Test void aSlotLeftBehindNeverRefusesABooking() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            // Every hour after the completion instant is bookable, right up to the old end of the booking.
            long id = booked(cash, booking(first, room, customer, "10:00", "20:00"));
            clock.fixed = kst("10:00");
            ok(start(s, id, 1));
            clock.fixed = kst("12:30");
            ok(complete(s, id, 2));
            for (int hour = 13; hour < 20; hour++) booked(cash, booking(first, room, another, hour + ":00", (hour + 1) + ":00"));
            refused(book(cash, booking(first, room, another, "12:00", "13:00"), key()), 422, "RESERVATION_TIME_INVALID");
            assertThat(count("SELECT COUNT(*) FROM room_reservation_slots WHERE room_id=?", room)).isEqualTo(10);
        }
    }

    @Test void aFailedCompletionKeepsItsSlotsAndUsageOpen() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "14:00", "17:00"));
            clock.fixed = kst("14:00");
            ok(start(s, id, 1));
            clock.fixed = kst("15:30");
            doThrow(new IllegalStateException("slot delete failed")).when(slotRepository).deleteStartingAfter(anyLong(), any());
            assertThat(complete(s, id, 2).statusCode()).isEqualTo(500);
            assertThat(status(id)).isEqualTo("IN_USE");
            assertThat(count("SELECT COUNT(*) FROM room_usages WHERE reservation_id=? AND ended_at IS NULL", id)).isEqualTo(1);
            assertThat(heldSlots(id)).isEqualTo(3);
        }
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    @Test void theCalendarTheListAndTheDetailAgree() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long waiting = booked(cash, booking(first, room, customer, "09:00", "10:00"));
            long started = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            long done = booked(cash, booking(first, room, customer, "11:00", "12:00"));
            long cancelled = booked(cash, booking(first, room, customer, "12:00", "13:00"));
            ok(start(s, done, 1));
            ok(complete(s, done, 2));
            ok(start(s, started, 1));
            ok(cancel(cash, cancelled, 1));
            var expected = Map.of(waiting, "확정", started, "이용 중", done, "완료", cancelled, "취소");

            for (var viewer : List.of(s, cash)) {
                var list = ok(get(viewer, "/api/room-reservations?roomId=" + room)).get("items");
                var calendar = ok(get(viewer, calendar(first))).get("items");
                assertThat(list.size()).isEqualTo(4);
                assertThat(calendar.size()).isEqualTo(4);
                for (var entry : expected.entrySet()) {
                    var detail = ok(get(viewer, "/api/room-reservations/" + entry.getKey()));
                    assertThat(detail.get("status").asString()).isEqualTo(entry.getValue());
                    assertThat(pick(list, entry.getKey())).isEqualTo(detail);
                    assertThat(pick(calendar, entry.getKey())).isEqualTo(detail);
                    assertThat(fields(detail)).containsExactlyInAnyOrder("id", "version", "status", "branchId", "room", "customer", "startAt", "endAt",
                            "billing", "usage", "cancelledAt", "cancelledBy", "cancelReason", "createdBy", "createdAt", "canCancel");
                }
            }
            for (var entry : expected.entrySet()) {
                var filtered = ok(get(s, "/api/room-reservations?status=" + java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))).get("items");
                assertThat(filtered.size()).isEqualTo(1);
                assertThat(filtered.get(0).get("id").asLong()).isEqualTo(entry.getKey());
            }
            var seen = ok(get(s, "/api/room-reservations/" + done));
            assertThat(seen.at("/usage/endedBy/name").asString()).isEqualTo("일반 직원");
            assertThat(seen.get("billing").isNull()).as("usage needs no payment permission, billing does").isTrue();
            assertThat(ok(get(s, "/api/room-reservations/" + waiting)).get("usage").isNull()).isTrue();
        }
    }

    JsonNode pick(JsonNode items, long id) {
        for (var item : items) if (item.get("id").asLong() == id) return item;
        throw new AssertionError("missing " + id);
    }

    @Test void aListReadsItsUsagesAndPeopleInOneLookup() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var staff = login(plain); var ops = login(operator); var cash = login(cashier)) {
            for (int hour = 10; hour < 16; hour++) {
                long id = booked(cash, booking(first, room, customer, hour + ":00", (hour + 1) + ":00"));
                ok(start(hour % 2 == 0 ? staff : ops, id, 1));
                ok(complete(hour % 2 == 0 ? ops : staff, id, 2));
            }
            Mockito.clearInvocations(employees, usageRepository);
            assertThat(ok(get(staff, "/api/room-reservations?roomId=" + room)).get("items").size()).isEqualTo(6);
            verify(employees, times(1)).names(any());
            verify(usageRepository, times(1)).findByReservationIdIn(any());
            verify(usageRepository, never()).findByReservationId(anyLong());
            Mockito.clearInvocations(employees, usageRepository);
            assertThat(ok(get(staff, calendar(first))).get("items").size()).isEqualTo(6);
            verify(employees, times(1)).names(any());
            verify(usageRepository, times(1)).findByReservationIdIn(any());
        }
    }

    @Test void anEmployeeRemovedFromTheUsageLeavesNoName() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(plain); var cash = login(cashier)) {
            long id = booked(cash, booking(first, room, customer, "10:00", "11:00"));
            ok(start(s, id, 1));
            jdbc.update("UPDATE room_usages SET started_by=NULL WHERE reservation_id=?", id);
            var view = ok(get(s, "/api/room-reservations/" + id));
            assertThat(view.at("/usage/startedBy").isNull()).isTrue();
            assertThat(view.at("/usage/startedAt").isNull()).isFalse();
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

    @Test void simultaneousRetriesWithOneKeyRefundOnce() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            for (int round = 0; round < 3; round++) {
                long id = booked(s, booking(first, room, customer, (10 + round) + ":00", (11 + round) + ":00"));
                String requestKey = key();
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) calls.add(() -> cancel(s, id, 1, "고객 요청", requestKey));
                var responses = race(calls);
                assertThat(statuses(responses)).containsOnly(200);
                assertThat(responses.stream().map(HttpResponse::body).distinct().count()).as("one stored answer").isEqualTo(1);
                assertThat(refunds(id)).isEqualTo(1);
                assertThat(audits("room-reservations", id, "CANCELLED")).isEqualTo(1);
                assertThat(status(id)).isEqualTo("CANCELLED");
                assertThat(heldSlots(id)).isZero();
            }
        }
    }

    @Test void simultaneousCancellationsWithDifferentKeysRefundOnce() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var one = login(cashier); var two = login(twoBranches)) {
            for (int round = 0; round < 3; round++) {
                long id = booked(one, booking(first, room, customer, (10 + round) + ":00", (11 + round) + ":00"));
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    var session = index % 2 == 0 ? one : two;
                    calls.add(() -> cancel(session, id, 1));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "VERSION_CONFLICT");
                assertThat(refunds(id)).isEqualTo(1);
                assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
                assertThat(count("SELECT p.version FROM payments p JOIN room_reservations r ON r.payment_id=p.id WHERE r.id=?", id)).isEqualTo(1);
            }
        }
    }

    @Test void aCancellationRacingAStartLeavesOneOutcome() throws Exception {
        long customer = customer(first);
        long room = room(first);
        int cancelled = 0;
        try (var cash = login(cashier); var staff = login(plain)) {
            for (int round = 0; round < 10; round++) {
                long id = booked(cash, booking(first, room, customer, (8 + round) + ":00", (9 + round) + ":00"));
                int delay = round % 2;
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> { Thread.sleep(delay * 3L); return cancel(cash, id, 1); });
                calls.add(() -> { Thread.sleep((1 - delay) * 3L); return start(staff, id, 1); });
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "VERSION_CONFLICT");
                if (responses.get(0).statusCode() == 200) {
                    cancelled++;
                    assertThat(status(id)).isEqualTo("CANCELLED");
                    assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
                    assertThat(refunds(id)).isEqualTo(1);
                    assertThat(usages(id)).isZero();
                    assertThat(heldSlots(id)).isZero();
                } else {
                    assertThat(status(id)).isEqualTo("IN_USE");
                    assertThat(paymentStatus(id)).isEqualTo("APPROVED");
                    assertThat(refunds(id)).isZero();
                    assertThat(usages(id)).isEqualTo(1);
                    assertThat(heldSlots(id)).isEqualTo(1);
                    ok(complete(staff, id, 2));
                }
            }
        }
        System.out.println("race cancel-vs-start: cancelled first " + cancelled + " of 10");
    }

    @Test void twoStartsInOneRoomLetOnlyOneIn() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var one = login(plain); var two = login(operator); var cash = login(cashier)) {
            for (int round = 0; round < 6; round++) {
                long a = booked(cash, booking(first, room, customer, (6 + round * 2) + ":00", (7 + round * 2) + ":00"));
                long b = booked(cash, booking(first, room, customer, (7 + round * 2) + ":00", (8 + round * 2) + ":00"));
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> start(one, a, 1));
                calls.add(() -> start(two, b, 1));
                var responses = race(calls);
                assertThat(statuses(responses)).as(statuses(responses).toString()).containsExactlyInAnyOrder(200, 409);
                var loser = responses.get(0).statusCode() == 409 ? responses.get(0) : responses.get(1);
                refused(loser, 409, "ROOM_IN_USE");
                assertThat(count("SELECT COUNT(*) FROM room_reservations WHERE room_id=? AND status='IN_USE'", room)).isEqualTo(1);
                assertThat(usages(a) + usages(b)).isEqualTo(1);
                long winner = responses.get(0).statusCode() == 200 ? a : b;
                ok(complete(one, winner, 2));
            }
        }
    }

    @Test void aCancellationRacingANewBookingOfTheSameHourIsConsistent() throws Exception {
        long customer = customer(first), another = customer(first);
        long room = room(first);
        int rebooked = 0;
        try (var cash = login(cashier); var other = login(twoBranches)) {
            for (int round = 0; round < 8; round++) {
                String startAt = (6 + round * 2) + ":00", endAt = (8 + round * 2) + ":00";
                long id = booked(cash, booking(first, room, customer, startAt, endAt));
                long paymentsBefore = count("SELECT COUNT(*) FROM payments WHERE customer_id=?", another);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                int delay = round % 2 * 15;
                calls.add(() -> { Thread.sleep(delay); return cancel(cash, id, 1); });
                calls.add(() -> book(other, booking(first, room, another, startAt, endAt), key()));
                var responses = race(calls);
                ok(responses.get(0));
                assertThat(refunds(id)).isEqualTo(1);
                assertThat(heldSlots(id)).isZero();
                var made = responses.get(1);
                if (made.statusCode() == 201) {
                    rebooked++;
                    long fresh = node(made).get("id").asLong();
                    assertThat(status(fresh)).isEqualTo("CONFIRMED");
                    assertThat(heldSlots(fresh)).isEqualTo(2);
                    assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", another)).isEqualTo(paymentsBefore + 1);
                } else {
                    refused(made, 409, "ROOM_TIME_CONFLICT");
                    assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", another)).as("the loser leaves no payment").isEqualTo(paymentsBefore);
                    assertThat(count("SELECT COUNT(*) FROM room_reservation_slots WHERE room_id=? AND slot_start_at=?", room, Timestamp.from(kst(startAt)))).isZero();
                }
            }
        }
        System.out.println("race cancel-vs-booking: new booking confirmed " + rebooked + " of 8");
    }
}
