package com.openstudy.modules.seat;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #61: HTTP and MySQL behaviour of the seat ledger, stop/resume, board and occupancy (seat-usage-api.md). */
@MySqlIntegrationTest
class SeatIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    long owner, manager, bareManager, writer, reader, outsider, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "점장"); bareManager = person("EMPLOYEE", "권한없는점장"); writer = person("EMPLOYEE", "쓰기직원");
        reader = person("EMPLOYEE", "읽기직원"); outsider = person("EMPLOYEE", "타지점");
        first = branch("좌석 첫 지점"); second = branch("좌석 둘째 지점");
        member(manager, first, "MANAGER"); grant(manager, "operations:write");
        member(bareManager, first, "MANAGER");
        member(writer, first, "STAFF"); grant(writer, "operations:write");
        member(reader, first, "STAFF");
        member(outsider, second, "STAFF"); grant(outsider, "operations:write");
    }

    @AfterEach void clean() {
        for (long id : branches) {
            jdbc.update("DELETE FROM seat_sessions WHERE branch_id=?", id);
            jdbc.update("DELETE FROM seats WHERE branch_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM customers WHERE member_no LIKE 'SEATT-%'");
        for (long id : people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@seat.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("seat-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "seat-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    void grant(long person, String code) {
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code=? WHERE m.employee_id=?", code, person);
    }

    /** Direct ledger row, for fixtures that need many seats or a specific stored state. */
    long seat(long branch, String zone, String no, String kind, String status) {
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,?,?,?,?)", branch, zone, no, kind, status);
        return jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", Long.class, branch, no);
    }

    long customer(String name) {
        String memberNo = "SEATT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,?)", memberNo, name);
        return jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, memberNo);
    }

    /**
     * Check-in (#62) does not exist yet, so the session is inserted directly. The pass and payment chain is not what
     * these tests are about, so foreign keys are switched off on this one connection for the insert only.
     */
    long session(long seat, long customer, boolean active) {
        long branch = jdbc.queryForObject("SELECT branch_id FROM seats WHERE id=?", Long.class, seat);
        String requestKey = "SEATT-" + UUID.randomUUID();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                statement.execute("INSERT INTO seat_sessions(customer_id,pass_id,branch_id,seat_id,status,checked_in_at,effective_checked_in_at,"
                        + "checked_out_at,effective_checked_out_at,start_request_key) VALUES(" + customer + ",1," + branch + "," + seat + ","
                        + (active ? "'ACTIVE','2026-11-02 01:02:03.456789','2026-11-02 01:02:03.456789',NULL,NULL"
                                  : "'CLOSED','2026-11-02 01:02:03.456789','2026-11-02 01:02:03.456789','2026-11-02 02:02:03','2026-11-02 02:02:03'")
                        + ",'" + requestKey + "')");
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            return null;
        });
        return jdbc.queryForObject("SELECT id FROM seat_sessions WHERE start_request_key=?", Long.class, requestKey);
    }

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "seat-test-password"), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> send(Session session, String method, String path, Object body) throws Exception {
        return http(session.client(), method, path, session.token(), body, null);
    }

    HttpResponse<String> post(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/seats", session.token(), body, key);
    }

    Map<String, Object> seatBody(long branch, String zone, String no) {
        var body = new HashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("zone", zone); body.put("seatNo", no); body.put("kind", "일반");
        return body;
    }

    JsonNode made(Session session, Map<String, Object> body) throws Exception {
        var response = post(session, body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    Map<String, Object> versioned(JsonNode current, Object... pairs) {
        var body = new HashMap<String, Object>();
        body.put("version", current.get("version").asLong());
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    static String key() { return UUID.randomUUID().toString(); }
    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    JsonNode audit(long seat, String action) {
        var rows = jdbc.queryForList("SELECT * FROM audit_events WHERE entity_type='seats' AND entity_id=? AND action=?", seat, action);
        assertThat(rows).as("audit %s", action).hasSize(1);
        return json.valueToTree(rows.get(0));
    }

    @Test void aStaffMemberWithOperationsWriteRegistersATrimmedSeatAndTheServerRecordsAuditAndEvent() throws Exception {
        try (var s = login(writer)) {
            var body = seatBody(first, "  A구역 ", "  A-01 ");
            body.put("kind", "모니터");
            var response = post(s, body, key());
            assertThat(response.statusCode()).isEqualTo(201);
            var created = node(response);
            long id = created.get("id").asLong();
            assertThat(response.headers().firstValue("Location")).contains("/api/seats/" + id);
            assertThat(created.get("zone").asString()).isEqualTo("A구역");
            assertThat(created.get("seatNo").asString()).isEqualTo("A-01");
            assertThat(created.get("kind").asString()).isEqualTo("모니터");
            assertThat(created.get("status").asString()).isEqualTo("사용 가능");
            assertThat(created.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(created.get("version").asLong()).isEqualTo(1);
            assertThat(created.get("activeSession").isNull()).isTrue();
            assertThat(created.get("createdAt").asString()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT CONCAT(zone,'|',seat_no,'|',kind,'|',status) FROM seats WHERE id=?", String.class, id))
                    .isEqualTo("A구역|A-01|MONITOR|AVAILABLE");
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, id)).isEqualTo(1);
            var row = audit(id, "CREATED");
            assertThat(row.get("actor_employee_id").asLong()).isEqualTo(writer);
            assertThat(row.get("branch_id").asLong()).isEqualTo(first);
            assertThat(row.get("occurred_at").isNull()).isFalse();
            assertThat(json.readTree(row.get("after_json").asString()).get("seatNo").asString()).isEqualTo("A-01");
        }
    }

    @Test void aSeatMayBeRegisteredAlreadyStoppedAndThenShowsAsStopped() throws Exception {
        try (var s = login(writer)) {
            var body = seatBody(first, "A", "A-01");
            body.put("status", "운영 중단");
            var created = made(s, body);
            assertThat(created.get("status").asString()).isEqualTo("운영 중단");
            assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, created.get("id").asLong())).isEqualTo("BLOCKED");
        }
    }

    @Test void aDuplicateSeatNumberInTheSameBranchIs409ButTheSameNumberInAnotherBranchIsAllowed() throws Exception {
        try (var s = login(owner)) {
            made(s, seatBody(first, "A", "A-01"));
            var duplicate = post(s, seatBody(first, "B", "A-01"), key());
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(code(duplicate)).isEqualTo("DUPLICATE_SEAT_NO");
            // The column collation is case-insensitive, so the check agrees with uk_seats_1.
            assertThat(code(post(s, seatBody(first, "A", "a-01"), key()))).isEqualTo("DUPLICATE_SEAT_NO");
            assertThat(count("SELECT COUNT(*) FROM seats WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(post(s, seatBody(second, "A", "A-01"), key()).statusCode()).isEqualTo(201);
        }
    }

    @Test void registeringNeedsOperationsWriteAtThatBranchOnly() throws Exception {
        try (var s = login(reader)) {
            assertThat(post(s, seatBody(first, "A", "A-01"), key()).statusCode()).isEqualTo(403);
        }
        try (var s = login(outsider)) {
            // operations:write at another branch does not carry over.
            assertThat(post(s, seatBody(first, "A", "A-01"), key()).statusCode()).isEqualTo(403);
            assertThat(post(s, seatBody(second, "A", "A-01"), key()).statusCode()).isEqualTo(201);
        }
        try (var s = login(manager)) {
            assertThat(post(s, seatBody(first, "A", "A-02"), key()).statusCode()).isEqualTo(201);
        }
        assertThat(count("SELECT COUNT(*) FROM seats WHERE branch_id=?", first)).isEqualTo(1);
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(http(anonymous, "GET", "/api/seats", null, null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test void registrationInputIsValidatedStrictly() throws Exception {
        try (var s = login(owner)) {
            var blank = seatBody(first, "   ", "A-01");
            assertThat(post(s, blank, key()).statusCode()).isEqualTo(422);
            assertThat(post(s, seatBody(first, "A", "   "), key()).statusCode()).isEqualTo(422);
            assertThat(post(s, seatBody(first, "A".repeat(81), "A-01"), key()).statusCode()).isEqualTo(422);
            assertThat(post(s, seatBody(first, "A", "N".repeat(31)), key()).statusCode()).isEqualTo(422);
            var badKind = seatBody(first, "A", "A-01");
            badKind.put("kind", "VIP");
            assertThat(post(s, badKind, key()).statusCode()).isEqualTo(422);
            var occupied = seatBody(first, "A", "A-01");
            occupied.put("status", "사용 중");
            assertThat(post(s, occupied, key()).statusCode()).isEqualTo(422);
            var extra = seatBody(first, "A", "A-01");
            extra.put("version", 1);
            assertThat(post(s, extra, key()).statusCode()).isEqualTo(422);
            assertThat(post(s, seatBody(first, "A", "A-01"), null).statusCode()).isEqualTo(422);
            assertThat(post(s, seatBody(first, "A", "A-01"), "short").statusCode()).isEqualTo(422);
            assertThat(count("SELECT COUNT(*) FROM seats WHERE branch_id=?", first)).isZero();
        }
    }

    @Test void theSameKeyAndInputReplaysTheFirstResultAndADifferentInputWithTheKeyConflicts() throws Exception {
        try (var s = login(writer)) {
            String key = key();
            var original = post(s, seatBody(first, "A", "A-01"), key);
            var replay = post(s, seatBody(first, "A", "A-01"), key);
            assertThat(original.statusCode()).isEqualTo(201);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay).get("id").asString()).isEqualTo(node(original).get("id").asString());
            assertThat(count("SELECT COUNT(*) FROM seats WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seats' AND branch_id=? AND action='CREATED'", first)).isEqualTo(1);
            var different = post(s, seatBody(first, "A", "A-02"), key);
            assertThat(different.statusCode()).isEqualTo(409);
            assertThat(code(different)).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM seats WHERE branch_id=?", first)).isEqualTo(1);
        }
    }

    @Test void editingChangesZoneNumberAndKindKeepsAnInUseSeatInUseAndRecordsBeforeAndAfter() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        session(seat, customer("이용자"), true);
        try (var s = login(writer)) {
            var current = node(get(s, "/api/seats/" + seat));
            assertThat(current.get("status").asString()).isEqualTo("사용 중");
            var response = send(s, "PATCH", "/api/seats/" + seat, versioned(current, "zone", "  B  ", "seatNo", "B-07", "kind", "기타"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var changed = node(response);
            assertThat(changed.get("zone").asString()).isEqualTo("B");
            assertThat(changed.get("seatNo").asString()).isEqualTo("B-07");
            assertThat(changed.get("kind").asString()).isEqualTo("기타");
            assertThat(changed.get("status").asString()).isEqualTo("사용 중");
            assertThat(changed.get("version").asLong()).isEqualTo(current.get("version").asLong() + 1);
            var row = audit(seat, "UPDATED");
            assertThat(row.get("actor_employee_id").asLong()).isEqualTo(writer);
            var before = json.readTree(row.get("before_json").asString());
            var after = json.readTree(row.get("after_json").asString());
            assertThat(before.get("seatNo").asString()).isEqualTo("A-01");
            assertThat(after.get("seatNo").asString()).isEqualTo("B-07");
            assertThat(before.get("kind").asString()).isEqualTo("일반");
            assertThat(after.get("kind").asString()).isEqualTo("기타");
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='seats' AND entity_id=?", first, seat)).isEqualTo(1);
        }
    }

    @Test void editingRejectsStatusAndBranchStaleVersionsAndDuplicateNumbersAndIgnoresNoOps() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        seat(first, "A", "A-02", "GENERAL", "AVAILABLE");
        try (var s = login(writer)) {
            var current = node(get(s, "/api/seats/" + seat));
            var status = send(s, "PATCH", "/api/seats/" + seat, versioned(current, "status", "운영 중단"));
            assertThat(status.statusCode()).isEqualTo(422);
            assertThat(code(status)).isEqualTo("IMMUTABLE_FIELD");
            assertThat(code(send(s, "PATCH", "/api/seats/" + seat, versioned(current, "branchId", Long.toString(second))))).isEqualTo("IMMUTABLE_FIELD");
            assertThat(send(s, "PATCH", "/api/seats/" + seat, Map.of("version", 1)).statusCode()).isEqualTo(422);
            assertThat(send(s, "PATCH", "/api/seats/" + seat, versioned(current, "zone", " ")).statusCode()).isEqualTo(422);
            var duplicate = send(s, "PATCH", "/api/seats/" + seat, versioned(current, "seatNo", "A-02"));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(code(duplicate)).isEqualTo("DUPLICATE_SEAT_NO");
            // Identical values: no version bump, audit row or event.
            var same = node(send(s, "PATCH", "/api/seats/" + seat, versioned(current, "zone", "A", "seatNo", "A-01")));
            assertThat(same.get("version").asLong()).isEqualTo(current.get("version").asLong());
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seats' AND entity_id=?", seat)).isZero();
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='seats' AND entity_id=?", seat)).isZero();
            // A stale version is a conflict that names the latest version and where to reload.
            assertThat(send(s, "PATCH", "/api/seats/" + seat, versioned(current, "zone", "B")).statusCode()).isEqualTo(200);
            var stale = send(s, "PATCH", "/api/seats/" + seat, versioned(current, "zone", "C"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(current.get("version").asLong() + 1);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/seats/" + seat);
            assertThat(jdbc.queryForObject("SELECT zone FROM seats WHERE id=?", String.class, seat)).isEqualTo("B");
        }
    }

    @Test void editingNeedsOperationsWriteAndAnOtherBranchsSeatLooksNonexistent() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        long version = 1;
        try (var s = login(reader)) {
            assertThat(send(s, "PATCH", "/api/seats/" + seat, Map.of("version", version, "zone", "B")).statusCode()).isEqualTo(403);
        }
        try (var s = login(outsider)) {
            assertThat(send(s, "PATCH", "/api/seats/" + seat, Map.of("version", version, "zone", "B")).statusCode()).isEqualTo(404);
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", Map.of("version", version, "status", "운영 중단", "reason", "x")).statusCode()).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT zone FROM seats WHERE id=?", String.class, seat)).isEqualTo("A");
    }

    @Test void onlyAManagerWithOperationsWriteStopsAndResumesASeatAndTheAuditKeepsBeforeAfterReasonActorAndTime() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        for (long denied : List.of(writer, reader, bareManager)) {
            try (var s = login(denied)) {
                var current = node(get(s, "/api/seats/" + seat));
                assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "점검")).statusCode())
                        .as("employee %d", denied).isEqualTo(403);
            }
        }
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat)).isEqualTo("AVAILABLE");
        try (var s = login(manager)) {
            var current = node(get(s, "/api/seats/" + seat));
            var stopped = send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "  모니터 고장  "));
            assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
            assertThat(node(stopped).get("status").asString()).isEqualTo("운영 중단");
            assertThat(node(stopped).get("version").asLong()).isEqualTo(current.get("version").asLong() + 1);
            assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat)).isEqualTo("BLOCKED");
            var row = audit(seat, "STATUS_CHANGED");
            assertThat(row.get("actor_employee_id").asLong()).isEqualTo(manager);
            assertThat(row.get("branch_id").asLong()).isEqualTo(first);
            assertThat(row.get("reason").asString()).isEqualTo("모니터 고장");
            assertThat(row.get("occurred_at").isNull()).isFalse();
            assertThat(json.readTree(row.get("before_json").asString()).get("status").asString()).isEqualTo("사용 가능");
            assertThat(json.readTree(row.get("after_json").asString()).get("status").asString()).isEqualTo("운영 중단");
            // Resuming is a second change with its own audit row.
            var resumed = send(s, "POST", "/api/seats/" + seat + "/status", versioned(node(stopped), "status", "사용 가능", "reason", "수리 완료"));
            assertThat(resumed.statusCode()).isEqualTo(200);
            assertThat(node(resumed).get("status").asString()).isEqualTo("사용 가능");
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seats' AND entity_id=? AND action='STATUS_CHANGED'", seat)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='seats' AND entity_id=?", seat)).isEqualTo(2);
        }
        try (var s = login(owner)) {
            var current = node(get(s, "/api/seats/" + seat));
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "사장 지시")).statusCode()).isEqualTo(200);
        }
    }

    @Test void stoppingASeatInUseIs409AndLeavesItUntouchedUntilTheSessionEnds() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        long sessionId = session(seat, customer("이용자"), true);
        try (var s = login(manager)) {
            var current = node(get(s, "/api/seats/" + seat));
            var refused = send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "점검"));
            assertThat(refused.statusCode()).isEqualTo(409);
            assertThat(code(refused)).isEqualTo("SEAT_OCCUPIED");
            assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id=?", String.class, seat)).isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("SELECT version FROM seats WHERE id=?", Long.class, seat)).isEqualTo(current.get("version").asLong() - 1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seats' AND entity_id=?", seat)).isZero();
            // Once the session is closed the same request goes through.
            jdbc.update("UPDATE seat_sessions SET status='CLOSED', checked_out_at='2026-11-02 02:02:03', effective_checked_out_at='2026-11-02 02:02:03' WHERE id=?", sessionId);
            var retried = send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "점검"));
            assertThat(retried.statusCode()).isEqualTo(200);
        }
    }

    @Test void theSameStatusAgainReturnsTheCurrentValueWithoutAuditAndInputIsValidated() throws Exception {
        long seat = seat(first, "A", "A-01", "GENERAL", "BLOCKED");
        try (var s = login(manager)) {
            var current = node(get(s, "/api/seats/" + seat));
            var again = node(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "운영 중단", "reason", "이미 중단")));
            assertThat(again.get("version").asLong()).isEqualTo(current.get("version").asLong());
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='seats' AND entity_id=?", seat)).isZero();
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "사용 중", "reason", "x")).statusCode()).isEqualTo(422);
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "사용 가능")).statusCode()).isEqualTo(422);
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "사용 가능", "reason", " ")).statusCode()).isEqualTo(422);
            assertThat(send(s, "POST", "/api/seats/" + seat + "/status", versioned(current, "status", "사용 가능", "reason", "r".repeat(501))).statusCode()).isEqualTo(422);
            var stale = send(s, "POST", "/api/seats/" + seat + "/status", Map.of("version", current.get("version").asLong() + 5, "status", "사용 가능", "reason", "x"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
        }
    }

    @Test void aSeatOfAStoppedBranchCanStillBeRegisteredEditedAndStopped() throws Exception {
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var s = login(manager)) {
            var created = made(s, seatBody(first, "A", "A-01"));
            assertThat(send(s, "PATCH", "/api/seats/" + created.get("id").asString(), versioned(created, "zone", "B")).statusCode()).isEqualTo(200);
        }
    }

    @Test void anActiveSessionDecidesInUseAndShowsTheCustomerWhileAClosedSessionDoesNot() throws Exception {
        long busy = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        long done = seat(first, "A", "A-02", "GENERAL", "AVAILABLE");
        long customerId = customer("홍길동");
        long active = session(busy, customerId, true);
        session(done, customer("퇴실자"), false);
        try (var s = login(reader)) {
            var detail = node(get(s, "/api/seats/" + busy));
            assertThat(detail.get("status").asString()).isEqualTo("사용 중");
            assertThat(detail.at("/activeSession/id").asString()).isEqualTo(Long.toString(active));
            assertThat(detail.at("/activeSession/customer/id").asString()).isEqualTo(Long.toString(customerId));
            assertThat(detail.at("/activeSession/customer/name").asString()).isEqualTo("홍길동");
            assertThat(detail.at("/activeSession/customer/memberNo").asString()).startsWith("SEATT-");
            assertThat(detail.at("/activeSession/checkedInAt").asString()).isEqualTo("2026-11-02T01:02:03.456789Z");
            // Only the minimum customer data is exposed (M5-D06).
            assertThat(detail.at("/activeSession/customer").size()).isEqualTo(3);
            var closed = node(get(s, "/api/seats/" + done));
            assertThat(closed.get("status").asString()).isEqualTo("사용 가능");
            assertThat(closed.get("activeSession").isNull()).isTrue();
            var list = node(get(s, "/api/seats?scope=" + first));
            assertThat(list.at("/items/0/status").asString()).isEqualTo("사용 중");
            assertThat(list.at("/items/0/activeSession/customer/name").asString()).isEqualTo("홍길동");
            assertThat(list.at("/items/1/status").asString()).isEqualTo("사용 가능");
        }
    }

    @Test void theListIsOrderedByZoneSeatNumberAndIdAndFiltersMatchTheStoredSeats() throws Exception {
        long a2 = seat(first, "A", "A-2", "GENERAL", "AVAILABLE");
        long a10 = seat(first, "A", "A-10", "MONITOR", "AVAILABLE");
        long b1 = seat(first, "B", "B-1", "MONITOR", "BLOCKED");
        long a1 = seat(first, "A", "A-1", "OTHER", "AVAILABLE");
        session(a2, customer("이용자"), true);
        seat(second, "A", "Z-9", "GENERAL", "AVAILABLE");
        try (var s = login(owner)) {
            var all = node(get(s, "/api/seats?scope=" + first));
            // Plain string comparison: "A-10" sorts before "A-2" (documented in the contract).
            assertThat(ids(all)).containsExactly(a1, a10, a2, b1);
            assertThat(all.at("/meta/total").asLong()).isEqualTo(4);
            var monitors = node(get(s, "/api/seats?scope=" + first + "&kind=" + enc("모니터")));
            assertThat(ids(monitors)).containsExactly(a10, b1);
            assertThat(monitors.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(ids(node(get(s, "/api/seats?scope=" + first + "&zone=B")))).containsExactly(b1);
            assertThat(ids(node(get(s, "/api/seats?scope=" + first + "&status=" + enc("사용 중"))))).containsExactly(a2);
            assertThat(ids(node(get(s, "/api/seats?scope=" + first + "&status=" + enc("운영 중단"))))).containsExactly(b1);
            assertThat(ids(node(get(s, "/api/seats?scope=" + first + "&status=" + enc("사용 가능"))))).containsExactly(a1, a10);
            assertThat(ids(node(get(s, "/api/seats?scope=" + first + "&zone=A&kind=" + enc("모니터") + "&status=" + enc("사용 가능"))))).containsExactly(a10);
            var page = node(get(s, "/api/seats?scope=" + first + "&page=2&pageSize=3"));
            assertThat(ids(page)).containsExactly(b1);
            assertThat(page.at("/meta/total").asLong()).isEqualTo(4);
            assertThat(get(s, "/api/seats?scope=" + first + "&kind=VIP").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/seats?scope=" + first + "&status=" + enc("이용 중")).statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/seats?scope=" + first + "&page=0").statusCode()).isEqualTo(422);
        }
    }

    @Test void seatsOfABranchTheCallerCannotReachAreNeverReturned() throws Exception {
        long mine = seat(second, "A", "A-01", "GENERAL", "AVAILABLE");
        long theirs = seat(first, "A", "A-01", "GENERAL", "AVAILABLE");
        try (var s = login(outsider)) {
            assertThat(ids(node(get(s, "/api/seats")))).contains(mine).doesNotContain(theirs);
            assertThat(get(s, "/api/seats/" + theirs).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/seats/" + mine).statusCode()).isEqualTo(200);
            assertThat(get(s, "/api/seats?scope=" + first).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/seats/" + theirs + "?scope=" + second).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/seats/occupancy?scope=" + first).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/seats/board?branchId=" + first).statusCode()).isEqualTo(404);
            var occupancy = node(get(s, "/api/seats/occupancy"));
            for (var item : occupancy.get("items")) assertThat(item.get("branchId").asString()).isEqualTo(Long.toString(second));
            assertThat(get(s, "/api/seats/999999999").statusCode()).isEqualTo(404);
        }
    }

    @Test void occupancyDividesInUseByAllSeatsAndRoundsHalfUpToOneDecimal() throws Exception {
        // 4 of 10 in use; the stopped seat stays in the denominator.
        List<Long> seats = new ArrayList<>();
        for (int index = 1; index <= 10; index++) seats.add(seat(first, "A", String.format("A-%02d", index), "GENERAL", index == 10 ? "BLOCKED" : "AVAILABLE"));
        for (int index = 0; index < 4; index++) session(seats.get(index), customer("이용자" + index), true);
        session(seats.get(4), customer("퇴실자"), false);
        var third = branch("좌석 셋째 지점");
        var fourth = branch("좌석 넷째 지점");
        var fifth = branch("좌석 다섯째 지점");
        var oneOfThree = new ArrayList<Long>();
        for (int index = 1; index <= 3; index++) oneOfThree.add(seat(third, "A", "T-" + index, "GENERAL", "AVAILABLE"));
        session(oneOfThree.get(0), customer("한명"), true);
        var twoOfThree = new ArrayList<Long>();
        for (int index = 1; index <= 3; index++) twoOfThree.add(seat(fourth, "A", "F-" + index, "GENERAL", "AVAILABLE"));
        session(twoOfThree.get(0), customer("둘A"), true);
        session(twoOfThree.get(1), customer("둘B"), true);
        try (var s = login(owner)) {
            var ten = node(get(s, "/api/seats/occupancy?scope=" + first)).at("/items/0");
            assertThat(ten.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(ten.get("total").asLong()).isEqualTo(10);
            assertThat(ten.get("inUse").asLong()).isEqualTo(4);
            assertThat(ten.get("available").asLong()).isEqualTo(5);
            assertThat(ten.get("blocked").asLong()).isEqualTo(1);
            assertThat(ten.get("occupancyRate").asDouble()).isEqualTo(40.0);
            assertThat(ten.get("asOf").asString()).isNotBlank();
            assertThat(ten.get("inUse").asLong() + ten.get("available").asLong() + ten.get("blocked").asLong()).isEqualTo(ten.get("total").asLong());
            assertThat(node(get(s, "/api/seats/occupancy?scope=" + third)).at("/items/0/occupancyRate").asString()).isEqualTo("33.3");
            assertThat(node(get(s, "/api/seats/occupancy?scope=" + fourth)).at("/items/0/occupancyRate").asString()).isEqualTo("66.7");
            // No seats: zeros and "not applicable" (null), which is not the same as 0% of existing seats.
            var empty = node(get(s, "/api/seats/occupancy?scope=" + fifth)).at("/items/0");
            assertThat(empty.get("total").asLong()).isZero();
            assertThat(empty.get("inUse").asLong()).isZero();
            assertThat(empty.get("available").asLong()).isZero();
            assertThat(empty.get("blocked").asLong()).isZero();
            assertThat(empty.get("occupancyRate").isNull()).isTrue();
            seat(fifth, "A", "E-1", "GENERAL", "AVAILABLE");
            var idle = node(get(s, "/api/seats/occupancy?scope=" + fifth)).at("/items/0");
            assertThat(idle.get("total").asLong()).isEqualTo(1);
            assertThat(idle.get("occupancyRate").isNull()).isFalse();
            assertThat(idle.get("occupancyRate").asDouble()).isEqualTo(0.0);
            // scope=all lists every branch of the caller, by branch id, empty ones included.
            var wide = node(get(s, "/api/seats/occupancy"));
            var mine = new ArrayList<Long>();
            for (var item : wide.get("items")) if (branches.contains(item.get("branchId").asLong())) mine.add(item.get("branchId").asLong());
            assertThat(mine).containsExactlyElementsOf(branches.stream().sorted().toList());
        }
    }

    @Test void theBoardReturnsEverySeatOfTheBranchGroupedByZoneWithCountsFromThoseSeats() throws Exception {
        // 130 seats exceed the largest page size: the board must not page.
        for (int index = 1; index <= 120; index++) seat(first, "Z", String.format("Z-%03d", index), "GENERAL", "AVAILABLE");
        long a2 = seat(first, "A", "A-2", "MONITOR", "AVAILABLE");
        long a1 = seat(first, "A", "A-1", "GENERAL", "AVAILABLE");
        long b1 = seat(first, "B", "B-1", "MONITOR", "BLOCKED");
        seat(first, "B", "B-2", "OTHER", "AVAILABLE");
        seat(first, "B", "B-3", "OTHER", "AVAILABLE");
        session(a1, customer("이용자"), true);
        seat(second, "A", "S-1", "GENERAL", "AVAILABLE");
        try (var s = login(reader)) {
            var board = node(get(s, "/api/seats/board?branchId=" + first));
            assertThat(board.get("zones").size()).isEqualTo(3);
            assertThat(board.at("/zones/0/zone").asString()).isEqualTo("A");
            assertThat(board.at("/zones/1/zone").asString()).isEqualTo("B");
            assertThat(board.at("/zones/2/zone").asString()).isEqualTo("Z");
            assertThat(board.at("/zones/0/seats/0/id").asLong()).isEqualTo(a1);
            assertThat(board.at("/zones/0/seats/1/id").asLong()).isEqualTo(a2);
            assertThat(board.at("/zones/0/seats/0/status").asString()).isEqualTo("사용 중");
            assertThat(board.at("/zones/0/seats/0/activeSession/customer/name").asString()).isEqualTo("이용자");
            assertThat(board.at("/zones/2/seats").size()).isEqualTo(120);
            var counts = board.get("counts");
            assertThat(counts.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(counts.get("total").asLong()).isEqualTo(125);
            assertThat(counts.get("inUse").asLong()).isEqualTo(1);
            assertThat(counts.get("blocked").asLong()).isEqualTo(1);
            assertThat(counts.get("available").asLong()).isEqualTo(123);
            assertThat(counts.get("occupancyRate").asString()).isEqualTo("0.8");
            // The board's numbers equal the occupancy numbers taken in the same state.
            var occupancy = node(get(s, "/api/seats/occupancy?scope=" + first)).at("/items/0");
            for (String field : List.of("total", "inUse", "available", "blocked")) assertThat(counts.get(field).asLong()).as(field).isEqualTo(occupancy.get(field).asLong());
            // A kind filter narrows the seats and the counts alike.
            var monitors = node(get(s, "/api/seats/board?branchId=" + first + "&kind=" + enc("모니터")));
            assertThat(monitors.at("/counts/total").asLong()).isEqualTo(2);
            assertThat(monitors.at("/counts/blocked").asLong()).isEqualTo(1);
            assertThat(monitors.at("/counts/inUse").asLong()).isZero();
            assertThat(monitors.at("/counts/occupancyRate").asString()).isEqualTo("0.0");
            assertThat(monitors.get("zones").size()).isEqualTo(2);
            assertThat(monitors.at("/zones/1/seats/0/id").asLong()).isEqualTo(b1);
        }
    }

    @Test void theBoardValidatesItsParametersAndShowsAnEmptyBranchWithoutARate() throws Exception {
        try (var s = login(owner)) {
            assertThat(get(s, "/api/seats/board").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/seats/board?branchId=abc").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/seats/board?branchId=" + first + "&kind=VIP").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/seats/board?branchId=999999999").statusCode()).isEqualTo(404);
            var empty = node(get(s, "/api/seats/board?branchId=" + first));
            assertThat(empty.get("zones").size()).isZero();
            assertThat(empty.at("/counts/total").asLong()).isZero();
            assertThat(empty.at("/counts/occupancyRate").isNull()).isTrue();
        }
    }

    List<Long> ids(JsonNode page) {
        var result = new ArrayList<Long>();
        for (var item : page.get("items")) result.add(item.get("id").asLong());
        return result;
    }

    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
