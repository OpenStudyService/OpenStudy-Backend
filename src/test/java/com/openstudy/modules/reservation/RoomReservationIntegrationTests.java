package com.openstudy.modules.reservation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.reservation.repository.RoomRepository;
import com.openstudy.modules.reservation.repository.RoomReservationRepository;
import com.openstudy.modules.reservation.service.RoomService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/** Issue #69: HTTP and MySQL behaviour of the room ledger, booking with prepayment and its reads (space-operations-api.md). */
@MySqlIntegrationTest
@Import(RoomReservationIntegrationTests.TimeConfiguration.class)
class RoomReservationIntegrationTests {
    /** Far from today, so a booking is never in the past. Times of the contract read as Korean wall clock. */
    static final String DAY = "2099-03-04";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired RoomService roomService;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired PaymentService payments;
    @Autowired MutableClock clock;
    @MockitoSpyBean RoomReservationRepository reservationRepository;
    @MockitoSpyBean RoomRepository roomRepository;
    @LocalServerPort int port;

    long owner, cashier, reader, plain, operator, outsider, twoBranches, first, second, third;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    int roomNumber;

    /** null follows the real clock. Tests that need exact instants pin it. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock roomClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        clock.fixed = null;
        owner = person("OWNER", "사장");
        cashier = person("EMPLOYEE", "계산 직원"); reader = person("EMPLOYEE", "장부 직원"); plain = person("EMPLOYEE", "일반 직원");
        operator = person("EMPLOYEE", "운영 직원"); outsider = person("EMPLOYEE", "둘째지점 직원"); twoBranches = person("EMPLOYEE", "겸임 직원");
        first = branch("룸 첫 지점"); second = branch("룸 둘째 지점"); third = branch("룸 셋째 지점");
        member(cashier, first, "payments:write");
        member(reader, first, "payments:read");
        member(plain, first);
        member(operator, first, "operations:write");
        member(outsider, second, "payments:write", "operations:write");
        member(twoBranches, first, "payments:write");
        member(twoBranches, second, "payments:read");
    }

    @AfterEach void clean() {
        Mockito.reset(reservationRepository, roomRepository);
        clock.fixed = null;
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM room_reservations WHERE customer_id=?", customer);
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
            jdbc.update("DELETE FROM rooms WHERE branch_id=?", id);
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
        String email = UUID.randomUUID() + "@room.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("room-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "room-fixture");
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
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "room-fixture").id());
        customerIds.add(id);
        return id;
    }

    long room(long branch, int capacity, long rate) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomNo", "R-" + ++roomNumber); body.put("name", "룸 " + roomNumber);
        body.put("capacity", capacity); body.put("ratePerHourKrw", rate);
        return Long.parseLong(roomService.create(owner, "all", key(), body, "room-fixture").id());
    }

    long room(long branch) { return room(branch, 4, 8000); }

    static String key() { return UUID.randomUUID().toString(); }
    static Instant kst(String time) { return OffsetDateTime.parse(DAY + "T" + time + ":00+09:00").toInstant(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    long payments(long customer) { return count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer); }
    long bookings(long customer) { return count("SELECT COUNT(*) FROM room_reservations WHERE customer_id=?", customer); }
    long slots(long room) { return count("SELECT COUNT(*) FROM room_reservation_slots WHERE room_id=?", room); }

    static Map<String, Object> booking(long branch, long room, long customer, String start, String end) {
        return bookingAt(branch, room, customer, kst(start).toString(), kst(end).toString());
    }

    static Map<String, Object> bookingAt(long branch, long room, long customer, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomId", Long.toString(room)); body.put("customerId", Long.toString(customer));
        body.put("startAt", start); body.put("endAt", end); body.put("partySize", 2); body.put("method", "카드");
        return body;
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        var changed = new LinkedHashMap<>(body);
        changed.put(field, value);
        return changed;
    }

    static Map<String, Object> failed(Map<String, Object> body) { return with(body, "mockOutcome", "FAILED"); }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "room-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> register(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/rooms", session.token(), body, key);
    }

    HttpResponse<String> patch(Session session, long room, Object body) throws Exception {
        return http(session.client(), "PATCH", "/api/rooms/" + room, session.token(), body, null);
    }

    HttpResponse<String> status(Session session, long room, Object body) throws Exception {
        return http(session.client(), "POST", "/api/rooms/" + room + "/status", session.token(), body, null);
    }

    JsonNode booked(Session session, Object body) throws Exception {
        var response = book(session, body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).isEqualTo(code);
    }

    static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    /** The response carries exactly the contract's fields: none missing, none extra. */
    void matchesSchema(JsonNode object, String schema) throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        var required = new HashSet<String>();
        spec.at("/components/schemas/" + schema + "/required").forEach(name -> required.add(name.asString()));
        assertThat(fields(object)).as(schema).isEqualTo(required);
    }

    Map<String, Object> newRoom(long branch, String roomNo) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomNo", roomNo); body.put("name", "새 룸"); body.put("capacity", 6);
        body.put("ratePerHourKrw", 8000);
        return body;
    }

    // ---- room ledger ----------------------------------------------------------------------------------------

    @Test void onlyOperationsWriteAtTheRoomBranchManagesTheLedger() throws Exception {
        long room = room(first);
        var version = Map.of("version", 1, "name", "수정");
        var stop = Map.of("version", 1, "status", "운영 중단", "reason", "청소");
        for (long actor : List.of(plain, cashier, reader)) {
            try (var s = login(actor)) {
                refused(register(s, newRoom(first, "X-" + actor), key()), 403, "FORBIDDEN");
                refused(patch(s, room, version), 403, "FORBIDDEN");
                refused(status(s, room, stop), 403, "FORBIDDEN");
            }
        }
        try (var s = login(outsider)) {
            refused(register(s, newRoom(first, "X-O"), key()), 403, "FORBIDDEN");
            assertThat(patch(s, room, version).statusCode()).isEqualTo(404);
            assertThat(status(s, room, stop).statusCode()).isEqualTo(404);
        }
        try (var s = login(operator)) {
            var made = node(register(s, newRoom(first, "OK-1"), key()));
            assertThat(made.get("status").asString()).isEqualTo("사용 가능");
            assertThat(ok(patch(s, room, version)).get("version").asLong()).isEqualTo(2);
            // STAFF with the grant is enough: unlike seats, stopping a room needs no MANAGER role (M6-D05).
            assertThat(ok(status(s, room, Map.of("version", 2, "status", "운영 중단", "reason", "청소"))).get("status").asString()).isEqualTo("운영 중단");
        }
        try (var s = login(owner)) {
            assertThat(register(s, newRoom(first, "OK-2"), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void aRoomMatchesTheContractAndHasNoDeleteRoute() throws Exception {
        try (var s = login(operator)) {
            var response = register(s, newRoom(first, "S-1"), key());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/rooms/" + node(response).get("id").asString());
            matchesSchema(node(response), "Room");
            assertThat(node(response).get("version").asLong()).isEqualTo(1);
            assertThat(node(response).get("ratePerHourKrw").asLong()).isEqualTo(8000);
            int code = http(s.client(), "DELETE", "/api/rooms/" + node(response).get("id").asString(), s.token(), null, null).statusCode();
            assertThat(code).isEqualTo(405);
        }
    }

    @Test void theSameRoomNumberIsRefusedOnlyInsideOneBranch() throws Exception {
        long other = room(first);
        try (var s = login(operator)) {
            assertThat(register(s, newRoom(first, "D-1"), key()).statusCode()).isEqualTo(201);
            refused(register(s, newRoom(first, "D-1"), key()), 409, "DUPLICATE_ROOM_NO");
            refused(patch(s, other, Map.of("version", 1, "roomNo", "D-1")), 409, "DUPLICATE_ROOM_NO");
        }
        try (var s = login(outsider)) {
            assertThat(register(s, newRoom(second, "D-1"), key()).statusCode()).isEqualTo(201);
        }
        // Pretend the check read before a rival committed: only uk_rooms_1 can refuse now, and nothing is left behind.
        doReturn(false).when(roomRepository).existsByBranchIdAndRoomNo(anyLong(), anyString());
        doReturn(false).when(roomRepository).existsByBranchIdAndRoomNoAndIdNot(anyLong(), anyString(), anyLong());
        try (var s = login(operator)) {
            refused(register(s, newRoom(first, "D-1"), key()), 409, "DUPLICATE_ROOM_NO");
            refused(patch(s, other, Map.of("version", 1, "roomNo", "D-1")), 409, "DUPLICATE_ROOM_NO");
            assertThat(count("SELECT COUNT(*) FROM rooms WHERE branch_id=? AND room_no='D-1'", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND operation='rooms:create'", operator)).isEqualTo(1);
        }
    }

    @Test void registeringARoomIsIdempotent() throws Exception {
        try (var s = login(operator)) {
            String requestKey = key();
            var body = newRoom(first, "I-1");
            var firstAnswer = register(s, body, requestKey);
            var again = register(s, body, requestKey);
            assertThat(again.statusCode()).isEqualTo(201);
            assertThat(node(again).get("id")).isEqualTo(node(firstAnswer).get("id"));
            refused(register(s, newRoom(first, "I-2"), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(register(s, newRoom(first, "I-3"), null), 422, "VALIDATION_FAILED");
            assertThat(count("SELECT COUNT(*) FROM rooms WHERE branch_id=? AND room_no IN ('I-1','I-2','I-3')", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='rooms' AND entity_id=?", node(firstAnswer).get("id").asLong())).isEqualTo(1);
        }
    }

    @Test void malformedRoomsAreRefused() throws Exception {
        try (var s = login(operator)) {
            for (var body : List.of(with(newRoom(first, "V-1"), "capacity", 0), with(newRoom(first, "V-2"), "capacity", 65536),
                    with(newRoom(first, "V-3"), "capacity", "4"), with(newRoom(first, "V-4"), "capacity", 1.5),
                    with(newRoom(first, "V-5"), "ratePerHourKrw", 0), with(newRoom(first, "V-6"), "ratePerHourKrw", 9007199254740992L),
                    with(newRoom(first, "V-7"), "roomNo", " "), with(newRoom(first, "V-8"), "roomNo", "x".repeat(41)),
                    with(newRoom(first, "V-9"), "name", "x".repeat(121)), with(newRoom(first, "V-10"), "status", "사용 중"),
                    with(newRoom(first, "V-11"), "ratePer30MinKrw", 1000))) {
                refused(register(s, body, key()), 422, "VALIDATION_FAILED");
            }
            assertThat(count("SELECT COUNT(*) FROM rooms WHERE branch_id=?", first)).isZero();
            var limits = with(with(newRoom(first, "L-1"), "capacity", 65535), "ratePerHourKrw", 9007199254740991L);
            assertThat(node(register(s, limits, key())).get("ratePerHourKrw").asLong()).isEqualTo(9007199254740991L);
        }
    }

    @Test void editingFollowsVersionsAndKeepsTheBranchAndStatusOutOfReach() throws Exception {
        long room = room(first);
        try (var s = login(operator)) {
            refused(patch(s, room, Map.of("version", 1)), 422, "VALIDATION_FAILED");
            refused(patch(s, room, Map.of("version", 1, "branchId", Long.toString(second))), 422, "IMMUTABLE_FIELD");
            refused(patch(s, room, Map.of("version", 1, "status", "운영 중단")), 422, "IMMUTABLE_FIELD");
            refused(patch(s, room, Map.of("version", 1, "unknown", 1)), 422, "VALIDATION_FAILED");
            // The same values again are no change: no version bump, audit row or event.
            String name = jdbc.queryForObject("SELECT name FROM rooms WHERE id=?", String.class, room);
            assertThat(ok(patch(s, room, Map.of("version", 1, "name", name))).get("version").asLong()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='rooms' AND entity_id=? AND action='UPDATED'", room)).isZero();
            var updated = ok(patch(s, room, Map.of("version", 1, "name", "큰 룸", "capacity", 8, "ratePerHourKrw", 9000)));
            assertThat(updated.get("capacity").asInt()).isEqualTo(8);
            assertThat(updated.get("ratePerHourKrw").asLong()).isEqualTo(9000);
            var stale = patch(s, room, Map.of("version", 1, "name", "늦은 수정"));
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/rooms/" + room);
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
            var audit = jdbc.queryForMap("SELECT CAST(before_json AS CHAR) b, CAST(after_json AS CHAR) a FROM audit_events WHERE entity_type='rooms' AND entity_id=? AND action='UPDATED'", room);
            assertThat(audit.get("b").toString()).contains("8000").doesNotContain("\"status\"");
            assertThat(audit.get("a").toString()).contains("9000");
        }
    }

    @Test void stoppingARoomNeedsAReasonAndKeepsItsReservations() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var cash = login(cashier); var s = login(operator)) {
            var reservation = booked(cash, booking(first, room, customer, "14:00", "15:00"));
            refused(status(s, room, Map.of("version", 1, "status", "운영 중단")), 422, "VALIDATION_FAILED");
            refused(status(s, room, Map.of("version", 1, "status", "운영 중단", "reason", " ")), 422, "VALIDATION_FAILED");
            refused(status(s, room, Map.of("version", 9, "status", "운영 중단", "reason", "공사")), 409, "VERSION_CONFLICT");
            assertThat(ok(status(s, room, Map.of("version", 1, "status", "사용 가능", "reason", "그대로"))).get("version").asLong()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='rooms' AND entity_id=? AND action='STATUS_CHANGED'", room)).isZero();
            var stopped = ok(status(s, room, Map.of("version", 1, "status", "운영 중단", "reason", "공사")));
            assertThat(stopped.get("status").asString()).isEqualTo("운영 중단");
            assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE entity_type='rooms' AND entity_id=? AND action='STATUS_CHANGED'", String.class, room)).isEqualTo("공사");
            var still = ok(get(cash, "/api/room-reservations/" + reservation.get("id").asString()));
            assertThat(still.get("status").asString()).isEqualTo("확정");
            assertThat(slots(room)).isEqualTo(1);
            refused(book(cash, booking(first, room, customer, "16:00", "17:00"), key()), 422, "ROOM_NOT_AVAILABLE");
            ok(status(s, room, Map.of("version", 2, "status", "사용 가능", "reason", "공사 끝")));
            booked(cash, booking(first, room, customer, "16:00", "17:00"));
        }
    }

    @Test void readingRoomsFollowsTheScope() throws Exception {
        long b = room(first, 4, 8000), a = room(first, 4, 8000), foreign = room(second);
        jdbc.update("UPDATE rooms SET room_no='B-2' WHERE id=?", b);
        jdbc.update("UPDATE rooms SET room_no='A-1' WHERE id=?", a);
        jdbc.update("UPDATE rooms SET status='BLOCKED' WHERE id=?", a);
        try (var s = login(plain)) {
            var page = ok(get(s, "/api/rooms?scope=" + first));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(page.at("/items/0/roomNo").asString()).isEqualTo("A-1");
            assertThat(page.at("/items/1/roomNo").asString()).isEqualTo("B-2");
            // Everyone at the branch reads the rate: it is the published sale price.
            assertThat(page.at("/items/0/ratePerHourKrw").asLong()).isEqualTo(8000);
            var all = ok(get(s, "/api/rooms"));
            assertThat(all.at("/meta/total").asLong()).as("no other branch in all").isEqualTo(2);
            var blocked = ok(get(s, "/api/rooms?status=" + encoded("운영 중단")));
            assertThat(blocked.at("/meta/total").asLong()).isEqualTo(1);
            refused(get(s, "/api/rooms?status=" + encoded("사용 중")), 422, "VALIDATION_FAILED");
            assertThat(get(s, "/api/rooms?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(ok(get(s, "/api/rooms/" + b)).get("id").asString()).isEqualTo(Long.toString(b));
            assertThat(get(s, "/api/rooms/" + foreign).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/rooms/999999999").statusCode()).isEqualTo(404);
        }
    }

    @Test void theRoomLedgerCanChangeWhileTheBranchIsStopped() throws Exception {
        long room = room(first);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var s = login(operator)) {
            assertThat(ok(patch(s, room, Map.of("version", 1, "ratePerHourKrw", 7000))).get("ratePerHourKrw").asLong()).isEqualTo(7000);
            ok(status(s, room, Map.of("version", 2, "status", "운영 중단", "reason", "휴업")));
            assertThat(register(s, newRoom(first, "T-1"), key()).statusCode()).isEqualTo(201);
        }
    }

    // ---- the contract's fee examples ------------------------------------------------------------------------

    record Example(long rate, String start, String end, int hours, long amount) {}

    @Test void everyFeeExampleOfTheContractIsChargedExactly() throws Exception {
        long customer = customer(first);
        try (var s = login(cashier)) {
            for (var example : List.of(new Example(8000, "14:00", "15:00", 1, 8000), new Example(8000, "14:00", "17:00", 3, 24000),
                    new Example(12500, "09:00", "11:00", 2, 25000))) {
                long room = room(first, 4, example.rate());
                var made = booked(s, booking(first, room, customer, example.start(), example.end()));
                assertThat(made.get("status").asString()).isEqualTo("확정");
                assertThat(made.at("/billing/ratePerHourKrw").asLong()).isEqualTo(example.rate());
                assertThat(made.at("/billing/hours").asInt()).isEqualTo(example.hours());
                assertThat(made.at("/billing/amountKrw").asLong()).isEqualTo(example.amount());
                assertThat(made.at("/billing/paymentStatus").asString()).isEqualTo("승인");
                long id = made.get("id").asLong();
                var row = jdbc.queryForMap("SELECT rate_per_hour_snapshot_krw r, hours h, amount_krw a, payment_id p FROM room_reservations WHERE id=?", id);
                assertThat(((Number) row.get("r")).longValue()).isEqualTo(example.rate());
                assertThat(((Number) row.get("h")).intValue()).isEqualTo(example.hours());
                assertThat(((Number) row.get("a")).longValue()).isEqualTo(example.amount());
                var paid = jdbc.queryForMap("SELECT amount_krw a, purpose u, product_id i, product_name_snapshot n, status t, issued_pass_quantity q, approved_at o FROM payments WHERE id=?", row.get("p"));
                assertThat(((Number) paid.get("a")).longValue()).isEqualTo(example.amount());
                assertThat(paid.get("u")).isEqualTo("ROOM");
                assertThat(paid.get("i")).isNull();
                assertThat(paid.get("n")).isNull();
                assertThat(paid.get("t")).isEqualTo("APPROVED");
                assertThat(((Number) paid.get("q")).intValue()).isZero();
                assertThat(paid.get("o")).isNotNull();
                var held = jdbc.queryForList("SELECT slot_start_at FROM room_reservation_slots WHERE reservation_id=? ORDER BY slot_start_at", Timestamp.class, id);
                var expected = new ArrayList<Instant>();
                for (int hour = 0; hour < example.hours(); hour++) expected.add(kst(example.start()).plus(Duration.ofHours(hour)));
                assertThat(held.stream().map(Timestamp::toInstant).toList()).isEqualTo(expected);
            }
            long room = room(first, 4, 8000);
            refused(book(s, booking(first, room, customer, "14:30", "15:30"), key()), 422, "RESERVATION_TIME_INVALID");
            refused(book(s, booking(first, room, customer, "14:00", "15:30"), key()), 422, "RESERVATION_TIME_INVALID");
            assertThat(slots(room)).isZero();
            assertThat(payments(customer)).isEqualTo(3);
        }
    }

    @Test void noRequestFieldCanSetMoneyOrHours() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            var body = booking(first, room, customer, "14:00", "15:00");
            for (String field : List.of("amountKrw", "hours", "ratePerHourKrw", "status", "paymentId")) {
                refused(book(s, with(body, field, 1), key()), 422, "VALIDATION_FAILED");
            }
            assertThat(payments(customer)).isZero();
            assertThat(bookings(customer)).isZero();
        }
    }

    @Test void malformedBookingsAreFormatErrors() throws Exception {
        long customer = customer(first);
        long room = room(first);
        var body = booking(first, room, customer, "14:00", "15:00");
        try (var s = login(cashier)) {
            for (var bad : List.of(with(body, "partySize", 0), with(body, "partySize", 65536), with(body, "partySize", "2"), with(body, "partySize", 1.5),
                    with(body, "method", "수표"), with(body, "mockOutcome", "PENDING"), with(body, "startAt", "내일"), with(body, "endAt", null),
                    with(body, "roomId", "abc"), with(body, "branchId", 1))) {
                refused(book(s, bad, key()), 422, "VALIDATION_FAILED");
            }
            refused(book(s, body, null), 422, "VALIDATION_FAILED");
            refused(book(s, body, "short"), 422, "VALIDATION_FAILED");
            var noCsrf = http(s.client(), "POST", "/api/room-reservations", null, body, key());
            assertThat(noCsrf.statusCode()).isEqualTo(403);
            assertThat(bookings(customer)).isZero();
        }
    }

    // ---- permissions and the check order ---------------------------------------------------------------------

    @Test void bookingNeedsPaymentsWriteAtTheRequestedBranch() throws Exception {
        long room = room(first);
        long customer = customer(first);
        var body = booking(first, room, customer, "14:00", "15:00");
        for (long actor : List.of(plain, reader, operator, outsider)) {
            try (var s = login(actor)) {
                refused(book(s, body, key()), 403, "FORBIDDEN");
                // 403 comes first: a missing room behind it stays unknown.
                refused(book(s, with(body, "roomId", "999999999"), key()), 403, "FORBIDDEN");
            }
        }
        assertThat(payments(customer)).isZero();
        try (var s = login(cashier)) { booked(s, body); }
        try (var s = login(owner)) { booked(s, booking(first, room, customer, "15:00", "16:00")); }
        assertThat(payments(customer)).isEqualTo(2);
    }

    @Test void checksRunInTheContractOrder() throws Exception {
        long customer = customer(first);
        long room = room(first, 2, 8000);
        long foreign = room(third);
        long otherBranch = room(second);
        var good = booking(first, room, customer, "14:00", "15:00");
        try (var s = login(twoBranches)) {
            refused(book(s, with(good, "roomId", "999999999"), key()), 404, "NOT_FOUND");
            // A room of a branch the actor cannot reach looks missing; a reachable one is a wrong input.
            refused(book(s, with(good, "roomId", Long.toString(foreign)), key()), 404, "NOT_FOUND");
            refused(book(s, with(good, "roomId", Long.toString(otherBranch)), key()), 422, "ROOM_BRANCH_MISMATCH");
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(book(s, with(good, "roomId", Long.toString(otherBranch)), key()), 422, "ROOM_BRANCH_MISMATCH");
            jdbc.update("UPDATE rooms SET status='BLOCKED' WHERE id=?", room);
            refused(book(s, with(good, "startAt", "2099-03-04T05:30:00Z"), key()), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);
            refused(book(s, with(good, "startAt", "2099-03-04T05:30:00Z"), key()), 422, "ROOM_NOT_AVAILABLE");
            jdbc.update("UPDATE rooms SET status='AVAILABLE' WHERE id=?", room);
            var crowded = with(good, "partySize", 3);
            refused(book(s, with(crowded, "startAt", "2099-03-04T05:30:00Z"), key()), 422, "RESERVATION_TIME_INVALID");
            refused(book(s, with(crowded, "customerId", "999999999"), key()), 422, "RESERVATION_CAPACITY_EXCEEDED");
            refused(book(s, with(good, "customerId", "999999999"), key()), 404, "NOT_FOUND");
            long hidden = customer(third);
            refused(book(s, with(good, "customerId", Long.toString(hidden)), key()), 404, "NOT_FOUND");
            jdbc.update("UPDATE customers SET status='RESTRICTED' WHERE id=?", customer);
            refused(book(s, good, key()), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='INACTIVE' WHERE id=?", customer);
            refused(book(s, good, key()), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='NORMAL' WHERE id=?", customer);
            var exact = with(good, "partySize", 2);
            booked(s, exact);
            refused(book(s, exact, key()), 409, "ROOM_TIME_CONFLICT");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=?", twoBranches)).as("a refusal is not stored").isEqualTo(1);
        }
    }

    @Test void timesOutsideTheRulesAreInputErrors() throws Exception {
        long customer = customer(first);
        long room = room(first);
        try (var s = login(cashier)) {
            String start = kst("14:00").toString();
            for (String[] times : new String[][] {
                    {"2099-03-04T05:00:01Z", "2099-03-04T06:00:01Z"}, {"2099-03-04T05:00:00.000001Z", "2099-03-04T06:00:00.000001Z"},
                    {"2099-03-04T05:00:00.000000001Z", "2099-03-04T06:00:00.000000001Z"}, {start, "2099-03-04T06:00:00.000000001Z"},
                    {start, start}, {kst("15:00").toString(), start}, {start, kst("14:00").plus(Duration.ofHours(25)).toString()},
                    {"2020-01-01T00:00:00Z", "2020-01-01T01:00:00Z"}}) {
                refused(book(s, bookingAt(first, room, customer, times[0], times[1]), key()), 422, "RESERVATION_TIME_INVALID");
            }
            assertThat(payments(customer)).isZero();
            var longest = booked(s, bookingAt(first, room, customer, start, kst("14:00").plus(Duration.ofHours(24)).toString()));
            assertThat(longest.at("/billing/hours").asInt()).isEqualTo(24);
            assertThat(longest.at("/billing/amountKrw").asLong()).isEqualTo(192_000);
            assertThat(slots(room)).isEqualTo(24);
        }
    }

    @Test void aStartThatIsNoLongerInTheFutureIsRefusedAtTheExactInstant() throws Exception {
        long customer = customer(first);
        long early = room(first), late = room(first);
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        clock.fixed = start.minusNanos(1000);
        try (var s = login(cashier)) {
            booked(s, bookingAt(first, early, customer, start.toString(), start.plus(Duration.ofHours(1)).toString()));
            clock.fixed = start;
            refused(book(s, bookingAt(first, late, customer, start.toString(), start.plus(Duration.ofHours(1)).toString()), key()), 422, "RESERVATION_TIME_INVALID");
        }
    }

    @Test void anAmountBeyondTheApiLimitIsNeverCharged() throws Exception {
        long customer = customer(first);
        long room = room(first, 4, 9007199254740991L);
        try (var s = login(cashier)) {
            assertThat(booked(s, booking(first, room, customer, "14:00", "15:00")).at("/billing/amountKrw").asLong()).isEqualTo(9007199254740991L);
            refused(book(s, booking(first, room, customer, "15:00", "17:00"), key()), 422, "VALIDATION_FAILED");
            assertThat(payments(customer)).isEqualTo(1);
        }
    }

    // ---- time conflicts ---------------------------------------------------------------------------------------

    @Test void overlappingTimesConflictAndTouchingTimesDoNot() throws Exception {
        long room = room(first);
        long other = room(first);
        long holder = customer(first), rival = customer(first);
        try (var s = login(cashier)) {
            booked(s, booking(first, room, holder, "14:00", "16:00"));
            for (String[] times : new String[][] {{"14:00", "16:00"}, {"13:00", "15:00"}, {"15:00", "17:00"}, {"14:00", "15:00"}, {"13:00", "17:00"}}) {
                refused(book(s, booking(first, room, rival, times[0], times[1]), key()), 409, "ROOM_TIME_CONFLICT");
                refused(book(s, failed(booking(first, room, rival, times[0], times[1])), key()), 409, "ROOM_TIME_CONFLICT");
            }
            assertThat(payments(rival)).as("a conflict leaves no payment, approved or failed").isZero();
            assertThat(bookings(rival)).isZero();
            booked(s, booking(first, room, rival, "13:00", "14:00"));
            booked(s, booking(first, room, rival, "16:00", "17:00"));
            booked(s, booking(first, other, rival, "14:00", "16:00"));
            assertThat(slots(room)).isEqualTo(4);
        }
    }

    @Test void onlyActiveReservationsHoldTheirTime() throws Exception {
        long room = room(first);
        long customer = customer(first), rival = customer(first);
        try (var s = login(cashier)) {
            long id = booked(s, booking(first, room, customer, "14:00", "15:00")).get("id").asLong();
            jdbc.update("UPDATE room_reservations SET status='IN_USE' WHERE id=?", id);
            refused(book(s, booking(first, room, rival, "14:00", "15:00"), key()), 409, "ROOM_TIME_CONFLICT");
            // #70 removes the slots when it cancels or completes; here only the status rule of this check is under test.
            jdbc.update("DELETE FROM room_reservation_slots WHERE reservation_id=?", id);
            jdbc.update("UPDATE room_reservations SET status='COMPLETED' WHERE id=?", id);
            long next = booked(s, booking(first, room, rival, "14:00", "15:00")).get("id").asLong();
            jdbc.update("DELETE FROM room_reservation_slots WHERE reservation_id=?", next);
            jdbc.update("DELETE FROM room_reservations WHERE id=?", next);
            jdbc.update("UPDATE room_reservations SET status='CANCELLED', cancelled_at=NOW(6), cancel_reason='사유' WHERE id=?", id);
            booked(s, booking(first, room, rival, "14:00", "15:00"));
        }
    }

    @Test void theSlotKeyIsTheLastDefenceAndRollsEverythingBack() throws Exception {
        long room = room(first);
        long holder = customer(first), rival = customer(first);
        try (var s = login(cashier)) {
            booked(s, booking(first, room, holder, "14:00", "16:00"));
            long auditBefore = count("SELECT COUNT(*) FROM audit_events WHERE actor_employee_id=?", cashier);
            // Pretend the check read before the rival committed: only uk_room_reservation_slots_1 can refuse now.
            doReturn(false).when(reservationRepository).existsOverlapping(anyLong(), any(), any(), any());
            refused(book(s, booking(first, room, rival, "15:00", "17:00"), key()), 409, "ROOM_TIME_CONFLICT");
            assertThat(payments(rival)).as("the approved payment is rolled back").isZero();
            assertThat(bookings(rival)).isZero();
            assertThat(slots(room)).as("the free 16:00 slot went with it").isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE actor_employee_id=?", cashier)).isEqualTo(auditBefore);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=?", cashier)).isEqualTo(1);
        }
    }

    // ---- Mock payment outcomes --------------------------------------------------------------------------------

    @Test void aFailedPaymentLeavesNoHoldAndTheTimeStaysBookable() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            String requestKey = key();
            var body = failed(booking(first, room, customer, "14:00", "16:00"));
            var answer = book(s, body, requestKey);
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(201);
            var made = node(answer);
            assertThat(made.get("status").asString()).isEqualTo("결제 실패");
            assertThat(made.at("/billing/paymentStatus").asString()).isEqualTo("실패");
            assertThat(made.at("/billing/amountKrw").asLong()).isEqualTo(16000);
            assertThat(made.get("canCancel").asBoolean()).isFalse();
            assertThat(slots(room)).isZero();
            var paid = jdbc.queryForMap("SELECT status s, failed_at f, approved_at a FROM payments WHERE customer_id=?", customer);
            assertThat(paid.get("s")).isEqualTo("FAILED");
            assertThat(paid.get("f")).isNotNull();
            assertThat(paid.get("a")).isNull();
            // Stored like a success: the same key answers the same failure and a new try needs a new key.
            assertThat(node(book(s, body, requestKey))).isEqualTo(made);
            assertThat(payments(customer)).isEqualTo(1);
            var retry = booked(s, booking(first, room, customer, "14:00", "16:00"));
            assertThat(retry.get("status").asString()).isEqualTo("확정");
            assertThat(slots(room)).isEqualTo(2);
            assertThat(payments(customer)).isEqualTo(2);
        }
    }

    // ---- idempotency ------------------------------------------------------------------------------------------

    @Test void theSameKeyAnswersTheFirstResultAndNeverChargesTwice() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            String requestKey = key();
            var body = booking(first, room, customer, "14:00", "16:00");
            var made = book(s, body, requestKey);
            assertThat(made.statusCode()).isEqualTo(201);
            assertThat(made.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/room-reservations/" + node(made).get("id").asString());
            var again = book(s, body, requestKey);
            assertThat(again.statusCode()).isEqualTo(201);
            assertThat(node(again)).isEqualTo(node(made));
            // The stored result stands even after the room stopped; only a new request is refused.
            jdbc.update("UPDATE rooms SET status='BLOCKED' WHERE id=?", room);
            assertThat(node(book(s, body, requestKey))).isEqualTo(node(made));
            jdbc.update("UPDATE rooms SET status='AVAILABLE' WHERE id=?", room);
            refused(book(s, booking(first, room, customer, "16:00", "17:00"), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(book(s, with(body, "partySize", 3), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(book(s, with(body, "method", "현금"), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            refused(book(s, failed(body), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(bookings(customer)).isEqualTo(1);
            assertThat(slots(room)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='room-reservations' AND entity_id=?", node(made).get("id").asLong())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='payments' AND actor_employee_id=?", cashier)).isEqualTo(1);
            // A stored key is a hash that fits the 120-character columns; the client's key is never kept there.
            String longKey = "k".repeat(128);
            long id = node(book(s, booking(first, room, customer, "18:00", "19:00"), longKey)).get("id").asLong();
            String stored = jdbc.queryForObject("SELECT request_key FROM room_reservations WHERE id=?", String.class, id);
            assertThat(stored.length()).isLessThanOrEqualTo(120);
            assertThat(stored).doesNotContain("kkkk");
            String storedPayment = jdbc.queryForObject("SELECT p.idempotency_key FROM payments p JOIN room_reservations r ON r.payment_id=p.id WHERE r.id=?", String.class, id);
            assertThat(storedPayment.length()).isLessThanOrEqualTo(120);
            assertThat(storedPayment).doesNotContain("kkkk").isNotEqualTo(stored);
        }
    }

    @Test void aReusedKeyOfAnotherEmployeeIsAnotherRequest() throws Exception {
        long room = room(first);
        long customer = customer(first);
        String shared = key();
        try (var one = login(cashier); var two = login(twoBranches)) {
            assertThat(book(one, booking(first, room, customer, "14:00", "15:00"), shared).statusCode()).isEqualTo(201);
            assertThat(book(two, booking(first, room, customer, "15:00", "16:00"), shared).statusCode()).isEqualTo(201);
            assertThat(payments(customer)).isEqualTo(2);
        }
    }

    @Test void anExpiredKeyCannotChargeTwice() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            String requestKey = key();
            booked(s, booking(first, room, customer, "14:00", "15:00"));
            assertThat(book(s, booking(first, room, customer, "15:00", "16:00"), requestKey).statusCode()).isEqualTo(201);
            jdbc.update("UPDATE idempotency_records SET expires_at='2020-01-01 00:00:00' WHERE actor_employee_id=? AND idempotency_key=?", cashier, requestKey);
            refused(book(s, booking(first, room, customer, "15:00", "16:00"), requestKey), 409, "ROOM_TIME_CONFLICT");
            // The stored key is the same hash, so the unique key of the payment refuses a new time as well.
            refused(book(s, booking(first, room, customer, "20:00", "21:00"), requestKey), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(payments(customer)).isEqualTo(2);
            assertThat(slots(room)).isEqualTo(2);
        }
    }

    // ---- branch state and scope -------------------------------------------------------------------------------

    @Test void aStoppedBranchRefusesBookingsButKeepsItsRecordsReadable() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            String id = booked(s, booking(first, room, customer, "14:00", "15:00")).get("id").asString();
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(book(s, booking(first, room, customer, "16:00", "17:00"), key()), 409, "BRANCH_NOT_ACTIVE");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(ok(get(s, "/api/room-reservations/" + id)).get("status").asString()).isEqualTo("확정");
            assertThat(ok(get(s, "/api/room-reservations?scope=" + first)).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(ok(get(s, calendar(first, null, "00:00", "23:00"))).get("items").size()).isEqualTo(1);
            assertThat(ok(get(s, "/api/rooms/" + room)).get("id").asString()).isEqualTo(Long.toString(room));
        }
    }

    @Test void recordsOfOtherBranchesStayHidden() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier); var other = login(outsider)) {
            var made = booked(s, booking(first, room, customer, "14:00", "15:00"));
            String id = made.get("id").asString();
            assertThat(get(other, "/api/room-reservations/" + id).statusCode()).isEqualTo(404);
            assertThat(get(other, "/api/room-reservations/999999999").statusCode()).isEqualTo(404);
            assertThat(ok(get(other, "/api/room-reservations")).at("/meta/total").asLong()).isZero();
            assertThat(ok(get(other, "/api/room-reservations?roomId=" + room)).at("/meta/total").asLong()).isZero();
            assertThat(ok(get(other, "/api/room-reservations?customerId=" + customer)).at("/meta/total").asLong()).isZero();
            assertThat(get(other, "/api/room-reservations?scope=" + first).statusCode()).isEqualTo(403);
            assertThat(get(other, calendar(first, null, "00:00", "23:00")).statusCode()).isEqualTo(403);
            assertThat(get(other, calendar(999999999L, null, "00:00", "23:00")).statusCode()).isEqualTo(403);
            assertThat(get(other, "/api/rooms/" + room).statusCode()).isEqualTo(404);
            // A readable branch, a room of another branch: nothing about that room leaks.
            assertThat(get(other, calendar(second, room, "00:00", "23:00")).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/room-reservations/" + id + "?scope=" + second).statusCode()).isEqualTo(403);
        }
    }

    // ---- billing exposure -------------------------------------------------------------------------------------

    @Test void billingNeedsAPaymentPermissionAtTheReservationBranch() throws Exception {
        long room = room(first), foreign = room(second);
        long customer = customer(first);
        String id, secondId;
        try (var s = login(owner)) {
            id = booked(s, booking(first, room, customer, "14:00", "15:00")).get("id").asString();
            secondId = booked(s, booking(second, foreign, customer, "14:00", "15:00")).get("id").asString();
        }
        String list = "/api/room-reservations?scope=" + first, day = calendar(first, null, "00:00", "23:00");
        for (long actor : List.of(reader, cashier, owner)) {
            try (var s = login(actor)) {
                var detail = ok(get(s, "/api/room-reservations/" + id));
                assertThat(detail.at("/billing/amountKrw").asLong()).as("actor %s", actor).isEqualTo(8000);
                assertThat(detail.at("/billing/paymentId").asString()).isNotBlank();
                assertThat(ok(get(s, list)).at("/items/0/billing/amountKrw").asLong()).isEqualTo(8000);
                assertThat(ok(get(s, day)).at("/items/0/billing/amountKrw").asLong()).isEqualTo(8000);
            }
        }
        // Same branch, no payment permission: the record is visible, the money is not.
        for (long actor : List.of(plain, operator)) {
            try (var s = login(actor)) {
                var detail = get(s, "/api/room-reservations/" + id);
                assertThat(ok(detail).get("billing").isNull()).isTrue();
                assertThat(detail.body()).doesNotContain("amountKrw").doesNotContain("paymentId");
                assertThat(ok(get(s, list)).at("/items/0/billing").isNull()).isTrue();
                assertThat(ok(get(s, day)).at("/items/0/billing").isNull()).isTrue();
                assertThat(ok(get(s, "/api/rooms/" + room)).get("ratePerHourKrw").asLong()).isEqualTo(8000);
            }
        }
        // A permission at one branch says nothing about another: decided row by row.
        jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=? AND m.branch_id=?", twoBranches, second);
        try (var s = login(twoBranches)) {
            var page = ok(get(s, "/api/room-reservations"));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(2);
            for (var item : page.get("items")) {
                boolean atFirst = item.get("id").asString().equals(id);
                assertThat(item.get("billing").isNull()).as(item.get("id").asString()).isEqualTo(!atFirst);
            }
            assertThat(ok(get(s, "/api/room-reservations/" + secondId)).get("billing").isNull()).isTrue();
            assertThat(ok(get(s, "/api/room-reservations/" + id)).get("billing").isNull()).isFalse();
        }
    }

    @Test void canCancelIsAHintForAPaymentWriterBeforeTheStart() throws Exception {
        long room = room(first);
        long customer = customer(first);
        String id;
        try (var s = login(cashier)) {
            var made = booked(s, booking(first, room, customer, "14:00", "15:00"));
            id = made.get("id").asString();
            assertThat(made.get("canCancel").asBoolean()).isTrue();
        }
        try (var s = login(reader)) {
            assertThat(ok(get(s, "/api/room-reservations/" + id)).get("canCancel").asBoolean()).isFalse();
        }
    }

    // ---- snapshots --------------------------------------------------------------------------------------------

    @Test void changingTheRoomLeavesEveryExistingReservationAsItWas() throws Exception {
        long room = room(first, 4, 8000);
        long customer = customer(first);
        try (var cash = login(cashier); var s = login(operator)) {
            var made = booked(cash, booking(first, room, customer, "14:00", "16:00"));
            String id = made.get("id").asString();
            ok(patch(s, room, Map.of("version", 1, "name", "개명한 룸", "roomNo", "NEW-1", "ratePerHourKrw", 20000)));
            var read = ok(get(cash, "/api/room-reservations/" + id));
            assertThat(read.get("billing")).isEqualTo(made.get("billing"));
            assertThat(read.at("/billing/ratePerHourKrw").asLong()).isEqualTo(8000);
            assertThat(read.at("/billing/amountKrw").asLong()).isEqualTo(16000);
            assertThat(read.at("/room/id").asString()).isEqualTo(Long.toString(room));
            assertThat(read.at("/room/name").asString()).isEqualTo("개명한 룸");
            assertThat(read.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(count("SELECT amount_krw FROM payments WHERE customer_id=?", customer)).isEqualTo(16000);
            // The next booking pays the rate the room shows now, read under its lock.
            var next = booked(cash, booking(first, room, customer, "16:00", "17:00"));
            assertThat(next.at("/billing/ratePerHourKrw").asLong()).isEqualTo(20000);
            assertThat(next.at("/billing/amountKrw").asLong()).isEqualTo(20000);
        }
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    String calendar(long branch, Long room, String from, String to) {
        return "/api/room-reservations/calendar?branchId=" + branch + (room == null ? "" : "&roomId=" + room) + "&from=" + kst(from) + "&to=" + kst(to);
    }

    @Test void theListTheCalendarAndTheDetailAgree() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            var made = booked(s, booking(first, room, customer, "14:00", "16:00"));
            String id = made.get("id").asString();
            matchesSchema(made, "RoomReservation");
            matchesSchema(made.get("billing"), "ReservationBilling");
            matchesSchema(made.get("room"), "RoomRef");
            matchesSchema(made.get("customer"), "CustomerSummary");
            matchesSchema(made.get("createdBy"), "ActorSummary");
            assertThat(made.get("usage").isNull()).isTrue();
            assertThat(made.get("version").asLong()).isEqualTo(1);
            assertThat(made.get("startAt").asString()).isEqualTo(kst("14:00").toString());
            assertThat(made.at("/createdBy/id").asString()).isEqualTo(Long.toString(cashier));
            var detail = ok(get(s, "/api/room-reservations/" + id));
            var listed = ok(get(s, "/api/room-reservations?scope=" + first)).at("/items/0");
            var calendar = ok(get(s, calendar(first, null, "00:00", "23:00")));
            matchesSchema(calendar, "RoomReservationCalendar");
            assertThat(calendar.get("from").asString()).isEqualTo(kst("00:00").toString());
            assertThat(detail).isEqualTo(made);
            assertThat(listed).isEqualTo(detail);
            assertThat(calendar.at("/items/0")).isEqualTo(detail);
            // The stored status moves all three together.
            jdbc.update("UPDATE room_reservations SET status='IN_USE' WHERE id=?", Long.parseLong(id));
            assertThat(ok(get(s, "/api/room-reservations/" + id)).get("status").asString()).isEqualTo("이용 중");
            assertThat(ok(get(s, "/api/room-reservations?scope=" + first)).at("/items/0/status").asString()).isEqualTo("이용 중");
            assertThat(ok(get(s, calendar(first, null, "00:00", "23:00"))).at("/items/0/status").asString()).isEqualTo("이용 중");
        }
    }

    @Test void theListFiltersOverlapsAndPages() throws Exception {
        long room = room(first), other = room(first);
        long customer = customer(first), another = customer(first);
        try (var s = login(cashier)) {
            String morning = booked(s, booking(first, room, customer, "09:00", "11:00")).get("id").asString();
            String noon = booked(s, booking(first, room, another, "12:00", "13:00")).get("id").asString();
            String failed = booked(s, failed(booking(first, other, another, "15:00", "16:00"))).get("id").asString();
            var all = ok(get(s, "/api/room-reservations?scope=" + first));
            assertThat(ids(all)).isEqualTo(List.of(failed, noon, morning));
            assertThat(all.at("/meta/total").asLong()).isEqualTo(3);
            var page = ok(get(s, "/api/room-reservations?scope=" + first + "&page=2&pageSize=2"));
            assertThat(ids(page)).isEqualTo(List.of(morning));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(3);
            assertThat(ids(ok(get(s, "/api/room-reservations?roomId=" + other)))).isEqualTo(List.of(failed));
            assertThat(ids(ok(get(s, "/api/room-reservations?customerId=" + customer)))).isEqualTo(List.of(morning));
            assertThat(ids(ok(get(s, "/api/room-reservations?status=" + encoded("결제 실패"))))).isEqualTo(List.of(failed));
            assertThat(ids(ok(get(s, "/api/room-reservations?status=" + encoded("확정"))))).isEqualTo(List.of(noon, morning));
            // endAt > from and startAt < to: a period that only touches the range is out.
            assertThat(ids(ok(get(s, "/api/room-reservations?from=" + kst("11:00") + "&to=" + kst("12:00"))))).isEmpty();
            assertThat(ids(ok(get(s, "/api/room-reservations?from=" + kst("10:00") + "&to=" + kst("12:00"))))).isEqualTo(List.of(morning));
            assertThat(ids(ok(get(s, "/api/room-reservations?from=" + kst("11:00") + "&to=" + kst("12:30"))))).isEqualTo(List.of(noon));
            assertThat(ids(ok(get(s, "/api/room-reservations?from=" + kst("10:59"))))).isEqualTo(List.of(failed, noon, morning));
            assertThat(ids(ok(get(s, "/api/room-reservations?to=" + kst("09:30"))))).isEqualTo(List.of(morning));
            refused(get(s, "/api/room-reservations?status=" + encoded("결제 대기")), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations?roomId=abc"), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations?from=yesterday"), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations?from=" + kst("12:00") + "&to=" + kst("11:00")), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations?pageSize=101"), 422, "VALIDATION_FAILED");
        }
    }

    List<String> ids(JsonNode page) {
        var found = new ArrayList<String>();
        page.get("items").forEach(item -> found.add(item.get("id").asString()));
        return found;
    }

    @Test void theCalendarGivesTheWholePeriodWithoutFailedPayments() throws Exception {
        long room = room(first), other = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            String late = booked(s, booking(first, room, customer, "20:00", "21:00")).get("id").asString();
            String early = booked(s, booking(first, room, customer, "09:00", "10:00")).get("id").asString();
            String next = booked(s, booking(first, other, customer, "10:00", "11:00")).get("id").asString();
            String cancelled = booked(s, booking(first, other, customer, "12:00", "13:00")).get("id").asString();
            jdbc.update("DELETE FROM room_reservation_slots WHERE reservation_id=?", Long.parseLong(cancelled));
            jdbc.update("UPDATE room_reservations SET status='CANCELLED', cancelled_at=NOW(6), cancelled_by=?, cancel_reason='사유' WHERE id=?", cashier, Long.parseLong(cancelled));
            booked(s, failed(booking(first, room, customer, "15:00", "16:00")));
            var whole = ok(get(s, calendar(first, null, "00:00", "23:00")));
            assertThat(ids(whole)).isEqualTo(List.of(early, next, cancelled, late));
            var item = whole.at("/items/2");
            assertThat(item.get("status").asString()).isEqualTo("취소");
            assertThat(item.get("cancelReason").asString()).isEqualTo("사유");
            assertThat(item.at("/cancelledBy/id").asString()).isEqualTo(Long.toString(cashier));
            assertThat(item.get("cancelledAt").isNull()).isFalse();
            assertThat(item.get("canCancel").asBoolean()).isFalse();
            assertThat(ids(ok(get(s, calendar(first, room, "00:00", "23:00"))))).isEqualTo(List.of(early, late));
            // The same overlap rule as the list.
            assertThat(ids(ok(get(s, calendar(first, room, "10:00", "20:00"))))).isEmpty();
            assertThat(ids(ok(get(s, calendar(first, room, "09:30", "20:30"))))).isEqualTo(List.of(early, late));
            // Exactly 62 days is allowed, a second more is not.
            Instant from = kst("00:00");
            String range = "/api/room-reservations/calendar?branchId=" + first + "&from=" + from + "&to=";
            assertThat(ids(ok(get(s, range + from.plus(Duration.ofDays(62)))))).hasSize(4);
            refused(get(s, range + from.plus(Duration.ofDays(62)).plusSeconds(1)), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations/calendar?from=" + from + "&to=" + from.plusSeconds(60)), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations/calendar?branchId=" + first + "&to=" + from), 422, "VALIDATION_FAILED");
            refused(get(s, "/api/room-reservations/calendar?branchId=" + first + "&from=" + from), 422, "VALIDATION_FAILED");
            refused(get(s, range + from), 422, "VALIDATION_FAILED");
            assertThat(get(s, calendar(first, 999999999L, "00:00", "23:00")).statusCode()).isEqualTo(404);
        }
    }

    // ---- payments -----------------------------------------------------------------------------------------------

    long passPayment(long customer, long branch) {
        var category = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류", "kind", "시간권"), "room-fixture");
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600); terms.put("validDays", null);
        var product = new LinkedHashMap<String, Object>();
        product.put("name", "시간권"); product.put("priceKrw", 15000L); product.put("terms", terms); product.put("status", "판매 중");
        product.put("categoryId", json.readTree(json.writeValueAsString(category)).get("id").asString());
        product.put("branchIds", List.of(Long.toString(branch)));
        var sale = new LinkedHashMap<String, Object>();
        sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(branch));
        sale.put("productId", products.create(owner, "all", key(), product, "room-fixture").id()); sale.put("method", "카드");
        return Long.parseLong(payments.create(owner, "all", key(), sale, "room-fixture").id());
    }

    @Test void roomPaymentsReadLikeAnyPaymentAndPassPaymentsAreUnchanged() throws Exception {
        long room = room(first);
        long customer = customer(first);
        long pass = passPayment(customer, first);
        try (var cash = login(cashier); var s = login(owner)) {
            var approved = booked(cash, booking(first, room, customer, "14:00", "16:00"));
            var failed = booked(cash, failed(booking(first, room, customer, "16:00", "17:00")));
            String paymentId = approved.at("/billing/paymentId").asString();
            var detail = ok(get(s, "/api/payments/" + paymentId));
            matchesSchema(detail, "Payment");
            assertThat(detail.get("purpose").asString()).isEqualTo("스터디룸");
            assertThat(detail.get("productId").isNull()).isTrue();
            assertThat(detail.get("productName").isNull()).isTrue();
            assertThat(detail.get("passId").isNull()).isTrue();
            assertThat(detail.get("lockerAssignmentId").isNull()).isTrue();
            assertThat(detail.get("roomReservationId").asString()).isEqualTo(approved.get("id").asString());
            assertThat(detail.get("amountKrw").asLong()).isEqualTo(16000);
            assertThat(detail.get("status").asString()).isEqualTo("승인");
            assertThat(detail.get("mock").asBoolean()).isTrue();
            assertThat(detail.get("canRefund").asBoolean()).isFalse();
            var failedDetail = ok(get(s, "/api/payments/" + failed.at("/billing/paymentId").asString()));
            assertThat(failedDetail.get("status").asString()).isEqualTo("실패");
            assertThat(failedDetail.get("roomReservationId").asString()).isEqualTo(failed.get("id").asString());
            var page = ok(get(s, "/api/payments?scope=" + first));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(3);
            var purposes = new ArrayList<String>();
            page.get("items").forEach(item -> purposes.add(item.get("purpose").asString()));
            assertThat(purposes).containsExactlyInAnyOrder("스터디룸", "스터디룸", "이용권");
            var passDetail = ok(get(s, "/api/payments/" + pass));
            matchesSchema(passDetail, "Payment");
            assertThat(passDetail.get("purpose").asString()).isEqualTo("이용권");
            assertThat(passDetail.get("productId").isNull()).isFalse();
            assertThat(passDetail.get("productName").asString()).isEqualTo("시간권");
            assertThat(passDetail.get("passId").isNull()).isFalse();
            assertThat(passDetail.get("roomReservationId").isNull()).isTrue();
            assertThat(passDetail.get("lockerAssignmentId").isNull()).isTrue();
            assertThat(passDetail.get("canRefund").asBoolean()).isTrue();
        }
    }

    @Test void aRoomPaymentCannotBeRefundedThroughThePaymentApi() throws Exception {
        long room = room(first);
        long customer = customer(first);
        long pass = passPayment(customer, first);
        try (var s = login(cashier)) {
            var approved = booked(s, booking(first, room, customer, "14:00", "15:00"));
            var failed = booked(s, failed(booking(first, room, customer, "15:00", "16:00")));
            for (var made : List.of(approved, failed)) {
                long id = made.at("/billing/paymentId").asLong();
                var refund = http(s.client(), "POST", "/api/payments/" + id + "/refund", s.token(), Map.of("reason", "고객 요청"), key());
                refused(refund, 409, "PAYMENT_NOT_REFUNDABLE");
            }
            assertThat(count("SELECT COUNT(*) FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id=?)", customer)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, approved.at("/billing/paymentId").asLong())).isEqualTo("APPROVED");
            assertThat(ok(get(s, "/api/room-reservations/" + approved.get("id").asString())).get("status").asString()).isEqualTo("확정");
            // A pass payment still refunds through the same route.
            var refund = http(s.client(), "POST", "/api/payments/" + pass + "/refund", s.token(), Map.of("reason", "고객 요청"), key());
            assertThat(refund.statusCode()).as(refund.body()).isEqualTo(200);
            assertThat(ok(refund).get("purpose").asString()).isEqualTo("이용권");
        }
    }

    @Test void aRoomPaymentCannotBeMadeThroughThePaymentApi() throws Exception {
        long customer = customer(first);
        try (var s = login(cashier)) {
            var sale = new LinkedHashMap<String, Object>();
            sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(first)); sale.put("method", "카드");
            refused(http(s.client(), "POST", "/api/payments", s.token(), sale, key()), 422, "VALIDATION_FAILED");
            refused(http(s.client(), "POST", "/api/payments", s.token(), with(sale, "purpose", "스터디룸"), key()), 422, "VALIDATION_FAILED");
            assertThat(payments(customer)).isZero();
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

    void onlyOneIsConfirmed(List<HttpResponse<String>> responses) {
        assertThat(statuses(responses).stream().filter(code -> code == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
        for (var response : responses) if (response.statusCode() != 201) refused(response, 409, "ROOM_TIME_CONFLICT");
    }

    @Test void onlyOneOfManySimultaneousBookingsOfTheSameTimeIsConfirmed() throws Exception {
        try (var one = login(cashier); var two = login(twoBranches)) {
            for (int round = 0; round < 3; round++) {
                long room = room(first);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                var people = new ArrayList<Long>();
                for (int index = 0; index < 8; index++) {
                    long customer = customer(first);
                    people.add(customer);
                    var session = index % 2 == 0 ? one : two;
                    calls.add(() -> book(session, booking(first, room, customer, "14:00", "16:00"), key()));
                }
                onlyOneIsConfirmed(race(calls));
                assertThat(count("SELECT COUNT(*) FROM room_reservations WHERE room_id=?", room)).isEqualTo(1);
                assertThat(slots(room)).isEqualTo(2);
                String inList = people.stream().map(String::valueOf).collect(Collectors.joining(","));
                assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id IN (" + inList + ")")).as("losers leave no payment").isEqualTo(1);
            }
        }
    }

    @Test void simultaneousBookingsThatOverlapOnlyPartlyNeverDoubleBook() throws Exception {
        try (var one = login(cashier); var two = login(twoBranches)) {
            for (int round = 0; round < 3; round++) {
                long room = room(first);
                long customer = customer(first);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int start = 10; start < 18; start++) {
                    String from = start + ":00", to = (start + 2) + ":00";
                    var session = start % 2 == 0 ? one : two;
                    calls.add(() -> book(session, booking(first, room, customer, from, to), key()));
                }
                var responses = race(calls);
                var won = new ArrayList<JsonNode>();
                for (var response : responses) {
                    if (response.statusCode() == 201) won.add(node(response));
                    else refused(response, 409, "ROOM_TIME_CONFLICT");
                }
                assertThat(won).isNotEmpty();
                var taken = new HashSet<Instant>();
                for (var reservation : won) {
                    assertThat(taken.add(Instant.parse(reservation.get("startAt").asString()))).isTrue();
                    assertThat(taken.add(Instant.parse(reservation.get("startAt").asString()).plus(Duration.ofHours(1)))).as("hours never overlap").isTrue();
                }
                assertThat(slots(room)).isEqualTo(won.size() * 2L);
                assertThat(payments(customer)).as("every payment has its confirmed reservation").isEqualTo(won.size());
                assertThat(bookings(customer)).isEqualTo(won.size());
            }
        }
    }

    @Test void simultaneousRetriesWithOneKeyChargeOnce() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var s = login(cashier)) {
            for (int round = 0; round < 3; round++) {
                String requestKey = key();
                var body = booking(first, room, customer, (10 + round) + ":00", (11 + round) + ":00");
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) calls.add(() -> book(s, body, requestKey));
                var responses = race(calls);
                assertThat(statuses(responses)).containsOnly(201);
                assertThat(responses.stream().map(this::node).map(made -> made.get("id").asLong()).distinct().count()).isEqualTo(1);
            }
            assertThat(payments(customer)).isEqualTo(3);
            assertThat(bookings(customer)).isEqualTo(3);
            assertThat(slots(room)).isEqualTo(3);
        }
    }

    @Test void simultaneousBookingsWithoutTheCheckAreStillStoppedByTheSlotKey() throws Exception {
        doReturn(false).when(reservationRepository).existsOverlapping(anyLong(), any(), any(), any());
        try (var s = login(cashier)) {
            long room = room(first);
            long customer = customer(first);
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) calls.add(() -> book(s, booking(first, room, customer, "14:00", "16:00"), key()));
            onlyOneIsConfirmed(race(calls));
            assertThat(payments(customer)).as("every loser rolled its payment back").isEqualTo(1);
            assertThat(bookings(customer)).isEqualTo(1);
            assertThat(slots(room)).isEqualTo(2);
        }
    }

    @Test void aBookingRacingARateChangePaysOneConsistentRate() throws Exception {
        try (var cash = login(cashier); var s = login(operator)) {
            for (int round = 0; round < 6; round++) {
                long room = room(first, 4, 8000);
                long customer = customer(first);
                int delay = round;
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> book(cash, booking(first, room, customer, "14:00", "16:00"), key()));
                calls.add(() -> { Thread.sleep(delay * 7L); return patch(s, room, Map.of("version", 1, "ratePerHourKrw", 9000)); });
                var responses = race(calls);
                assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isEqualTo(200);
                var made = node(responses.get(0));
                long rate = made.at("/billing/ratePerHourKrw").asLong();
                assertThat(rate).isIn(8000L, 9000L);
                assertThat(made.at("/billing/amountKrw").asLong()).isEqualTo(rate * 2);
                assertThat(count("SELECT amount_krw FROM payments WHERE customer_id=?", customer)).isEqualTo(rate * 2);
            }
        }
    }
}
