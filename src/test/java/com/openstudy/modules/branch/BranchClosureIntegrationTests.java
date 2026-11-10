package com.openstudy.modules.branch;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.reservation.service.RoomService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #112 over HTTP and MySQL (operations-extension-api.md "지점 휴무일", M13-D01·D05): only a MANAGER of the branch keeps its
 * closures and the day is checked; a room reservation that starts on a closed day is refused with its own code; the
 * closure warns about the reservations already starting that day and cancels none; sales, clock-in and shifts go on;
 * a branch the reader cannot access shows no closure; the calendar and the home summary announce the days.
 */
@MySqlIntegrationTest
class BranchClosureIntegrationTests {
    static final String PASSWORD = "closure-test-password";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired RoomService roomService;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired PaymentService payments;
    @LocalServerPort int port;

    long owner, manager, staff, outsider, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    int roomNumber;

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "첫 지점 매니저");
        staff = person("EMPLOYEE", "첫 지점 직원");
        outsider = person("EMPLOYEE", "둘째 지점 매니저");
        first = branch("휴무 첫 지점");
        second = branch("휴무 둘째 지점");
        member(manager, first, "MANAGER", "payments:write");
        member(staff, first, "STAFF", "payments:write");
        member(outsider, second, "MANAGER");
    }

    @AfterEach void clean() {
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM room_reservation_slots WHERE reservation_id IN (SELECT id FROM room_reservations WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM room_reservations WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM passes WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        for (long id : jdbc.queryForList("SELECT id FROM products WHERE created_by IN (" + actors + ")", Long.class)) {
            jdbc.update("DELETE FROM product_revisions WHERE original_product_id=?", id);
            jdbc.update("DELETE FROM product_branches WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        jdbc.update("DELETE FROM product_categories WHERE created_by IN (" + actors + ")");
        for (long id : people) {
            jdbc.update("DELETE e FROM shift_events e JOIN shifts s ON s.id=e.shift_id WHERE s.employee_id=?", id);
            jdbc.update("DELETE FROM shifts WHERE employee_id=?", id);
            jdbc.update("DELETE FROM attendance_records WHERE employee_id=?", id);
        }
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM branch_closures WHERE branch_id=?", id);
            jdbc.update("DELETE FROM rooms WHERE branch_id=?", id);
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

    /** AC 1: only a MANAGER of the branch (or the OWNER) keeps its closures; the day and duplicates are checked. */
    @Test void onlyTheBranchManagerKeepsClosuresAndTheDayIsChecked() throws Exception {
        String day = day(20);
        try (var lead = login(manager); var member = login(staff); var other = login(outsider); var boss = login(owner)) {
            refused(create(member, first, Map.of("date", day)), 403, "FORBIDDEN");
            refused(create(other, first, Map.of("date", day)), 403, "FORBIDDEN");
            refused(create(lead, first, Map.of("date", "2026/12/25")), 422, "VALIDATION_FAILED");
            refused(create(lead, first, Map.of("date", day, "branchId", "1")), 422, "VALIDATION_FAILED");
            refused(create(lead, first, Map.of("date", day(-1))), 422, "CLOSURE_DATE_INVALID");
            refused(create(lead, first, Map.of("date", day(367))), 422, "CLOSURE_DATE_INVALID");
            refused(http(lead.client(), "POST", "/api/branches/" + first + "/closures", lead.token(), Map.of("date", day), null),
                    422, "VALIDATION_FAILED");

            String key = key();
            var made = created(create(lead, first, Map.of("date", day, "note", "내부 공사"), key));
            var closure = made.get("closure");
            assertThat(closure.get("date").asString()).isEqualTo(day);
            assertThat(closure.get("note").asString()).isEqualTo("내부 공사");
            assertThat(closure.get("version").asLong()).isEqualTo(1);
            assertThat(closure.at("/createdBy/name").asString()).isEqualTo("첫 지점 매니저");
            assertThat(closure.get("canEdit").asBoolean()).isTrue();
            assertThat(closure.get("canDelete").asBoolean()).isTrue();
            String id = closure.get("id").asString();
            // The same key and input replays the first answer; another input under it is refused.
            assertThat(created(create(lead, first, Map.of("date", day, "note", "내부 공사"), key)).at("/closure/id").asString()).isEqualTo(id);
            refused(create(lead, first, Map.of("date", day, "note", "다른 문구"), key), 409, "IDEMPOTENCY_CONFLICT");
            // A second closure of the day names the first one.
            var duplicate = create(boss, first, Map.of("date", day));
            refused(duplicate, 409, "BRANCH_CLOSURE_EXISTS");
            assertThat(node(duplicate).get("closureId").asString()).isEqualTo(id);
            assertThat(count("SELECT COUNT(*) FROM branch_closures WHERE branch_id=?", first)).isEqualTo(1);

            // Only the note changes, under its version.
            refused(update(member, id, Map.of("version", 1, "note", "바뀐 문구")), 403, "FORBIDDEN");
            refused(update(other, id, Map.of("version", 1, "note", "바뀐 문구")), 404, "NOT_FOUND");
            refused(update(lead, id, Map.of("version", 1, "date", day(21))), 422, "VALIDATION_FAILED");
            refused(update(lead, id, Map.of("version", 1)), 422, "VALIDATION_FAILED");
            var updated = ok(update(lead, id, Map.of("version", 1, "note", "바뀐 문구")));
            assertThat(updated.get("note").asString()).isEqualTo("바뀐 문구");
            assertThat(updated.get("version").asLong()).isEqualTo(2);
            refused(update(lead, id, Map.of("version", 1, "note", "늦은 문구")), 409, "VERSION_CONFLICT");
            var cleared = new LinkedHashMap<String, Object>();
            cleared.put("version", 2); cleared.put("note", null);
            assertThat(ok(update(lead, id, cleared)).get("note").isNull()).isTrue();

            // A future closure is deleted under its version; today's is kept as a record.
            refused(delete(member, id, 3), 403, "FORBIDDEN");
            refused(delete(lead, id, 1), 409, "VERSION_CONFLICT");
            refused(http(lead.client(), "DELETE", "/api/branch-closures/" + id, lead.token(), null, null), 422, "VALIDATION_FAILED");
            assertThat(delete(lead, id, 3).statusCode()).isEqualTo(204);
            assertThat(count("SELECT COUNT(*) FROM branch_closures WHERE branch_id=?", first)).isZero();
            refused(delete(lead, id, 3), 404, "NOT_FOUND");
            String today = created(create(lead, first, Map.of("date", day(0)))).at("/closure/id").asString();
            assertThat(ok(get(lead, "/api/branches/" + first + "/closures")).at("/items/0/canDelete").asBoolean()).isFalse();
            refused(delete(lead, today, 1), 409, "CLOSURE_ALREADY_STARTED");
            // Every change is audited at the branch.
            assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type='branch-closures' AND branch_id=? ORDER BY id",
                    String.class, first)).containsExactly("CREATED", "UPDATED", "UPDATED", "DELETED", "CREATED");
        }
    }

    /** AC 2 and 3: a closure warns about reservations already starting that day and cancels none; new ones are refused. */
    @Test void aClosureWarnsAboutExistingReservationsAndRefusesNewOnes() throws Exception {
        long room = room(first);
        long customer = customer(first);
        try (var lead = login(manager); var cash = login(staff)) {
            var booked = created(book(cash, booking(room, customer, 10, "10:00", "12:00")));
            created(book(cash, booking(room, customer, 10, "14:00", "15:00")));
            created(book(cash, booking(room, customer, 11, "10:00", "11:00")));

            var made = created(create(lead, first, Map.of("date", day(10))));
            assertThat(made.get("affectedReservationCount").asLong()).isEqualTo(2);
            var warned = made.get("affectedReservations");
            assertThat(warned).hasSize(2);
            assertThat(warned.get(0).get("id").asString()).isEqualTo(booked.get("id").asString());
            assertThat(warned.get(0).get("roomName").asString()).isEqualTo(booked.at("/room/name").asString());
            assertThat(warned.get(0).get("status").asString()).isEqualTo("확정");
            assertThat(fields(warned.get(0))).containsExactlyInAnyOrder("id", "roomId", "roomName", "startAt", "endAt", "status");
            assertThat(made.toString()).doesNotContain("customer");
            // Nothing is cancelled.
            assertThat(count("SELECT COUNT(*) FROM room_reservations WHERE customer_id=? AND status='CONFIRMED'", customer)).isEqualTo(3);

            // A new reservation starting on the closed day is refused with its own 409; the next day still books.
            long paymentsBefore = count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer);
            var refused = book(cash, booking(room, customer, 10, "16:00", "17:00"));
            refused(refused, 409, "BRANCH_CLOSED_DAY");
            assertThat(node(refused).get("closedDate").asString()).isEqualTo(day(10));
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer)).isEqualTo(paymentsBefore);
            // A time error is still a 422 before the closure.
            refused(book(cash, booking(room, customer, 10, "16:30", "17:00")), 422, "RESERVATION_TIME_INVALID");
            created(book(cash, booking(room, customer, 11, "16:00", "17:00")));
            // Seoul's day decides: 23:00 the day before is not the closed day, even though it ends on it.
            created(book(cash, booking(room, customer, 9, "23:00", "24:00")));

            // The calendar names the closed day of its period.
            var from = LocalDate.parse(day(9)).atStartOfDay(SEOUL).toInstant();
            var to = LocalDate.parse(day(12)).atStartOfDay(SEOUL).toInstant();
            var calendar = ok(get(cash, "/api/room-reservations/calendar?branchId=" + first + "&from=" + from + "&to=" + to));
            assertThat(texts(calendar.get("closedDates"))).containsExactly(day(10));
        }
    }

    /** AC 4: on a closed day sales, clock-in and shifts go on (M13-D01). */
    @Test void salesClockInAndShiftsGoOnOnAClosedDay() throws Exception {
        long customer = customer(first);
        try (var lead = login(manager); var member = login(staff)) {
            created(create(lead, first, Map.of("date", day(0))));
            created(create(lead, first, Map.of("date", day(1))));
            assertThat(passPayment(customer, first)).isPositive();
            assertThat(http(member.client(), "POST", "/api/me/attendance/clock-in", member.token(),
                    Map.of("branchId", Long.toString(first)), key()).statusCode()).isEqualTo(201);
            var shift = new LinkedHashMap<String, Object>();
            shift.put("branchId", Long.toString(first)); shift.put("employeeId", Long.toString(staff));
            shift.put("startsAt", seoul(1, "09:00").toString()); shift.put("endsAt", seoul(1, "18:00").toString());
            assertThat(http(lead.client(), "POST", "/api/shifts", lead.token(), shift, key()).statusCode()).isEqualTo(201);
        }
    }

    /** AC 5: a branch the reader cannot access shows no closure; the home summary announces only the scope's days. */
    @Test void closuresOfAnInaccessibleBranchStayHidden() throws Exception {
        try (var lead = login(manager); var member = login(staff); var other = login(outsider); var boss = login(owner)) {
            created(create(lead, first, Map.of("date", day(0), "note", "오늘 휴무")));
            created(create(lead, first, Map.of("date", day(7))));
            created(create(lead, first, Map.of("date", day(8))));
            created(create(other, second, Map.of("date", day(1))));

            refused(get(other, "/api/branches/" + first + "/closures"), 403, "FORBIDDEN");
            refused(get(member, "/api/branches/" + second + "/closures"), 403, "FORBIDDEN");
            var listed = ok(get(member, "/api/branches/" + first + "/closures"));
            assertThat(listed.get("items")).hasSize(3);
            assertThat(listed.at("/items/0/canEdit").asBoolean()).isFalse();
            assertThat(ok(get(member, "/api/branches/" + first + "/closures?from=" + day(1) + "&to=" + day(7))).get("items")).hasSize(1);
            refused(get(member, "/api/branches/" + first + "/closures?from=" + day(1)), 422, "VALIDATION_FAILED");
            refused(get(member, "/api/branches/" + first + "/closures?from=" + day(0) + "&to=" + day(367)), 422, "PERIOD_TOO_LONG");

            // The home summary: today through seven days ahead, the reader's scope only, date then branch order.
            var mine = ok(get(member, "/api/home/summary")).get("closures");
            assertThat(texts(mine, "date")).containsExactly(day(0), day(7));
            assertThat(mine.get(0).get("today").asBoolean()).isTrue();
            assertThat(mine.get(0).get("note").asString()).isEqualTo("오늘 휴무");
            assertThat(mine.get(0).get("branchName").asString()).isEqualTo("휴무 첫 지점");
            assertThat(texts(ok(get(boss, "/api/home/summary")).get("closures"), "date")).contains(day(0), day(1), day(7))
                    .doesNotContain(day(8));
            assertThat(texts(ok(get(other, "/api/home/summary")).get("closures"), "date")).containsExactly(day(1));
        }
    }

    /** The responses carry exactly the contract's fields. */
    @Test void responsesCarryExactlyTheContractFields() throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        long room = room(first);
        long customer = customer(first);
        try (var lead = login(manager); var cash = login(staff)) {
            created(book(cash, booking(room, customer, 5, "10:00", "11:00")));
            var made = created(create(lead, first, Map.of("date", day(5), "note", "점검")));
            same(spec, "BranchClosureCreated", made);
            same(spec, "BranchClosure", made.get("closure"));
            same(spec, "ActorSummary", made.at("/closure/createdBy"));
            same(spec, "AffectedReservation", made.at("/affectedReservations/0"));
            same(spec, "BranchClosureList", ok(get(lead, "/api/branches/" + first + "/closures")));
            var conflict = book(cash, booking(room, customer, 5, "12:00", "13:00"));
            assertThat(fields(node(conflict))).containsExactlyInAnyOrder("code", "message", "requestId", "closedDate");
            created(create(lead, first, Map.of("date", day(0))));
            same(spec, "HomeClosureItem", ok(get(lead, "/api/home/summary")).at("/closures/0"));
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@closure.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"),
                "closure-fixture");
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

    long customer(long branch) {
        var body = new LinkedHashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", "휴무 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "closure-fixture").id());
        customerIds.add(id);
        return id;
    }

    long room(long branch) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomNo", "C-" + ++roomNumber); body.put("name", "휴무 룸 " + roomNumber);
        body.put("capacity", 4); body.put("ratePerHourKrw", 8000);
        return Long.parseLong(roomService.create(owner, "all", key(), body, "closure-fixture").id());
    }

    long passPayment(long customer, long branch) {
        var category = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "분류", "kind", "시간권"), "closure-fixture");
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", "시간권"); terms.put("includedMinutes", 600); terms.put("validDays", null);
        var product = new LinkedHashMap<String, Object>();
        product.put("name", "시간권"); product.put("priceKrw", 15000L); product.put("terms", terms); product.put("status", "판매 중");
        product.put("categoryId", json.readTree(json.writeValueAsString(category)).get("id").asString());
        product.put("branchIds", List.of(Long.toString(branch)));
        var sale = new LinkedHashMap<String, Object>();
        sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(branch));
        sale.put("productId", products.create(owner, "all", key(), product, "closure-fixture").id()); sale.put("method", "카드");
        return Long.parseLong(payments.create(manager, "all", key(), sale, "closure-fixture").id());
    }

    /** A Seoul day relative to today, as the contract writes it. */
    static String day(int plusDays) { return LocalDate.now(SEOUL).plusDays(plusDays).toString(); }

    static Instant seoul(int plusDays, String time) {
        if (time.equals("24:00")) return LocalDate.now(SEOUL).plusDays(plusDays + 1L).atStartOfDay(SEOUL).toInstant();
        return LocalDate.now(SEOUL).plusDays(plusDays).atTime(java.time.LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    Map<String, Object> booking(long room, long customer, int plusDays, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(first)); body.put("roomId", Long.toString(room)); body.put("customerId", Long.toString(customer));
        body.put("startAt", seoul(plusDays, start).toString()); body.put("endAt", seoul(plusDays, end).toString());
        body.put("partySize", 2); body.put("method", "카드");
        return body;
    }

    static String key() { return UUID.randomUUID().toString(); }

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

    HttpResponse<String> create(Session session, long branch, Map<String, Object> body, String key) throws Exception {
        return http(session.client(), "POST", "/api/branches/" + branch + "/closures", session.token(), body, key);
    }

    HttpResponse<String> create(Session session, long branch, Map<String, Object> body) throws Exception { return create(session, branch, body, key()); }

    HttpResponse<String> update(Session session, String id, Map<String, Object> body) throws Exception {
        return http(session.client(), "PATCH", "/api/branch-closures/" + id, session.token(), body, null);
    }

    HttpResponse<String> delete(Session session, String id, long version) throws Exception {
        return http(session.client(), "DELETE", "/api/branch-closures/" + id + "?version=" + version, session.token(), null, null);
    }

    HttpResponse<String> book(Session session, Object body) throws Exception {
        return http(session.client(), "POST", "/api/room-reservations", session.token(), body, key());
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

    static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    static List<String> texts(JsonNode array, String field) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.get(field).asString());
        return values;
    }

    void same(JsonNode spec, String schema, JsonNode actual) {
        var expected = new HashSet<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> expected.add(entry.getKey()));
        assertThat(fields(actual)).as(schema).isEqualTo(expected);
    }
}
