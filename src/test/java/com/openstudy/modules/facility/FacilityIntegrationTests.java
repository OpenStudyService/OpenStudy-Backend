package com.openstudy.modules.facility;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Issue #78: HTTP and MySQL behaviour of the facility contract (collaboration-api.md, ADR-M7-001 M7-D03·D05·D06),
 * including the Asia/Seoul date rules and real concurrent requests against one facility.
 */
@MySqlIntegrationTest
@Import(FacilityIntegrationTests.TimeConfiguration.class)
class FacilityIntegrationTests {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2030-06-16 01:00 in Seoul, still 2030-06-15 in UTC: the instant where the two calendars disagree. */
    static final Instant DATELINE = Instant.parse("2030-06-15T16:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired MutableClock clock;
    @LocalServerPort int port;

    /** null follows the real clock. Tests that need exact instants pin it before they log in. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock facilityClock() { return new MutableClock(); }
    }

    long owner, manager, writer, assignee, bystander, outsider, first, second, type, weeklyType;
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();

    @BeforeEach void setup() {
        clock.fixed = null;
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "관리자"); writer = person("EMPLOYEE", "시설 담당"); assignee = person("EMPLOYEE", "점검자");
        bystander = person("EMPLOYEE", "동료"); outsider = person("EMPLOYEE", "타지점");
        first = branch("시설 첫 지점"); second = branch("시설 둘째 지점");
        member(manager, first, "MANAGER"); member(writer, first, "STAFF"); grant(writer, "facilities:write");
        member(assignee, first, "STAFF"); member(bystander, first, "STAFF");
        member(outsider, second, "STAFF"); grant(outsider, "facilities:write");
        type = typeRow("공기청정기", 30);
        weeklyType = typeRow("소화기", 7);
    }

    @AfterEach void clean() {
        clock.fixed = null;
        for (long id : branches) {
            jdbc.update("DELETE w FROM facility_works w JOIN facilities f ON f.id=w.facility_id WHERE f.branch_id=?", id);
            jdbc.update("DELETE FROM facilities WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM facility_types WHERE name LIKE ?", token + "%");
        for (long id : people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@facility.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("facility-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "facility-fixture");
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

    long typeRow(String name, int days) {
        jdbc.update("INSERT INTO facility_types(name,default_inspection_days) VALUES(?,?)", token + name, days);
        return jdbc.queryForObject("SELECT id FROM facility_types WHERE name=?", Long.class, token + name);
    }

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "facility-test-password"), null).statusCode()).isEqualTo(200);
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
        return http(session.client(), "POST", "/api/facilities", session.token(), body, key);
    }

    HttpResponse<String> complete(Session session, JsonNode current, Map<String, Object> fields, String key) throws Exception {
        var body = new HashMap<String, Object>(fields);
        body.putIfAbsent("version", current.get("version").asLong());
        return http(session.client(), "POST", "/api/facilities/" + current.get("id").asString() + "/inspection/complete", session.token(), body, key);
    }

    Map<String, Object> done(String result, LocalDate on, String status) {
        var body = new HashMap<String, Object>();
        body.put("result", result); body.put("completedOn", on.toString()); body.put("facilityStatus", status);
        return body;
    }

    Map<String, Object> facility(long branch, long typeId, String name) {
        var body = new HashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("typeId", Long.toString(typeId)); body.put("name", name);
        return body;
    }

    JsonNode made(Session session, Map<String, Object> body) throws Exception {
        var response = post(session, body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    JsonNode reload(Session session, JsonNode current) throws Exception { return node(get(session, "/api/facilities/" + current.get("id").asString())); }

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
    long id(JsonNode facility) { return facility.get("id").asLong(); }
    long openWorks(long facility) { return count("SELECT COUNT(*) FROM facility_works WHERE facility_id=? AND kind='INSPECTION' AND status IN ('OPEN','IN_PROGRESS')", facility); }
    long doneWorks(long facility) { return count("SELECT COUNT(*) FROM facility_works WHERE facility_id=? AND kind='INSPECTION' AND status='DONE'", facility); }
    long total(Session session, String query) throws Exception { return node(get(session, "/api/facilities?" + query)).at("/meta/total").asLong(); }

    /** Rewrites the stored dates directly, so a rule can be checked at an exact boundary. */
    void stamp(JsonNode facility, String createdAtUtc, String lastInspectedOn, int interval, String status) {
        jdbc.update("UPDATE facilities SET created_at=?, last_inspected_on=?, inspection_interval_days=?, status=? WHERE id=?",
                createdAtUtc, lastInspectedOn, interval, status, id(facility));
    }

    /** The given field of every element; findValuesAsString would also pick up the nested type name. */
    static List<String> field(JsonNode items, String name) {
        var values = new ArrayList<String>();
        items.forEach(item -> values.add(item.get(name).asString()));
        return values;
    }

    static LocalDate seoulDate(String instant) { return LocalDate.ofInstant(Instant.parse(instant), SEOUL); }

    // ---- facility types -------------------------------------------------------------------------------------

    @Test void onlyTheOwnerRegistersAFacilityTypeAndANameIsUnique() throws Exception {
        String name = token + "정수기";
        try (var s = login(owner)) {
            String key = key();
            var response = http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", "  " + name + "  ", "defaultInspectionDays", 14), key);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            var created = node(response);
            assertThat(created.get("name").asString()).isEqualTo(name);
            assertThat(created.get("defaultInspectionDays").asInt()).isEqualTo(14);
            assertThat(count("SELECT COUNT(*) FROM facility_types WHERE name=?", name)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facility_types' AND entity_id=? AND action='CREATED' AND actor_employee_id=?",
                    created.get("id").asLong(), owner)).isEqualTo(1);

            // The same key with the same input replays; a different input under that key is refused.
            var replay = http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", "  " + name + "  ", "defaultInspectionDays", 14), key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay).get("id").asString()).isEqualTo(created.get("id").asString());
            var changed = http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", name, "defaultInspectionDays", 15), key);
            assertThat(changed.statusCode()).isEqualTo(409);
            assertThat(code(changed)).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM facility_types WHERE name LIKE ?", token + "정수기%")).isEqualTo(1);

            // The column's collation ignores case, so a differently written name is the same type.
            String latin = token + "Filter";
            assertThat(http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", latin, "defaultInspectionDays", 3), key()).statusCode()).isEqualTo(201);
            var duplicate = http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", latin.toUpperCase(), "defaultInspectionDays", 3), key());
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(code(duplicate)).isEqualTo("DUPLICATE_FACILITY_TYPE");

            assertThat(http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", token + "키없음", "defaultInspectionDays", 3), null).statusCode()).isEqualTo(422);
            for (Object days : List.of(0, 3651, "7", 1.5, true)) {
                var refused = http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", token + "주기 " + days, "defaultInspectionDays", days), key());
                assertThat(refused.statusCode()).as("%s", days).isEqualTo(422);
            }
            assertThat(http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", token + "주기없음"), key()).statusCode()).isEqualTo(422);
            assertThat(http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", "x".repeat(81), "defaultInspectionDays", 3), key()).statusCode()).isEqualTo(422);
        }
        for (long other : List.of(manager, writer, assignee)) {
            try (var s = login(other)) {
                assertThat(http(s.client(), "POST", "/api/facility-types", s.token(), Map.of("name", token + "거부" + other, "defaultInspectionDays", 3), key()).statusCode()).isEqualTo(403);
            }
        }
        assertThat(count("SELECT COUNT(*) FROM facility_types WHERE name LIKE ?", token + "거부%")).isZero();
    }

    @Test void anyActiveSessionListsTypesByName() throws Exception {
        try (var s = login(bystander)) {
            var items = node(get(s, "/api/facility-types")).get("items");
            var names = field(items, "name").stream().filter(name -> name.startsWith(token)).toList();
            assertThat(names).containsExactly(token + "공기청정기", token + "소화기");
            assertThat(items.get(0).propertyNames().stream().toList()).containsExactlyInAnyOrder("id", "name", "defaultInspectionDays");
        }
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(http(anonymous, "GET", "/api/facility-types", null, null, null).statusCode()).isEqualTo(401);
        }
    }

    // ---- registration ---------------------------------------------------------------------------------------

    @Test void registrationNeedsFacilitiesWriteOnTheBranch() throws Exception {
        try (var s = login(writer)) {
            var created = made(s, facility(first, type, "  1층 공기청정기  "));
            assertThat(created.get("name").asString()).isEqualTo("1층 공기청정기");
        }
        try (var s = login(owner)) { made(s, facility(first, type, "소유자 등록")); made(s, facility(second, type, "둘째 지점")); }
        // A MANAGER role alone, an ordinary member and a writer of another branch are all refused.
        for (long denied : List.of(manager, assignee, bystander, outsider)) {
            try (var s = login(denied)) {
                var refused = post(s, facility(first, type, "거부 " + denied), key());
                assertThat(refused.statusCode()).as("%d", denied).isEqualTo(403);
            }
        }
        assertThat(count("SELECT COUNT(*) FROM facilities WHERE branch_id=?", first)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM facilities WHERE name LIKE '거부%'")).isZero();
    }

    @Test void registrationCreatesTheFacilityAndItsFirstOpenWorkTogether() throws Exception {
        try (var s = login(writer)) {
            var body = facility(first, type, "1층 공기청정기");
            body.put("location", "1층 로비"); body.put("description", "필터 교체 주기 확인"); body.put("assigneeId", Long.toString(assignee));
            var response = post(s, body, key());
            assertThat(response.statusCode()).isEqualTo(201);
            var created = node(response);
            long id = id(created);
            assertThat(response.headers().firstValue("Location")).contains("/api/facilities/" + id);
            assertThat(created.get("status").asString()).isEqualTo("정상");
            assertThat(created.get("inspectionIntervalDays").asInt()).isEqualTo(30);
            assertThat(created.at("/type/name").asString()).isEqualTo(token + "공기청정기");
            assertThat(created.get("location").asString()).isEqualTo("1층 로비");
            assertThat(created.get("description").asString()).isEqualTo("필터 교체 주기 확인");
            assertThat(created.get("lastInspectedOn").isNull()).isTrue();
            assertThat(created.get("lastInspection").isNull()).isTrue();
            assertThat(created.get("version").asLong()).isEqualTo(1);
            assertThat(created.get("canEdit").asBoolean()).isTrue();
            assertThat(created.get("canComplete").asBoolean()).isTrue();
            // The facility's condition and the processing status are two different fields.
            assertThat(created.at("/openWork/status").asString()).isEqualTo("대기");
            assertThat(created.at("/openWork/assignee/id").asString()).isEqualTo(Long.toString(assignee));
            assertThat(created.at("/openWork/assignee/name").asString()).isEqualTo("점검자");
            assertThat(created.at("/openWork/openedAt").isString()).isTrue();
            // Never inspected: due on the registration date, so it is already due.
            assertThat(created.get("nextInspectionOn").asString()).isEqualTo(seoulDate(created.get("createdAt").asString()).toString());
            assertThat(created.get("inspectionDue").asBoolean()).isTrue();

            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facility_works WHERE facility_id=?", id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT assigned_employee_id FROM facility_works WHERE facility_id=?", Long.class, id)).isEqualTo(assignee);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='CREATED' AND actor_employee_id=? AND branch_id=?",
                    id, writer, first)).isEqualTo(1);

            // An explicit interval wins over the type's default; no assignee leaves the work unassigned.
            var explicit = facility(first, type, "주기 지정");
            explicit.put("inspectionIntervalDays", 5); explicit.put("status", "고장");
            var custom = made(s, explicit);
            assertThat(custom.get("inspectionIntervalDays").asInt()).isEqualTo(5);
            assertThat(custom.get("status").asString()).isEqualTo("고장");
            assertThat(custom.at("/openWork/assignee").isNull()).isTrue();
        }
    }

    @Test void aPastInspectionGivenAtRegistrationIsStoredAsAFinishedWork() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(writer)) {
            var body = facility(first, type, "이전 점검 있음");
            body.put("assigneeId", Long.toString(assignee)); body.put("lastInspectedOn", today.minusDays(10).toString());
            body.put("lastInspectionResult", "필터 교체 완료");
            var created = made(s, body);
            long id = id(created);
            assertThat(created.get("lastInspectedOn").asString()).isEqualTo(today.minusDays(10).toString());
            assertThat(created.get("nextInspectionOn").asString()).isEqualTo(today.plusDays(20).toString());
            assertThat(created.get("inspectionDue").asBoolean()).isFalse();
            assertThat(created.at("/lastInspection/result").asString()).isEqualTo("필터 교체 완료");
            assertThat(created.at("/lastInspection/completedOn").asString()).isEqualTo(today.minusDays(10).toString());
            assertThat(created.at("/lastInspection/completedBy/id").asString()).isEqualTo(Long.toString(writer));
            assertThat(doneWorks(id)).isEqualTo(1);
            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT result_note FROM facility_works WHERE facility_id=? AND status='DONE'", String.class, id)).isEqualTo("필터 교체 완료");

            // A date alone is accepted, with no result to show; a result alone, or a date after today, is not.
            var dateOnly = facility(first, type, "날짜만");
            dateOnly.put("lastInspectedOn", today.toString());
            var plain = made(s, dateOnly);
            assertThat(plain.get("lastInspection").isNull()).isTrue();
            assertThat(doneWorks(id(plain))).isZero();
            var resultOnly = facility(first, type, "결과만");
            resultOnly.put("lastInspectionResult", "결과");
            assertThat(post(s, resultOnly, key()).statusCode()).isEqualTo(422);
            var future = facility(first, type, "미래");
            future.put("lastInspectedOn", today.plusDays(1).toString()); future.put("lastInspectionResult", "결과");
            assertThat(post(s, future, key()).statusCode()).isEqualTo(422);
            var blankResult = facility(first, type, "빈 결과");
            blankResult.put("lastInspectedOn", today.toString()); blankResult.put("lastInspectionResult", "  ");
            assertThat(post(s, blankResult, key()).statusCode()).isEqualTo(422);
        }
    }

    @Test void registrationRejectsBadInputAndInvalidAssigneesWithoutLeavingRows() throws Exception {
        long revoked = person("EMPLOYEE", "회수"), disabled = person("EMPLOYEE", "비활성");
        member(revoked, first, "STAFF"); member(disabled, first, "STAFF");
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", revoked);
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", disabled);
        try (var s = login(writer)) {
            for (long invalid : List.of(outsider, revoked, disabled, owner, 999_999_999L)) {
                var body = facility(first, type, "잘못된 담당자");
                body.put("assigneeId", Long.toString(invalid));
                var refused = post(s, body, key());
                assertThat(refused.statusCode()).as("%d", invalid).isEqualTo(422);
                assertThat(code(refused)).isEqualTo("INVALID_ASSIGNEE");
            }
            assertThat(post(s, facility(first, 999_999_999L, "없는 유형"), key()).statusCode()).isEqualTo(404);
            assertThat(post(s, facility(first, type, "키 없음"), null).statusCode()).isEqualTo(422);
            for (var mutate : List.<java.util.function.Consumer<Map<String, Object>>>of(
                    body -> body.put("name", " "), body -> body.put("name", "x".repeat(121)), body -> body.put("location", "x".repeat(201)),
                    body -> body.put("description", "x".repeat(20001)), body -> body.put("inspectionIntervalDays", 0),
                    body -> body.put("inspectionIntervalDays", 3651), body -> body.put("status", "나쁨"),
                    body -> body.put("lastInspectedOn", "2030/01/01"), body -> body.put("createdBy", "1"),
                    body -> body.put("workStatus", "대기"), body -> body.put("assigneeId", 5))) {
                var body = facility(first, type, "검증");
                mutate.accept(body);
                assertThat(post(s, body, key()).statusCode()).as(body.toString()).isEqualTo(422);
            }
            assertThat(post(s, new HashMap<>(Map.of("branchId", Long.toString(first), "name", "유형 없음")), key()).statusCode()).isEqualTo(422);
            // A branch the writer does not belong to: refused, and nothing is stored.
            assertThat(post(s, facility(second, type, "다른 지점"), key()).statusCode()).isEqualTo(403);
        }
        assertThat(count("SELECT COUNT(*) FROM facilities WHERE branch_id IN (?,?)", first, second)).isZero();
        assertThat(count("SELECT COUNT(*) FROM facility_works w JOIN facilities f ON f.id=w.facility_id WHERE f.branch_id IN (?,?)", first, second)).isZero();
    }

    @Test void theSameRegistrationKeyStoresOneFacility() throws Exception {
        try (var s = login(writer)) {
            String key = key();
            var body = facility(first, type, "재전송");
            var one = post(s, body, key);
            var two = post(s, body, key);
            assertThat(one.statusCode()).isEqualTo(201);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(node(two).get("id").asString()).isEqualTo(node(one).get("id").asString());
            assertThat(node(two).get("version").asLong()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facilities WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facility_works w JOIN facilities f ON f.id=w.facility_id WHERE f.branch_id=?", first)).isEqualTo(1);
            var changed = facility(first, type, "다른 입력");
            var conflict = post(s, changed, key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM facilities WHERE branch_id=?", first)).isEqualTo(1);
        }
    }

    // ---- read model -----------------------------------------------------------------------------------------

    @Test void listItemsAndDetailKeepToTheContractShape() throws Exception {
        try (var s = login(writer)) {
            var created = made(s, facility(first, type, "모양"));
            var item = node(get(s, "/api/facilities")).at("/items/0");
            assertThat(item.propertyNames().stream().toList()).containsExactlyInAnyOrder("id", "branchId", "type", "name", "location", "status",
                    "inspectionIntervalDays", "lastInspectedOn", "nextInspectionOn", "inspectionDue", "openWork", "canEdit", "canComplete",
                    "version", "createdAt", "updatedAt");
            assertThat(created.propertyNames().stream().toList()).containsExactlyInAnyOrder("id", "branchId", "type", "name", "location", "status",
                    "inspectionIntervalDays", "lastInspectedOn", "nextInspectionOn", "inspectionDue", "openWork", "canEdit", "canComplete",
                    "version", "createdAt", "updatedAt", "description", "lastInspection");
            assertThat(item.at("/openWork").propertyNames().stream().toList()).containsExactlyInAnyOrder("id", "status", "assignee", "openedAt");
            assertThat(item.get("location").isNull()).isTrue();
        }
    }

    @Test void listAndDetailAreLimitedToTheBranchesOfTheReader() throws Exception {
        JsonNode inFirst, inSecond;
        try (var s = login(owner)) { inFirst = made(s, facility(first, type, "첫째")); inSecond = made(s, facility(second, type, "둘째")); }
        try (var s = login(assignee)) {
            assertThat(total(s, "")).isEqualTo(1);
            assertThat(get(s, "/api/facilities/" + id(inFirst)).statusCode()).isEqualTo(200);
            assertThat(get(s, "/api/facilities/" + id(inSecond)).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/facilities?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/facilities/summary?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/facilities/" + id(inFirst) + "?scope=" + second).statusCode()).isEqualTo(403);
            var summary = node(get(s, "/api/facilities/summary"));
            assertThat(summary.get("branches").size()).isEqualTo(1);
            assertThat(summary.at("/totals/total").asLong()).isEqualTo(1);
            // A reader without write rights still sees the facility, only with the actions switched off.
            assertThat(reload(s, inFirst).get("canEdit").asBoolean()).isFalse();
        }
        try (var s = login(owner)) { assertThat(total(s, "")).isEqualTo(2); }
        try (var s = login(outsider)) {
            assertThat(get(s, "/api/facilities/" + id(inFirst)).statusCode()).isEqualTo(404);
            assertThat(total(s, "")).isEqualTo(1);
        }
        // Losing the membership removes the facility from every read at once.
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", bystander, first);
        try (var s = login(bystander)) {
            assertThat(get(s, "/api/facilities/" + id(inFirst)).statusCode()).isEqualTo(404);
            assertThat(total(s, "")).isZero();
        }
    }

    // ---- update ---------------------------------------------------------------------------------------------

    @Test void updateNeedsFacilitiesWriteAndAnOutsideFacilityDoesNotExist() throws Exception {
        JsonNode created;
        try (var s = login(writer)) { created = made(s, facility(first, type, "수정 권한")); }
        String path = "/api/facilities/" + id(created);
        for (long denied : List.of(manager, assignee, bystander)) {
            try (var s = login(denied)) {
                assertThat(send(s, "PATCH", path, versioned(created, "name", "거부")).statusCode()).as("%d", denied).isEqualTo(403);
            }
        }
        // A writer of another branch cannot tell the facility exists.
        try (var s = login(outsider)) { assertThat(send(s, "PATCH", path, versioned(created, "name", "거부")).statusCode()).isEqualTo(404); }
        assertThat(jdbc.queryForObject("SELECT name FROM facilities WHERE id=?", String.class, id(created))).isEqualTo("수정 권한");
        try (var s = login(owner)) {
            var changed = send(s, "PATCH", path, versioned(created, "name", "소유자 수정"));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).get("name").asString()).isEqualTo("소유자 수정");
        }
    }

    @Test void updateChangesFacilityAssigneeAndProcessingStatusIndependently() throws Exception {
        try (var s = login(writer)) {
            var body = facility(first, type, "수정");
            body.put("assigneeId", Long.toString(assignee));
            var created = made(s, body);
            String path = "/api/facilities/" + id(created);
            long work = jdbc.queryForObject("SELECT id FROM facility_works WHERE facility_id=?", Long.class, id(created));

            // Only the open work changes: the facility's own condition stays, yet the version moves.
            var started = node(send(s, "PATCH", path, versioned(created, "workStatus", "진행 중")));
            assertThat(started.at("/openWork/status").asString()).isEqualTo("진행 중");
            assertThat(started.at("/openWork/id").asLong()).isEqualTo(work);
            assertThat(started.get("status").asString()).isEqualTo("정상");
            assertThat(started.get("version").asLong()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM facility_works WHERE id=?", String.class, work)).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("SELECT status FROM facilities WHERE id=?", String.class, id(created))).isEqualTo("NORMAL");

            // Only the facility changes: the processing status stays.
            var broken = node(send(s, "PATCH", path, versioned(started, "status", "고장", "location", "2층", "description", "소음", "inspectionIntervalDays", 10)));
            assertThat(broken.get("status").asString()).isEqualTo("고장");
            assertThat(broken.at("/openWork/status").asString()).isEqualTo("진행 중");
            assertThat(broken.get("location").asString()).isEqualTo("2층");
            assertThat(broken.get("inspectionIntervalDays").asInt()).isEqualTo(10);
            assertThat(broken.get("version").asLong()).isEqualTo(3);

            var typeChanged = node(send(s, "PATCH", path, versioned(broken, "typeId", Long.toString(weeklyType), "assigneeId", Long.toString(bystander))));
            assertThat(typeChanged.at("/type/id").asString()).isEqualTo(Long.toString(weeklyType));
            assertThat(typeChanged.at("/openWork/assignee/id").asString()).isEqualTo(Long.toString(bystander));
            assertThat(typeChanged.at("/openWork/id").asLong()).isEqualTo(work);

            var cleared = node(send(s, "PATCH", path, versioned(typeChanged, "assigneeId", null, "location", null, "workStatus", "대기")));
            assertThat(cleared.at("/openWork/assignee").isNull()).isTrue();
            assertThat(cleared.get("location").isNull()).isTrue();
            assertThat(cleared.at("/openWork/status").asString()).isEqualTo("대기");
            assertThat(openWorks(id(created))).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facility_works WHERE facility_id=?", id(created))).isEqualTo(1);
        }
    }

    @Test void updateRefusesImmutableFieldsEmptyChangesAndInvalidValues() throws Exception {
        try (var s = login(writer)) {
            var created = made(s, facility(first, type, "검증"));
            String path = "/api/facilities/" + id(created);
            for (var field : List.of("branchId", "lastInspectedOn")) {
                var refused = send(s, "PATCH", path, versioned(created, field, field.equals("branchId") ? Long.toString(second) : "2030-01-01"));
                assertThat(refused.statusCode()).as(field).isEqualTo(422);
                assertThat(code(refused)).isEqualTo("IMMUTABLE_FIELD");
            }
            assertThat(send(s, "PATCH", path, versioned(created)).statusCode()).isEqualTo(422);
            for (var pair : List.<Object[]>of(new Object[] {"name", " "}, new Object[] {"name", null}, new Object[] {"status", "나쁨"},
                    new Object[] {"workStatus", "완료"}, new Object[] {"workStatus", "정상"}, new Object[] {"inspectionIntervalDays", 0},
                    new Object[] {"typeId", null}, new Object[] {"unknown", "x"}, new Object[] {"assigneeId", 3})) {
                assertThat(send(s, "PATCH", path, versioned(created, pair[0].toString(), pair[1])).statusCode()).as("%s=%s", pair[0], pair[1]).isEqualTo(422);
            }
            assertThat(send(s, "PATCH", path, Map.of("name", "버전 없음")).statusCode()).isEqualTo(422);
            assertThat(send(s, "PATCH", path, versioned(created, "typeId", "999999999")).statusCode()).isEqualTo(404);
            for (long invalid : List.of(outsider, owner, 999_999_999L)) {
                var refused = send(s, "PATCH", path, versioned(created, "assigneeId", Long.toString(invalid)));
                assertThat(refused.statusCode()).as("%d", invalid).isEqualTo(422);
                assertThat(code(refused)).isEqualTo("INVALID_ASSIGNEE");
            }
            assertThat(send(s, "PATCH", "/api/facilities/999999999", versioned(created, "name", "없음")).statusCode()).isEqualTo(404);
            assertThat(reload(s, created).get("version").asLong()).isEqualTo(1);
            // The same values again are not a change.
            var same = node(send(s, "PATCH", path, versioned(created, "name", "검증", "status", "정상", "workStatus", "대기")));
            assertThat(same.get("version").asLong()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='UPDATED'", id(created))).isZero();
        }
    }

    @Test void aStaleVersionIs409WithTheLatestVersionAndAReloadUrl() throws Exception {
        try (var s = login(writer)) {
            var created = made(s, facility(first, type, "버전"));
            String path = "/api/facilities/" + id(created);
            assertThat(send(s, "PATCH", path, versioned(created, "name", "먼저")).statusCode()).isEqualTo(200);
            var stale = send(s, "PATCH", path, versioned(created, "name", "나중"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo(path);
            var staleComplete = complete(s, created, done("결과", LocalDate.now(SEOUL), "정상"), key());
            assertThat(staleComplete.statusCode()).isEqualTo(409);
            assertThat(code(staleComplete)).isEqualTo("VERSION_CONFLICT");
            assertThat(node(staleComplete).get("reloadUrl").asString()).isEqualTo(path);
            assertThat(doneWorks(id(created))).isZero();
            assertThat(reload(s, created).get("name").asString()).isEqualTo("먼저");
        }
    }

    @Test void updateAuditRecordsActorAndBeforeAndAfterValuesOfWhatChanged() throws Exception {
        try (var s = login(writer)) {
            var body = facility(first, type, "감사");
            body.put("assigneeId", Long.toString(assignee)); body.put("location", "1층");
            var created = made(s, body);
            long id = id(created);
            var edited = node(send(s, "PATCH", "/api/facilities/" + id, versioned(created, "name", "감사 수정", "status", "고장",
                    "assigneeId", Long.toString(bystander), "workStatus", "진행 중", "description", "새 설명")));
            assertThat(edited.get("version").asLong()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='UPDATED'", id)).isEqualTo(1);
            var row = jdbc.queryForMap("SELECT actor_employee_id, branch_id, before_json, after_json, request_id FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='UPDATED'", id);
            assertThat(((Number) row.get("actor_employee_id")).longValue()).isEqualTo(writer);
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
            assertThat(row.get("request_id")).isNotNull();
            var before = json.readTree((String) row.get("before_json"));
            var after = json.readTree((String) row.get("after_json"));
            assertThat(before.get("name").asString()).isEqualTo("감사");
            assertThat(after.get("name").asString()).isEqualTo("감사 수정");
            assertThat(before.get("status").asString()).isEqualTo("정상");
            assertThat(after.get("status").asString()).isEqualTo("고장");
            assertThat(before.get("assigneeId").asString()).isEqualTo(Long.toString(assignee));
            assertThat(after.get("assigneeId").asString()).isEqualTo(Long.toString(bystander));
            assertThat(before.get("workStatus").asString()).isEqualTo("대기");
            assertThat(after.get("workStatus").asString()).isEqualTo("진행 중");
            assertThat(before.get("description").isNull()).isTrue();
            assertThat(after.get("description").asString()).isEqualTo("새 설명");
            // Fields that did not change are not in the record.
            assertThat(before.has("location")).isFalse();
            assertThat(after.has("typeId")).isFalse();
        }
    }

    // ---- inspection completion ------------------------------------------------------------------------------

    @Test void completionClosesTheWorkUpdatesTheFacilityAndOpensTheNextWorkForTheSameAssignee() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(assignee)) {
            JsonNode created;
            try (var w = login(writer)) {
                var body = facility(first, weeklyType, "점검 완료");
                body.put("assigneeId", Long.toString(assignee));
                created = made(w, body);
            }
            long id = id(created);
            long closing = created.at("/openWork/id").asLong();
            var visible = reload(s, created);
            assertThat(visible.get("canComplete").asBoolean()).isTrue();
            assertThat(visible.get("canEdit").asBoolean()).isFalse();

            var response = complete(s, visible, done("  필터 교체, 소음 없음  ", today.minusDays(1), "점검 필요"), key());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var result = node(response);
            assertThat(result.get("status").asString()).isEqualTo("점검 필요");
            assertThat(result.get("lastInspectedOn").asString()).isEqualTo(today.minusDays(1).toString());
            assertThat(result.get("nextInspectionOn").asString()).isEqualTo(today.plusDays(6).toString());
            assertThat(result.get("inspectionDue").asBoolean()).isFalse();
            assertThat(result.get("version").asLong()).isEqualTo(2);
            assertThat(result.at("/lastInspection/result").asString()).isEqualTo("필터 교체, 소음 없음");
            assertThat(result.at("/lastInspection/completedOn").asString()).isEqualTo(today.minusDays(1).toString());
            assertThat(result.at("/lastInspection/completedBy/id").asString()).isEqualTo(Long.toString(assignee));
            // A new waiting work for the same assignee; the closed one is history and has a different id.
            assertThat(result.at("/openWork/status").asString()).isEqualTo("대기");
            assertThat(result.at("/openWork/assignee/id").asString()).isEqualTo(Long.toString(assignee));
            assertThat(result.at("/openWork/id").asLong()).isNotEqualTo(closing);
            assertThat(result.get("canComplete").asBoolean()).isTrue();

            var closed = jdbc.queryForMap("SELECT status, result_note, completed_by, completed_at FROM facility_works WHERE id=?", closing);
            assertThat(closed.get("status")).isEqualTo("DONE");
            assertThat(closed.get("result_note")).isEqualTo("필터 교체, 소음 없음");
            assertThat(((Number) closed.get("completed_by")).longValue()).isEqualTo(assignee);
            assertThat(closed.get("completed_at")).isNotNull();
            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(doneWorks(id)).isEqualTo(1);
            var stored = jdbc.queryForMap("SELECT status, last_inspected_on FROM facilities WHERE id=?", id);
            assertThat(stored.get("status")).isEqualTo("CHECK_NEEDED");
            assertThat(stored.get("last_inspected_on").toString()).isEqualTo(today.minusDays(1).toString());

            var audit = jdbc.queryForMap("SELECT actor_employee_id, branch_id, before_json, after_json FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='INSPECTION_COMPLETED'", id);
            assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(assignee);
            assertThat(json.readTree((String) audit.get("before_json")).get("status").asString()).isEqualTo("정상");
            assertThat(json.readTree((String) audit.get("after_json")).get("status").asString()).isEqualTo("점검 필요");
            assertThat(json.readTree((String) audit.get("after_json")).get("lastInspectedOn").asString()).isEqualTo(today.minusDays(1).toString());

            // The next work is completed in the same way, so the history grows by one and the open work stays single.
            var again = complete(s, result, done("재점검", today, "정상"), key());
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(node(again).get("nextInspectionOn").asString()).isEqualTo(today.plusDays(7).toString());
            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(doneWorks(id)).isEqualTo(2);
        }
    }

    @Test void onlyTheAssigneeOrAFacilitiesWriterMayCompleteAndMustStillBelongToTheBranch() throws Exception {
        JsonNode assigned, unassigned;
        try (var w = login(writer)) {
            var body = facility(first, type, "담당 있음");
            body.put("assigneeId", Long.toString(assignee));
            assigned = made(w, body);
            unassigned = made(w, facility(first, type, "담당 없음"));
        }
        LocalDate today = LocalDate.now(SEOUL);
        for (long denied : List.of(bystander, manager)) {
            try (var s = login(denied)) {
                var refused = complete(s, assigned, done("거부", today, "정상"), key());
                assertThat(refused.statusCode()).as("%d", denied).isEqualTo(403);
                assertThat(reload(s, assigned).get("canComplete").asBoolean()).isFalse();
            }
        }
        // The assignee of one facility has no right over another one without an assignee.
        try (var s = login(assignee)) { assertThat(complete(s, unassigned, done("거부", today, "정상"), key()).statusCode()).isEqualTo(403); }
        try (var s = login(outsider)) { assertThat(complete(s, assigned, done("다른 지점", today, "정상"), key()).statusCode()).isEqualTo(404); }
        assertThat(doneWorks(id(assigned)) + doneWorks(id(unassigned))).isZero();
        try (var s = login(writer)) {
            assertThat(complete(s, unassigned, done("시설 담당 완료", today, "정상"), key()).statusCode()).isEqualTo(200);
            // A writer may also complete another person's work.
            assertThat(complete(s, assigned, done("대신 완료", today, "정상"), key()).statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT assigned_employee_id FROM facility_works WHERE facility_id=? AND status='OPEN'", Long.class, id(assigned))).isEqualTo(assignee);
        try (var s = login(owner)) {
            var current = reload(s, unassigned);
            assertThat(complete(s, current, done("소유자 완료", today, "정상"), key()).statusCode()).isEqualTo(200);
        }
        // Once the assignee loses the membership, the assignment alone no longer allows completion.
        try (var s = login(assignee)) {
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", assignee, first);
            var current = jdbc.queryForObject("SELECT version FROM facilities WHERE id=?", Long.class, id(assigned)) + 1;
            var body = done("회수 후", today, "정상");
            body.put("version", current);
            var refused = http(s.client(), "POST", "/api/facilities/" + id(assigned) + "/inspection/complete", s.token(), body, key());
            assertThat(refused.statusCode()).isEqualTo(404);
        }
    }

    @Test void completionNeedsAResultAndAPlausibleDate() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(writer)) {
            var body = facility(first, type, "검증");
            body.put("lastInspectedOn", today.minusDays(3).toString());
            var created = made(s, body);
            long id = id(created);
            var good = done("결과", today, "정상");
            for (var mutate : List.<java.util.function.Consumer<Map<String, Object>>>of(
                    m -> m.remove("result"), m -> m.put("result", ""), m -> m.put("result", "   "), m -> m.put("result", "x".repeat(5001)),
                    m -> m.put("result", null), m -> m.remove("completedOn"), m -> m.remove("facilityStatus"), m -> m.put("facilityStatus", "나쁨"),
                    m -> m.put("completedOn", "2030/01/01"), m -> m.put("completedOn", today.plusDays(1).toString()),
                    m -> m.put("completedOn", today.minusDays(4).toString()), m -> m.put("note", "x"))) {
                var attempt = new HashMap<>(good);
                mutate.accept(attempt);
                var refused = complete(s, created, attempt, key());
                assertThat(refused.statusCode()).as(attempt.toString()).isEqualTo(422);
            }
            var noVersion = new HashMap<>(good);
            assertThat(http(s.client(), "POST", "/api/facilities/" + id + "/inspection/complete", s.token(), noVersion, key()).statusCode()).isEqualTo(422);
            assertThat(complete(s, created, good, null).statusCode()).isEqualTo(422);
            assertThat(doneWorks(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT last_inspected_on FROM facilities WHERE id=?", java.sql.Date.class, id).toLocalDate()).isEqualTo(today.minusDays(3));
            assertThat(reload(s, created).get("version").asLong()).isEqualTo(1);
            // The previous inspection date itself is a valid completion date (a second inspection on the same day).
            assertThat(complete(s, created, done("같은 날", today.minusDays(3), "정상"), key()).statusCode()).isEqualTo(200);
            // The completion moved the version, so the same stale version is now a conflict.
            assertThat(complete(s, created, good, key()).statusCode()).isEqualTo(409);
            var unknown = http(s.client(), "POST", "/api/facilities/999999999/inspection/complete", s.token(),
                    versioned(created, "result", "x", "completedOn", today.toString(), "facilityStatus", "정상"), key());
            assertThat(unknown.statusCode()).isEqualTo(404);
        }
    }

    @Test void completingTwiceWithTheSameKeyReplaysAndAnotherKeyWithAStaleVersionConflicts() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(writer)) {
            var created = made(s, facility(first, type, "멱등"));
            String key = key();
            var one = complete(s, created, done("결과", today, "정상"), key);
            var two = complete(s, created, done("결과", today, "정상"), key);
            assertThat(one.statusCode()).isEqualTo(200);
            assertThat(two.statusCode()).isEqualTo(200);
            assertThat(two.body()).isEqualTo(one.body());
            assertThat(doneWorks(id(created))).isEqualTo(1);
            assertThat(openWorks(id(created))).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='INSPECTION_COMPLETED'", id(created))).isEqualTo(1);
            var different = complete(s, created, done("다른 결과", today, "정상"), key);
            assertThat(different.statusCode()).isEqualTo(409);
            assertThat(code(different)).isEqualTo("IDEMPOTENCY_CONFLICT");
            var stale = complete(s, created, done("결과", today, "정상"), key());
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
            assertThat(doneWorks(id(created))).isEqualTo(1);
        }
    }

    @Test void completingWithoutChangingTheStatusOrDateStillAdvancesTheVersion() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(writer)) {
            var body = facility(first, type, "동일");
            body.put("lastInspectedOn", today.toString());
            var created = made(s, body);
            var result = node(complete(s, created, done("이상 없음", today, "정상"), key()));
            assertThat(result.get("version").asLong()).isEqualTo(created.get("version").asLong() + 1);
            assertThat(doneWorks(id(created))).isEqualTo(1);
            assertThat(openWorks(id(created))).isEqualTo(1);
        }
    }

    // ---- next inspection date and due ----------------------------------------------------------------------

    @Test void nextInspectionDateFollowsLastInspectionPlusIntervalAndRegistrationDateInSeoul() throws Exception {
        clock.fixed = DATELINE;
        try (var s = login(writer)) {
            var never = made(s, facility(first, type, "점검 이력 없음"));
            var inspected = made(s, facility(first, type, "점검 이력 있음"));
            // Registered 2030-06-15 23:59:59 in Seoul, 14:59:59 UTC.
            stamp(never, "2030-06-15 14:59:59", null, 30, "NORMAL");
            assertThat(reload(s, never).get("nextInspectionOn").asString()).isEqualTo("2030-06-15");
            // One second later it is the next day in Seoul although the UTC date is the same.
            stamp(never, "2030-06-15 15:00:00", null, 30, "NORMAL");
            var next = reload(s, never);
            assertThat(next.get("nextInspectionOn").asString()).isEqualTo("2030-06-16");
            assertThat(next.get("lastInspectedOn").isNull()).isTrue();
            // The interval does not count for a facility that was never inspected.
            stamp(never, "2030-06-15 15:00:00", null, 3650, "NORMAL");
            assertThat(reload(s, never).get("nextInspectionOn").asString()).isEqualTo("2030-06-16");

            stamp(inspected, "2030-01-01 00:00:00", "2030-06-01", 30, "NORMAL");
            assertThat(reload(s, inspected).get("nextInspectionOn").asString()).isEqualTo("2030-07-01");
            stamp(inspected, "2030-01-01 00:00:00", "2030-12-20", 20, "NORMAL");
            assertThat(reload(s, inspected).get("nextInspectionOn").asString()).isEqualTo("2031-01-09");

            // Recomputed after each change that matters: an interval edit, then a completion.
            stamp(inspected, "2030-01-01 00:00:00", "2030-06-10", 30, "NORMAL");
            var current = reload(s, inspected);
            var shorter = node(send(s, "PATCH", "/api/facilities/" + id(inspected), versioned(current, "inspectionIntervalDays", 5)));
            assertThat(shorter.get("nextInspectionOn").asString()).isEqualTo("2030-06-15");
            var completed = node(complete(s, shorter, done("완료", LocalDate.parse("2030-06-16"), "정상"), key()));
            assertThat(completed.get("nextInspectionOn").asString()).isEqualTo("2030-06-21");
            // Today (Seoul) is 2030-06-16, so a completion dated 06-17 is in the future.
            assertThat(complete(s, completed, done("미래", LocalDate.parse("2030-06-17"), "정상"), key()).statusCode()).isEqualTo(422);
        }
    }

    @Test void inspectionDueUsesTheSeoulDateAndExcludesFacilitiesOutOfService() throws Exception {
        clock.fixed = DATELINE; // Seoul today = 2030-06-16
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        try (var s = login(writer)) {
            for (var name : List.of("A 오늘 도래", "B 내일 도래", "C 사용 중지 지연", "D 등록일 자정", "E 등록일 23:59:59", "F 등록일 다음날", "G 오늘 점검", "H 어제 점검", "I 고장 지연"))
                rows.put(name.substring(0, 1), made(s, facility(first, type, name)));
            stamp(rows.get("A"), "2030-01-01 00:00:00", "2030-06-09", 7, "NORMAL");
            stamp(rows.get("B"), "2030-01-01 00:00:00", "2030-06-10", 7, "NORMAL");
            stamp(rows.get("C"), "2030-01-01 00:00:00", "2030-06-01", 7, "OUT_OF_SERVICE");
            stamp(rows.get("D"), "2030-06-15 15:00:00", null, 30, "NORMAL");
            stamp(rows.get("E"), "2030-06-16 14:59:59", null, 30, "CHECK_NEEDED");
            stamp(rows.get("F"), "2030-06-16 15:00:00", null, 30, "NORMAL");
            stamp(rows.get("G"), "2030-01-01 00:00:00", "2030-06-16", 1, "NORMAL");
            stamp(rows.get("H"), "2030-01-01 00:00:00", "2030-06-15", 1, "NORMAL");
            stamp(rows.get("I"), "2030-01-01 00:00:00", "2030-05-01", 7, "BROKEN");
            // Never inspected: D and E were registered on 06-16 in Seoul, so they are due; F was registered on 06-17.
            Set<String> due = new TreeSet<>(Set.of("A", "D", "E", "H", "I"));
            var all = node(get(s, "/api/facilities?pageSize=100")).get("items");
            Map<String, JsonNode> byName = new HashMap<>();
            all.forEach(item -> byName.put(item.get("name").asString().substring(0, 1), item));
            for (var entry : byName.entrySet())
                assertThat(entry.getValue().get("inspectionDue").asBoolean()).as(entry.getKey()).isEqualTo(due.contains(entry.getKey()));
            assertThat(byName.get("C").get("nextInspectionOn").asString()).isEqualTo("2030-06-08");
            assertThat(byName.get("F").get("nextInspectionOn").asString()).isEqualTo("2030-06-17");
            assertThat(byName.get("D").get("nextInspectionOn").asString()).isEqualTo("2030-06-16");

            var dueList = node(get(s, "/api/facilities?inspectionDue=true&pageSize=100"));
            assertThat(dueList.at("/meta/total").asLong()).isEqualTo(due.size());
            assertThat(field(dueList.get("items"), "name").stream().map(name -> name.substring(0, 1)).toList()).containsExactlyInAnyOrderElementsOf(due);
            assertThat(total(s, "inspectionDue=true&status=" + enc("사용 중지"))).isZero();
            assertThat(total(s, "inspectionDue=true&status=" + enc("고장"))).isEqualTo(1);
            assertThat(total(s, "inspectionDue=false")).isEqualTo(rows.size());

            // Pages of the due list are cut by the same rule the total uses.
            var seen = new ArrayList<String>();
            for (int page = 1; page <= 3; page++) {
                var part = node(get(s, "/api/facilities?inspectionDue=true&pageSize=2&page=" + page));
                assertThat(part.at("/meta/total").asLong()).isEqualTo(due.size());
                part.get("items").forEach(item -> seen.add(item.get("name").asString().substring(0, 1)));
            }
            assertThat(seen).hasSize(due.size()).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(due);
            // Default order is the soonest due date first, ties broken by id.
            assertThat(seen).containsExactly("I", "A", "D", "E", "H");

            var summary = node(get(s, "/api/facilities/summary"));
            assertThat(summary.at("/totals/inspectionDue").asLong()).isEqualTo(due.size());
            assertThat(summary.at("/totals/outOfService").asLong()).isEqualTo(1);
            assertThat(summary.at("/totals/total").asLong()).isEqualTo(rows.size());
        }
    }

    // ---- list and summary agree -----------------------------------------------------------------------------

    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }

    @Test void theSummaryEqualsTheTotalOfTheSameListQueryForEveryCountAndFilter() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var s = login(owner)) {
            String[] statuses = {"정상", "점검 필요", "고장", "사용 중지"};
            for (int index = 0; index < 8; index++) {
                long branch = index % 3 == 0 ? second : first;
                var body = facility(branch, index % 2 == 0 ? type : weeklyType, (index % 2 == 0 ? "공기 " : "소화 ") + index);
                body.put("status", statuses[index % 4]);
                body.put("inspectionIntervalDays", 3 + index);
                if (index % 4 == 1) { body.put("lastInspectedOn", today.minusDays(index).toString()); }
                if (index < 4) body.put("assigneeId", Long.toString(branch == first ? assignee : outsider));
                var created = node(post(s, body, key()));
                if (index % 5 == 0) {
                    send(s, "PATCH", "/api/facilities/" + id(created), versioned(created, "workStatus", "진행 중"));
                }
            }
            jdbc.update("UPDATE facilities SET last_inspected_on=? WHERE branch_id=? AND name LIKE '소화%'", today.minusDays(400).toString(), first);
            for (String filter : List.of("", "q=" + enc("공기"), "q=" + enc("소화"), "typeId=" + type, "typeId=" + weeklyType,
                    "assigneeId=" + assignee, "assigneeId=" + outsider, "workStatus=" + enc("진행 중"), "workStatus=" + enc("대기"),
                    "inspectionDue=true", "typeId=" + type + "&workStatus=" + enc("대기") + "&q=" + enc("공기"))) {
                String and = filter.isEmpty() ? "" : "&" + filter;
                var summary = node(get(s, "/api/facilities/summary?scope=all" + and));
                assertCounts(s, summary.get("totals"), "scope=all" + and);
                var perBranch = summary.get("branches");
                long sum = 0;
                for (var branch : perBranch) {
                    assertCounts(s, branch, "scope=" + branch.get("branchId").asString() + and);
                    sum += branch.get("total").asLong();
                }
                assertThat(sum).as(filter).isEqualTo(summary.at("/totals/total").asLong());
                var parts = summary.get("totals");
                assertThat(parts.get("normal").asLong() + parts.get("checkNeeded").asLong() + parts.get("broken").asLong() + parts.get("outOfService").asLong())
                        .as(filter).isEqualTo(parts.get("total").asLong());
            }
            // The summary accepts no status filter of its own: it is the sum of the four.
            assertThat(get(s, "/api/facilities/summary?status=" + enc("고장")).statusCode()).isEqualTo(200);
            assertThat(node(get(s, "/api/facilities/summary?status=" + enc("고장"))).at("/totals/total").asLong()).isEqualTo(8);
            assertThat(get(s, "/api/facilities/summary?workStatus=" + enc("완료")).statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/facilities/summary?inspectionDue=maybe").statusCode()).isEqualTo(422);
        }
    }

    private void assertCounts(Session s, JsonNode counts, String query) throws Exception {
        assertThat(counts.get("total").asLong()).as("total %s", query).isEqualTo(total(s, query));
        assertThat(counts.get("normal").asLong()).as("normal %s", query).isEqualTo(total(s, query + "&status=" + enc("정상")));
        assertThat(counts.get("checkNeeded").asLong()).as("checkNeeded %s", query).isEqualTo(total(s, query + "&status=" + enc("점검 필요")));
        assertThat(counts.get("broken").asLong()).as("broken %s", query).isEqualTo(total(s, query + "&status=" + enc("고장")));
        assertThat(counts.get("outOfService").asLong()).as("outOfService %s", query).isEqualTo(total(s, query + "&status=" + enc("사용 중지")));
        // A filter that already asks for inspectionDue=true must not repeat the parameter.
        String due = query.contains("inspectionDue=true") ? query : query + "&inspectionDue=true";
        assertThat(counts.get("inspectionDue").asLong()).as("inspectionDue %s", query).isEqualTo(total(s, due));
    }

    @Test void listFiltersSortsAndPagesInSql() throws Exception {
        try (var s = login(writer)) {
            for (var name : List.of("가 정수기", "나 정수기", "다 소화기", "라_특수%기")) {
                var body = facility(first, name.startsWith("다") ? weeklyType : type, name);
                body.put("location", name.startsWith("가") ? "별관 3층" : "본관");
                if (name.startsWith("나")) body.put("assigneeId", Long.toString(assignee));
                made(s, body);
            }
            assertThat(total(s, "q=" + enc("정수기"))).isEqualTo(2);
            assertThat(total(s, "q=" + enc("3층"))).isEqualTo(1);
            assertThat(total(s, "q=" + enc("_"))).isEqualTo(1);
            assertThat(total(s, "q=" + enc("%"))).isEqualTo(1);
            assertThat(total(s, "typeId=" + weeklyType)).isEqualTo(1);
            assertThat(total(s, "assigneeId=" + assignee)).isEqualTo(1);
            assertThat(total(s, "assigneeId=" + bystander)).isZero();
            assertThat(total(s, "workStatus=" + enc("대기"))).isEqualTo(4);
            assertThat(total(s, "workStatus=" + enc("진행 중"))).isZero();
            var byName = field(node(get(s, "/api/facilities?sort=name&direction=desc")).get("items"), "name");
            assertThat(byName).containsExactly("라_특수%기", "다 소화기", "나 정수기", "가 정수기");
            assertThat(field(node(get(s, "/api/facilities?sort=name")).get("items"), "name")).containsExactly("가 정수기", "나 정수기", "다 소화기", "라_특수%기");
            var created = field(node(get(s, "/api/facilities?sort=createdAt")).get("items"), "name");
            assertThat(created).hasSize(4);
            assertThat(field(node(get(s, "/api/facilities?pageSize=3&page=2&sort=name")).get("items"), "name")).containsExactly("라_특수%기");
            for (String bad : List.of("sort=password", "direction=up", "page=0", "pageSize=101", "typeId=abc", "assigneeId=0", "workStatus=" + enc("완료"),
                    "status=" + enc("나쁨"), "inspectionDue=yes", "scope=abc", "q=" + "x".repeat(101)))
                assertThat(get(s, "/api/facilities?" + bad).statusCode()).as(bad).isEqualTo(422);
            assertThat(get(s, "/api/facilities/abc").statusCode()).isIn(400, 404, 422);
        }
    }

    @Test void theDefaultSortIsTheSoonestDueFirstAndCoversFacilitiesThatWereNeverInspected() throws Exception {
        clock.fixed = DATELINE;
        try (var s = login(writer)) {
            var a = made(s, facility(first, type, "a")); var b = made(s, facility(first, type, "b"));
            var c = made(s, facility(first, type, "c")); var d = made(s, facility(first, type, "d"));
            stamp(a, "2030-01-01 00:00:00", "2030-06-20", 10, "NORMAL"); // 06-30
            stamp(b, "2030-06-01 00:00:00", null, 10, "NORMAL");         // 06-01 (registration date)
            stamp(c, "2030-01-01 00:00:00", "2030-06-01", 10, "NORMAL"); // 06-11
            stamp(d, "2030-01-01 00:00:00", "2030-06-01", 10, "NORMAL"); // 06-11, same date as c
            var ascending = node(get(s, "/api/facilities")).get("items");
            assertThat(field(ascending, "name")).containsExactly("b", "c", "d", "a");
            assertThat(field(ascending, "nextInspectionOn")).containsExactly("2030-06-01", "2030-06-11", "2030-06-11", "2030-06-30");
            assertThat(field(node(get(s, "/api/facilities?direction=desc")).get("items"), "name")).containsExactly("a", "d", "c", "b");
            assertThat(field(node(get(s, "/api/facilities?sort=nextInspectionOn&pageSize=2&page=2")).get("items"), "name")).containsExactly("d", "a");
            var sortedByValue = new ArrayList<>(field(ascending, "nextInspectionOn"));
            sortedByValue.sort(Comparator.naturalOrder());
            assertThat(field(ascending, "nextInspectionOn")).isEqualTo(sortedByValue);
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

    @Test void concurrentCompletionsOfOneFacilityLeaveExactlyOneOpenWork() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        JsonNode created;
        try (var w = login(writer)) {
            var body = facility(first, type, "동시 완료");
            body.put("assigneeId", Long.toString(assignee));
            created = made(w, body);
        }
        long id = id(created);
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 6; index++) sessions.add(login(index % 2 == 0 ? assignee : writer));
            // Everyone read the same version and has a different request key: one wins, the rest see a conflict.
            for (int round = 0; round < 3; round++) {
                var current = reload(sessions.get(0), created);
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < sessions.size(); index++) {
                    var session = sessions.get(index);
                    int number = index;
                    calls.add(() -> complete(session, current, done("동시 " + number, today, "정상"), key()));
                }
                var responses = race(calls);
                assertThat(statuses(responses)).as("round %d", round).containsExactly(200, 409, 409, 409, 409, 409);
                for (var response : responses) if (response.statusCode() == 409) assertThat(code(response)).isEqualTo("VERSION_CONFLICT");
                assertThat(openWorks(id)).as("round %d", round).isEqualTo(1);
                assertThat(doneWorks(id)).isEqualTo(round + 1);
                assertThat(jdbc.queryForObject("SELECT assigned_employee_id FROM facility_works WHERE facility_id=? AND status='OPEN'", Long.class, id)).isEqualTo(assignee);
            }
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='INSPECTION_COMPLETED'", id)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT version FROM facilities WHERE id=?", Long.class, id)).isEqualTo(3L);
        } finally {
            sessions.forEach(Session::close);
        }
    }

    @Test void concurrentCompletionsWithFreshVersionsAreAppliedOneAtATimeAndEachClosesAnOpenWork() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        JsonNode created;
        try (var w = login(writer)) { created = made(w, facility(first, type, "동시 재시도")); }
        long id = id(created);
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 4; index++) sessions.add(login(writer));
            var calls = new ArrayList<Callable<Integer>>();
            for (var session : sessions) calls.add(() -> {
                int applied = 0;
                for (int attempt = 0; attempt < 5; attempt++) {
                    var current = reload(session, created.deepCopy());
                    if (complete(session, current, done("재시도 " + attempt, today, "정상"), key()).statusCode() == 200) applied++;
                }
                return applied;
            });
            int applied = race(calls).stream().mapToInt(Integer::intValue).sum();
            assertThat(applied).isGreaterThanOrEqualTo(1);
            assertThat(doneWorks(id)).isEqualTo(applied);
            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM facilities WHERE id=?", Long.class, id)).isEqualTo((long) applied);
        } finally {
            sessions.forEach(Session::close);
        }
    }

    @Test void theSameCompletionKeySentConcurrentlyIsAppliedOnce() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        JsonNode created;
        try (var w = login(writer)) { created = made(w, facility(first, type, "동시 멱등")); }
        long id = id(created);
        String key = key();
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 5; index++) sessions.add(login(writer));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (var session : sessions) calls.add(() -> complete(session, created, done("한 번만", today, "정상"), key));
            var responses = race(calls);
            assertThat(statuses(responses)).containsOnly(200);
            assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
            assertThat(doneWorks(id)).isEqualTo(1);
            assertThat(openWorks(id)).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }

    @Test void aCompletionAndAnEditRacingOnTheSameVersionAreSerialized() throws Exception {
        LocalDate today = LocalDate.now(SEOUL);
        try (var w = login(writer); var other = login(owner)) {
            for (int round = 0; round < 4; round++) {
                var created = made(w, facility(first, type, "경합 " + round));
                long id = id(created);
                List<Callable<HttpResponse<String>>> calls = List.of(
                        () -> complete(w, created, done("경합 완료", today, "정상"), key()),
                        () -> send(other, "PATCH", "/api/facilities/" + id, versioned(created, "name", "경합 수정 " + created.get("id").asLong(), "workStatus", "진행 중")));
                var responses = race(calls);
                assertThat(statuses(responses)).as("round %d", round).containsExactly(200, 409);
                boolean completed = responses.get(0).statusCode() == 200;
                assertThat(openWorks(id)).isEqualTo(1);
                assertThat(doneWorks(id)).isEqualTo(completed ? 1 : 0);
                var stored = jdbc.queryForMap("SELECT name, version FROM facilities WHERE id=?", id);
                assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1L);
                assertThat(stored.get("name").toString().startsWith("경합 수정")).isEqualTo(!completed);
                // The open work is in the state the winner left it.
                assertThat(jdbc.queryForObject("SELECT status FROM facility_works WHERE facility_id=? AND status IN ('OPEN','IN_PROGRESS')", String.class, id))
                        .isEqualTo(completed ? "OPEN" : "IN_PROGRESS");
            }
        }
    }

    @Test void concurrentEditsWithTheSameVersionApplyOnce() throws Exception {
        try (var w = login(writer); var other = login(owner)) {
            var created = made(w, facility(first, type, "동시 수정"));
            long id = id(created);
            List<Callable<HttpResponse<String>>> calls = List.of(
                    () -> send(w, "PATCH", "/api/facilities/" + id, versioned(created, "workStatus", "진행 중")),
                    () -> send(other, "PATCH", "/api/facilities/" + id, versioned(created, "status", "고장")),
                    () -> send(w, "PATCH", "/api/facilities/" + id, versioned(created, "name", "동시 수정됨")));
            var responses = race(calls);
            assertThat(statuses(responses)).containsExactly(200, 409, 409);
            assertThat(jdbc.queryForObject("SELECT version FROM facilities WHERE id=?", Long.class, id)).isEqualTo(1L);
            assertThat(openWorks(id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='facilities' AND entity_id=? AND action='UPDATED'", id)).isEqualTo(1);
        }
    }

    @Test void theSameRegistrationKeySentConcurrentlyStoresOneFacility() throws Exception {
        String key = key();
        var body = facility(first, type, "동시 등록");
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 5; index++) sessions.add(login(writer));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (var session : sessions) calls.add(() -> post(session, body, key));
            var responses = race(calls);
            assertThat(statuses(responses)).containsOnly(201);
            assertThat(responses.stream().map(response -> node(response).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facilities WHERE branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM facility_works w JOIN facilities f ON f.id=w.facility_id WHERE f.branch_id=?", first)).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }
}
