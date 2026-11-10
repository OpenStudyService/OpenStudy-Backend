package com.openstudy.modules.home;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #90: GET /home/summary over real HTTP and MySQL. The worked examples of aggregation-api.md ("오늘의 업무와 완료율",
 * "근무") at 2026-11-05 10:00 Asia/Seoul, each card compared with the source operation it names, the M8-D02 shift scope
 * with the colleague exception, and the scope rules.
 */
@MySqlIntegrationTest
@Import(HomeSummaryIntegrationTests.TimeConfiguration.class)
class HomeSummaryIntegrationTests {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant AS_OF = seoul("2026-11-05T10:00");
    static final String PASSWORD = "home-test-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @LocalServerPort int port;

    long owner, manager, staff, other, first, second, customer, facilityType;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    final List<Client> clients = new ArrayList<>();
    String passwordHash;

    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock homeClock() { return new MutableClock(); }
    }

    @BeforeEach void fixture() {
        clock.fixed = AS_OF;
        passwordHash = passwords.encode(PASSWORD);
        owner = person("OWNER", "홈 사장");
        manager = person("EMPLOYEE", "홈 매니저");
        staff = person("EMPLOYEE", "홈 직원");
        other = person("EMPLOYEE", "다른 지점 직원");
        first = branch("홈 첫 지점");
        second = branch("홈 둘째 지점");
        member(manager, first, "MANAGER");
        member(staff, first, "STAFF");
        member(other, second, "STAFF");
        jdbc.update("INSERT INTO customers(member_no,name,registered_branch_id) VALUES (?, '홈 고객', ?)",
                "H-" + UUID.randomUUID().toString().substring(0, 12), first);
        customer = jdbc.queryForObject("SELECT MAX(id) FROM customers WHERE registered_branch_id=?", Long.class, first);
        jdbc.update("INSERT INTO facility_types(name,default_inspection_days) VALUES (?, 30)", "홈 유형 " + UUID.randomUUID());
        facilityType = jdbc.queryForObject("SELECT MAX(id) FROM facility_types", Long.class);
    }

    @AfterEach void clean() {
        clients.forEach(Client::close);
        clients.clear();
        clock.fixed = null;
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long id : branches) {
            jdbc.update("DELETE FROM seats WHERE branch_id=?", id);
            jdbc.update("DELETE FROM work_tasks WHERE branch_id=?", id);
            jdbc.update("DELETE FROM inquiries WHERE branch_id=?", id);
            jdbc.update("DELETE FROM facilities WHERE branch_id=?", id);
            jdbc.update("DELETE FROM shifts WHERE branch_id=?", id);
        }
        jdbc.update("DELETE FROM announcement_branches WHERE announcement_id IN (SELECT id FROM announcements WHERE author_employee_id IN ("
                + actors + "))");
        jdbc.update("DELETE FROM announcements WHERE author_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM facility_types WHERE id=?", facilityType);
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
        people.clear();
        branches.clear();
    }

    // ---- tasks (aggregation-api.md "오늘의 업무와 완료율") -------------------------------------------------------

    /** T1~T6 of the worked example, all assigned to the staff member: total 3, open 2, overdue 1, completed 1, 33.3%. */
    void workedTasks(long assignee) {
        task(first, assignee, "T1", "TODO", "2026-11-04T18:00", null);
        task(first, assignee, "T2", "IN_PROGRESS", "2026-11-05T20:00", null);
        task(first, assignee, "T3", "TODO", "2026-11-06T09:00", null);
        task(first, assignee, "T4", "TODO", null, null);
        task(first, assignee, "T5", "DONE", "2026-11-07T00:00", "2026-11-05T09:00");
        task(first, assignee, "T6", "DONE", "2026-11-04T00:00", "2026-11-04T17:00");
    }

    @Test void todaysTasksFollowTheWorkedExampleAndMatchTheSourceLists() throws Exception {
        workedTasks(staff);
        var browser = client(staff);
        var home = ok(browser.get("/api/home/summary?scope=" + first));
        var today = home.at("/tasks/today");
        assertThat(today.get("total").asLong()).isEqualTo(3);
        assertThat(today.get("open").asLong()).isEqualTo(2);
        assertThat(today.get("overdue").asLong()).isEqualTo(1);
        assertThat(today.get("completedToday").asLong()).isEqualTo(1);
        assertThat(today.at("/completionRate/state").asString()).isEqualTo("value");
        assertThat(today.at("/completionRate/percent").decimalValue()).isEqualByComparingTo("33.3");
        assertThat(home.at("/tasks/items").findValuesAsString("title")).containsExactly("T1", "T2");
        assertThat(home.at("/tasks/items/0/overdue").asBoolean()).isTrue();
        assertThat(home.at("/tasks/items/0/link/screen").asString()).isEqualTo("task-detail");
        // A staff member sees their own tasks by default (M9-D08).
        assertThat(home.at("/meta/taskFilter/mine").asBoolean()).isTrue();
        assertThat(home.at("/meta/taskFilter/defaulted").asBoolean()).isTrue();
        assertThat(home.at("/meta/date").asString()).isEqualTo("2026-11-05");
        assertThat(home.at("/meta/dayStart").asString()).isEqualTo("2026-11-04T15:00:00Z");
        assertThat(home.at("/meta/dayEnd").asString()).isEqualTo("2026-11-05T15:00:00Z");

        // The sources reproduce the counts.
        String due = enc("2026-11-05T15:00:00Z");
        long todo = ok(browser.get("/api/tasks?scope=" + first + "&mine=true&dueTo=" + due + "&status=" + enc("할 일"))).at("/meta/total").asLong();
        long doing = ok(browser.get("/api/tasks?scope=" + first + "&mine=true&dueTo=" + due + "&status=" + enc("진행 중"))).at("/meta/total").asLong();
        assertThat(todo + doing).isEqualTo(today.get("open").asLong());
        var summary = ok(browser.get("/api/tasks/summary?scope=" + first + "&mine=true&dueTo=" + due));
        assertThat(summary.at("/totals/overdue").asLong()).isEqualTo(today.get("overdue").asLong());
        long completedToday = 0;
        for (var task : ok(browser.get("/api/tasks?scope=" + first + "&mine=true&pageSize=100&status=" + enc("완료"))).get("items")) {
            Instant at = Instant.parse(task.get("completedAt").asString());
            if (!at.isBefore(seoul("2026-11-05T00:00")) && at.isBefore(seoul("2026-11-06T00:00"))) completedToday++;
        }
        assertThat(completedToday).isEqualTo(today.get("completedToday").asLong());
    }

    @Test void theOwnerSeesTheScopeByDefaultAndTheConditionCanBeChanged() throws Exception {
        workedTasks(staff);
        task(first, manager, "매니저 업무", "TODO", "2026-11-05T12:00", null);
        var o = client(owner);
        var all = ok(o.get("/api/home/summary?scope=" + first));
        assertThat(all.at("/meta/taskFilter/mine").asBoolean()).isFalse();
        assertThat(all.at("/tasks/today/open").asLong()).isEqualTo(3);
        // The owner has no task of their own.
        assertThat(all.at("/tasks/mine/total").asLong()).isZero();
        assertThat(all.at("/tasks/mine/completionRate/state").asString()).isEqualTo("notApplicable");
        assertThat(all.at("/tasks/mine/completionRate/percent").isNull()).isTrue();
        var managers = ok(o.get("/api/home/summary?scope=" + first + "&assigneeId=" + manager));
        assertThat(managers.at("/tasks/today/open").asLong()).isEqualTo(1);
        assertThat(managers.at("/meta/taskFilter/assigneeId").asString()).isEqualTo(Long.toString(manager));
        assertThat(managers.at("/meta/taskFilter/defaulted").asBoolean()).isFalse();
        assertThat(o.get("/api/home/summary?mine=true&assigneeId=" + manager).statusCode()).isEqualTo(422);
        assertThat(o.get("/api/home/summary?mine=yes").statusCode()).isEqualTo(422);
        // A manager sees the whole branch with mine=false.
        var branchWide = ok(client(manager).get("/api/home/summary?scope=" + first + "&mine=false"));
        assertThat(branchWide.at("/tasks/today/open").asLong()).isEqualTo(3);
        assertThat(branchWide.at("/tasks/mine/open").asLong()).isEqualTo(1);
    }

    // ---- shifts (M8-D02 and the colleague exception) -------------------------------------------------------------

    @Test void shiftsFollowM8D02AndColleaguesShowNamesAndTimesOnly() throws Exception {
        // The worked example for the staff member: three of five overlap today.
        shift(staff, first, "2026-11-04T22:00", "2026-11-05T06:00", "SCHEDULED");
        shift(staff, first, "2026-11-05T09:00", "2026-11-05T18:00", "SCHEDULED");
        long cancelled = shift(staff, first, "2026-11-05T09:00", "2026-11-05T18:00", "CANCELLED");
        shift(staff, first, "2026-11-05T22:00", "2026-11-06T06:00", "SCHEDULED");
        shift(staff, first, "2026-11-04T14:00", "2026-11-04T22:00", "SCHEDULED");
        long colleague = shift(manager, first, "2026-11-05T08:00", "2026-11-05T17:00", "SCHEDULED");
        shift(other, second, "2026-11-05T08:00", "2026-11-05T17:00", "SCHEDULED");

        var s = client(staff);
        var home = ok(s.get("/api/home/summary?scope=all"));
        var card = home.get("shifts");
        assertThat(card.get("count").asLong()).isEqualTo(3);
        assertThat(card.get("items").findValuesAsString("startsAt")).containsExactly("2026-11-04T13:00:00Z", "2026-11-05T00:00:00Z",
                "2026-11-05T13:00:00Z");
        assertThat(card.get("items").toString()).doesNotContain("\"" + cancelled + "\"");
        // The source list with the overlap condition gives the same count.
        long fromSource = 0;
        for (var shift : ok(s.get("/api/shifts?scope=all&from=2026-11-04&to=2026-11-05&pageSize=100")).get("items")) {
            Instant start = Instant.parse(shift.get("startsAt").asString()), end = Instant.parse(shift.get("endsAt").asString());
            if (start.isBefore(seoul("2026-11-06T00:00")) && end.isAfter(seoul("2026-11-05T00:00"))) fromSource++;
        }
        assertThat(fromSource).isEqualTo(card.get("count").asLong());
        // The manager's shift is a colleague's: name and times only, never an ID or a link.
        assertThat(card.get("colleagueCount").asLong()).isEqualTo(1);
        var only = card.at("/colleagues/0");
        assertThat(only.get("employeeName").asString()).isEqualTo("홈 매니저");
        assertThat(only.get("branchId").asString()).isEqualTo(Long.toString(first));
        assertThat(names(only)).containsExactlyInAnyOrder("branchId", "employeeName", "startsAt", "endsAt");
        assertThat(card.get("colleagues").toString()).doesNotContain("\"" + colleague + "\"", "\"" + manager + "\"");
        // Another branch's shift is no colleague's, and the staff member still cannot read the manager's shift itself.
        assertThat(card.get("colleagues").toString()).doesNotContain("다른 지점 직원");
        assertThat(s.get("/api/shifts/" + colleague).statusCode()).isIn(403, 404);

        // The owner reads every shift in items and has no membership, so no colleagues.
        var ownerCard = ok(client(owner).get("/api/home/summary?scope=all")).get("shifts");
        assertThat(ownerCard.get("count").asLong()).isEqualTo(5);
        assertThat(ownerCard.get("colleagueCount").asLong()).isZero();

        // A revoked membership ends the colleague view at the next request.
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", manager, first);
        assertThat(ok(s.get("/api/home/summary?scope=all")).at("/shifts/colleagueCount").asLong()).isZero();
    }

    // ---- inquiries, facilities, notices, seats --------------------------------------------------------------------

    @Test void theOtherCardsMatchTheirSourcesAndCarryNoCustomer() throws Exception {
        inquiry(first, "접수 문의", "RECEIVED", "2026-11-05T08:00");
        inquiry(first, "처리 문의", "IN_PROGRESS", "2026-11-05T09:00");
        inquiry(first, "답변 문의", "ANSWERED", "2026-11-05T09:30");
        inquiry(second, "다른 지점 문의", "RECEIVED", "2026-11-05T09:40");
        facility(first, "점검 지난 시설", "NORMAL", "2026-10-01");
        facility(first, "점검 남은 시설", "NORMAL", "2026-11-04");
        facility(first, "중지 시설", "OUT_OF_SERVICE", "2026-10-01");
        notice("게시 공지", "PUBLISHED", first);
        notice("초안 공지", "DRAFT", first);
        notice("다른 지점 공지", "PUBLISHED", second);
        seat(first, "A-1", "AVAILABLE");
        seat(first, "A-2", "BLOCKED");

        var s = client(staff);
        var home = ok(s.get("/api/home/summary?scope=" + first));
        var inquiries = home.get("inquiries");
        var inquirySummary = ok(s.get("/api/inquiries/summary?scope=" + first)).get("totals");
        assertThat(inquiries.get("unresolved").asLong()).isEqualTo(inquirySummary.get("unresolved").asLong()).isEqualTo(2);
        assertThat(inquiries.get("items").findValuesAsString("subject")).containsExactly("처리 문의", "접수 문의");
        assertThat(inquiries.toString()).doesNotContain("홈 고객", "customer");

        var facilities = home.get("facilities");
        assertThat(facilities.get("inspectionDue").asLong())
                .isEqualTo(ok(s.get("/api/facilities/summary?scope=" + first)).at("/totals/inspectionDue").asLong()).isEqualTo(1);
        assertThat(facilities.get("items").findValuesAsString("name")).containsExactly("점검 지난 시설");
        assertThat(facilities.at("/link/query/inspectionDue").asString()).isEqualTo("true");

        var notices = home.at("/notices/items").findValuesAsString("title");
        assertThat(notices).contains("게시 공지").doesNotContain("초안 공지", "다른 지점 공지");

        var seats = home.get("seats");
        var occupancy = ok(s.get("/api/seats/occupancy?scope=" + first)).at("/items/0");
        assertThat(seats.get("total").asLong()).isEqualTo(occupancy.get("total").asLong()).isEqualTo(2);
        assertThat(seats.get("blocked").asLong()).isEqualTo(occupancy.get("blocked").asLong()).isEqualTo(1);
        assertThat(seats.at("/occupancy/state").asString()).isEqualTo("value");
        assertThat(seats.at("/occupancy/percent").decimalValue()).isEqualByComparingTo("0.0");

        // The message card left with team chat (ADR-M10-002 M10-D11).
        assertThat(names(home)).containsExactlyInAnyOrder("meta", "seats", "tasks", "inquiries", "facilities", "shifts", "notices",
                "closures"); // M13 #112
    }

    @Test void theScopeIsTheRequestersOwn() throws Exception {
        inquiry(first, "첫 지점 문의", "RECEIVED", "2026-11-05T08:00");
        var o = client(other);
        assertThat(o.get("/api/home/summary?scope=" + first).statusCode()).isEqualTo(403);
        assertThat(o.get("/api/home/summary?scope=bad").statusCode()).isEqualTo(422);
        var own = ok(o.get("/api/home/summary?scope=all"));
        assertThat(own.at("/meta/branchIds").toString()).isEqualTo("[\"" + second + "\"]");
        assertThat(own.at("/meta/allBranches").asBoolean()).isTrue();
        assertThat(own.at("/inquiries/unresolved").asLong()).isZero();
        // A membership revoked elsewhere refuses the next request (ADR-COM-001 COM-D05).
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", other, second);
        assertThat(o.get("/api/home/summary?scope=" + second).statusCode()).isEqualTo(403);
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(anonymous.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/home/summary")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    static Instant seoul(String local) { return LocalDateTime.parse(local).atZone(SEOUL).toInstant(); }

    /** A UTC wall-clock string, so the driver binds it as is whatever the JVM time zone is. */
    static String utc(Instant at) { return at.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' '); }

    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@home.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,?,'별명',?,'ACTIVE')",
                email, passwordHash, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address,status) VALUES (?,?,'서울','ACTIVE')", code, name);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        branches.add(id);
        return id;
    }

    void member(long employee, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,'ACTIVE')", employee, branch, role);
    }

    void task(long branch, long assignee, String title, String status, String due, String completed) {
        jdbc.update("INSERT INTO work_tasks(branch_id,title,created_by,assignee_employee_id,category,status,due_at,completed_at) "
                + "VALUES (?,?,?,?,'일반',?,?,?)", branch, title, owner, assignee, status,
                due == null ? null : utc(seoul(due)), completed == null ? null : utc(seoul(completed)));
    }

    long shift(long employee, long branch, String start, String end, String status) {
        jdbc.update("INSERT INTO shifts(employee_id,branch_id,starts_at,ends_at,status,created_by) VALUES (?,?,?,?,?,?)",
                employee, branch, utc(seoul(start)), utc(seoul(end)), status, owner);
        return jdbc.queryForObject("SELECT MAX(id) FROM shifts WHERE employee_id=?", Long.class, employee);
    }

    void inquiry(long branch, String subject, String status, String created) {
        jdbc.update("INSERT INTO inquiries(branch_id,customer_id,subject,body,source,status,created_by,created_at) "
                + "VALUES (?,?,?,'본문','PHONE',?,?,?)", branch, customer, subject, status, owner, utc(seoul(created)));
    }

    void facility(long branch, String name, String status, String lastInspected) {
        jdbc.update("INSERT INTO facilities(branch_id,type_id,name,status,inspection_interval_days,last_inspected_on) "
                + "VALUES (?,?,?,?,30,?)", branch, facilityType, name, status, java.sql.Date.valueOf(lastInspected));
    }

    void notice(String title, String status, long branch) {
        jdbc.update("INSERT INTO announcements(author_employee_id,title,body,target,status,published_at) "
                + "VALUES (?,?,'본문','SELECTED_BRANCHES',?,?)", owner, title, status,
                "PUBLISHED".equals(status) ? utc(seoul("2026-11-05T08:00")) : null);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM announcements WHERE author_employee_id=?", Long.class, owner);
        jdbc.update("INSERT INTO announcement_branches(announcement_id,branch_id) VALUES (?,?)", id, branch);
    }

    void seat(long branch, String number, String status) {
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,status) VALUES (?, 'A', ?, ?)", branch, number, status);
    }

    JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    Client client(long actor) throws Exception {
        var client = new Client(actor);
        clients.add(client);
        return client;
    }

    final class Client implements AutoCloseable {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;

        Client(long actor) throws Exception {
            csrf = json.readTree(send("GET", "/api/auth/csrf", null).body()).get("token").asString();
            String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, actor);
            var login = send("POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD));
            assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
            csrf = json.readTree(send("GET", "/api/auth/csrf", null).body()).get("token").asString();
        }

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }

        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf != null) request.header("X-CSRF-Token", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close() { http.close(); }
    }
}
