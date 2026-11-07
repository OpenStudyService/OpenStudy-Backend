package com.openstudy.modules.notice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #80: HTTP and MySQL behaviour of the notice contract (collaboration-api.md, ADR-M7-001 M7-D04). Visibility is
 * checked through the list and the detail for every kind of reader, and the races run real concurrent requests.
 */
@MySqlIntegrationTest
class NoticeIntegrationTests {
    static final String PASSWORD = "notice-test-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    long owner, manager, managerWithoutGrant, writerA, writerB, writerAB, partial, staffA, staffB, dual;
    long a, b, c;
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "지점 관리자"); managerWithoutGrant = person("EMPLOYEE", "권한 없는 관리자");
        writerA = person("EMPLOYEE", "A 작성자"); writerB = person("EMPLOYEE", "B 작성자"); writerAB = person("EMPLOYEE", "AB 작성자");
        partial = person("EMPLOYEE", "일부 권한자"); staffA = person("EMPLOYEE", "A 직원"); staffB = person("EMPLOYEE", "B 직원");
        dual = person("EMPLOYEE", "겸직 직원");
        a = branch("공지 A 지점"); b = branch("공지 B 지점"); c = branch("공지 C 지점");
        member(manager, a, "MANAGER"); grant(manager, a);
        member(managerWithoutGrant, a, "MANAGER");
        member(writerA, a, "STAFF"); grant(writerA, a);
        member(writerB, b, "STAFF"); grant(writerB, b);
        member(writerAB, a, "STAFF"); member(writerAB, b, "STAFF"); grant(writerAB, a); grant(writerAB, b);
        member(partial, a, "STAFF"); member(partial, b, "STAFF"); grant(partial, a);
        member(staffA, a, "STAFF"); member(staffB, b, "STAFF");
        member(dual, a, "STAFF"); member(dual, b, "STAFF");
    }

    @AfterEach void clean() {
        jdbc.update("DELETE FROM announcements WHERE author_employee_id IN (" + marks(people) + ")", people.toArray());
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    static String marks(List<Long> values) { return String.join(",", java.util.Collections.nCopies(values.size(), "?")); }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@notice.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "notice-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    void grant(long person, long branch) {
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='notices:write' WHERE m.employee_id=? AND m.branch_id=?", person, branch);
    }

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
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return http(session.client(), "GET", path, null, null, null); }

    Map<String, Object> draft(String title, String target, List<Long> ids, String status, Boolean pinned) {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", token + " " + title); body.put("body", "공지 본문: " + title); body.put("target", target);
        if (ids != null) body.put("branchIds", ids.stream().map(String::valueOf).toList());
        if (status != null) body.put("status", status);
        if (pinned != null) body.put("pinned", pinned);
        return body;
    }

    HttpResponse<String> createRaw(Session session, Map<String, Object> body, String key) throws Exception {
        return http(session.client(), "POST", "/api/notices", session.token(), body, key);
    }

    JsonNode make(Session session, String title, String target, List<Long> ids, String status, Boolean pinned) throws Exception {
        var response = createRaw(session, draft(title, target, ids, status, pinned), key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    HttpResponse<String> patch(Session session, JsonNode current, Object... pairs) throws Exception {
        return http(session.client(), "PATCH", "/api/notices/" + id(current), session.token(), versioned(current, pairs), null);
    }

    HttpResponse<String> status(Session session, JsonNode current, String status) throws Exception {
        return http(session.client(), "POST", "/api/notices/" + id(current) + "/status", session.token(), versioned(current, "status", status), null);
    }

    Map<String, Object> versioned(JsonNode current, Object... pairs) {
        var body = new HashMap<String, Object>();
        body.put("version", current.get("version").asLong());
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    JsonNode reload(Session session, JsonNode current) throws Exception { return ok(get(session, "/api/notices/" + id(current))); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode archived(Session session, JsonNode current) throws Exception { return ok(status(session, current, "게시 종료")); }

    JsonNode listAll(Session session, String extra) throws Exception {
        var response = get(session, "/api/notices?q=" + token + "&pageSize=100" + (extra.isEmpty() ? "" : "&" + extra));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    /** Titles without the test token, in the order the API returned them. */
    List<String> titles(JsonNode page) {
        var result = new ArrayList<String>();
        for (var item : page.get("items")) result.add(item.get("title").asString().substring(token.length() + 1));
        return result;
    }

    List<String> titles(Session session) throws Exception { return titles(listAll(session, "")); }

    List<String> ids(JsonNode notice) {
        var result = new ArrayList<String>();
        for (var item : notice.get("branchIds")) result.add(item.asString());
        return result;
    }

    List<String> ids(long... branches) { return java.util.Arrays.stream(branches).sorted().mapToObj(String::valueOf).toList(); }

    static String key() { return UUID.randomUUID().toString(); }
    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }
    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    long id(JsonNode notice) { return notice.get("id").asLong(); }
    long notices() { return count("SELECT COUNT(*) FROM announcements WHERE title LIKE ?", token + "%"); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    // ---- who may register ------------------------------------------------------------------------------------

    @Test void aNoticeForEveryoneIsRegisteredByAnOwnerOnly() throws Exception {
        try (var o = login(owner); var m = login(manager); var w = login(writerAB); var s = login(staffA)) {
            var created = make(o, "전체", "전체", null, null, null);
            assertThat(created.get("status").asString()).isEqualTo("임시 저장");
            assertThat(created.get("branchIds")).isEmpty();
            assertThat(created.get("publishedAt").isNull()).isTrue();
            assertThat(created.get("canEdit").asBoolean()).isTrue();
            assertThat(created.get("pinned").asBoolean()).isFalse();
            assertThat(created.at("/author/id").asString()).isEqualTo(Long.toString(owner));
            long before = notices();
            for (var denied : List.of(m, w, s)) {
                var response = createRaw(denied, draft("전체 거부", "전체", null, "게시 중", null), key());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            }
            assertThat(notices()).isEqualTo(before);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='notices' AND entity_id=? AND action='CREATED'", id(created))).isEqualTo(1);
        }
    }

    @Test void aBranchNoticeNeedsNoticesWriteAtEveryTargetBranch() throws Exception {
        try (var o = login(owner); var wab = login(writerAB); var wa = login(writerA); var p = login(partial);
                var m = login(manager); var mn = login(managerWithoutGrant); var s = login(staffA)) {
            var both = make(wab, "두 지점", "선택 지점", List.of(a, b), "게시 중", null);
            assertThat(ids(both)).isEqualTo(ids(a, b));
            assertThat(both.get("target").asString()).isEqualTo("선택 지점");
            assertThat(both.get("publishedAt").isNull()).isFalse();
            make(wa, "A만", "선택 지점", List.of(a), null, null);
            make(m, "관리자 A", "선택 지점", List.of(a), null, null);
            make(o, "소유자 셋", "선택 지점", List.of(a, b, c), null, null);
            long before = notices();
            // Only some of the targets: the grant must be held at all of them.
            var attempts = List.of(Map.entry(p, List.of(a, b)), Map.entry(wa, List.of(a, b)), Map.entry(m, List.of(a, b)),
                    Map.entry(mn, List.of(a)), Map.entry(s, List.of(a)), Map.entry(wa, List.of(a, c)));
            for (var attempt : attempts) {
                var response = createRaw(attempt.getKey(), draft("거부", "선택 지점", attempt.getValue(), null, null), key());
                assertThat(response.statusCode()).as(attempt.getValue() + response.body()).isEqualTo(403);
            }
            assertThat(notices()).isEqualTo(before);
        }
    }

    @Test void anUnknownBranchIsRefusedLikeAnUnauthorizedOneForEveryone() throws Exception {
        try (var o = login(owner); var wa = login(writerA)) {
            long unknown = 987654321L;
            var forWriter = createRaw(wa, draft("없는 지점", "선택 지점", List.of(a, unknown), null, null), key());
            var forOwner = createRaw(o, draft("없는 지점", "선택 지점", List.of(unknown), null, null), key());
            var unauthorized = createRaw(wa, draft("권한 없는 지점", "선택 지점", List.of(b), null, null), key());
            for (var response : List.of(forWriter, forOwner, unauthorized)) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
                assertThat(code(response)).isEqualTo("FORBIDDEN");
                assertThat(response.body()).doesNotContain(Long.toString(unknown));
            }
            assertThat(notices()).isZero();
        }
    }

    @Test void aStoppedBranchStillTakesNotices() throws Exception {
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", a);
        try (var wa = login(writerA)) {
            var created = make(wa, "운영 중지", "선택 지점", List.of(a), "게시 중", null);
            ok(patch(wa, created, "title", token + " 운영 중지 수정"));
        }
    }

    @Test void requestsAreValidatedBeforeAnythingIsStored() throws Exception {
        try (var o = login(owner)) {
            var cases = new ArrayList<Map<String, Object>>();
            cases.add(draft("전체에 지점", "전체", List.of(a), null, null));
            cases.add(draft("지점 없음", "선택 지점", null, null, null));
            cases.add(draft("빈 지점", "선택 지점", List.of(), null, null));
            var duplicate = draft("중복", "선택 지점", List.of(a), null, null);
            duplicate.put("branchIds", List.of(Long.toString(a), Long.toString(a)));
            cases.add(duplicate);
            var malformed = draft("형식", "선택 지점", List.of(a), null, null);
            malformed.put("branchIds", List.of("abc"));
            cases.add(malformed);
            cases.add(draft("종료 등록", "전체", null, "게시 종료", null));
            cases.add(draft("모르는 상태", "전체", null, "보관", null));
            cases.add(draft("모르는 대상", "일부", null, null, null));
            var blank = draft("공백", "전체", null, null, null);
            blank.put("title", "   ");
            cases.add(blank);
            var longTitle = draft("긴 제목", "전체", null, null, null);
            longTitle.put("title", "가".repeat(201));
            cases.add(longTitle);
            var noBody = draft("본문 없음", "전체", null, null, null);
            noBody.remove("body");
            cases.add(noBody);
            var pinned = draft("고정 형식", "전체", null, null, null);
            pinned.put("pinned", "yes");
            cases.add(pinned);
            var extra = draft("추가 필드", "전체", null, null, null);
            extra.put("authorId", "1");
            cases.add(extra);
            var tooLong = draft("너무 긴 본문", "전체", null, null, null);
            tooLong.put("body", "가".repeat(20001));
            cases.add(tooLong);
            for (var body : cases) {
                var response = createRaw(o, body, key());
                assertThat(response.statusCode()).as(json.writeValueAsString(body).substring(0, Math.min(120, json.writeValueAsString(body).length())) + response.body()).isEqualTo(422);
                assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
            }
            assertThat(createRaw(o, draft("키 없음", "전체", null, null, null), null).statusCode()).isEqualTo(422);
            assertThat(createRaw(o, draft("짧은 키", "전체", null, null, null), "short").statusCode()).isEqualTo(422);
            assertThat(notices()).isZero();
        }
    }

    // ---- who may change --------------------------------------------------------------------------------------

    @Test void changingTheTargetNeedsPermissionAtTheOldAndTheNewTarget() throws Exception {
        try (var o = login(owner); var wa = login(writerA); var wab = login(writerAB); var wb = login(writerB)) {
            var mine = make(wa, "내 공지", "선택 지점", List.of(a), "게시 중", null);

            // New target outside the writer's permission: refused, nothing changes.
            var denied = patch(wa, mine, "branchIds", List.of(Long.toString(a), Long.toString(b)));
            assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
            assertThat(patch(wa, mine, "branchIds", List.of(Long.toString(b))).statusCode()).isEqualTo(403);
            assertThat(patch(wa, mine, "target", "전체").statusCode()).isEqualTo(403);
            assertThat(ids(reload(wa, mine))).isEqualTo(ids(a));
            assertThat(reload(wa, mine).get("version").asLong()).isEqualTo(mine.get("version").asLong());

            // Old target outside the writer's permission: the published notice is visible but cannot be changed.
            var grown = ok(patch(wab, mine, "branchIds", List.of(Long.toString(a), Long.toString(b))));
            assertThat(ids(grown)).isEqualTo(ids(a, b));
            var refused = patch(wa, grown, "title", token + " 몰래 수정");
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(reload(wab, grown).get("title").asString()).isEqualTo(token + " 내 공지");
            assertThat(reload(wa, grown).get("canEdit").asBoolean()).isFalse();
            assertThat(patch(wb, grown, "title", token + " B 수정").statusCode()).isEqualTo(403);

            // Moving to another branch needs both sides, and both are held here.
            var moved = ok(patch(wab, grown, "branchIds", List.of(Long.toString(b))));
            assertThat(ids(moved)).isEqualTo(ids(b));
            // writerA no longer reaches the notice, which now concerns only B.
            assertThat(patch(wa, moved, "title", token + "수정").statusCode()).isEqualTo(404);

            // An OWNER may switch between everyone and selected branches; a branch writer may not touch an everyone notice.
            var toAll = ok(patch(o, moved, "target", "전체"));
            assertThat(toAll.get("branchIds")).isEmpty();
            assertThat(patch(wab, toAll, "title", token + " 전체 수정").statusCode()).isEqualTo(403);
            var back = ok(patch(o, toAll, "target", "선택 지점", "branchIds", List.of(Long.toString(a), Long.toString(c))));
            assertThat(ids(back)).isEqualTo(ids(a, c));
            assertThat(patch(wab, back, "title", token + "수정").statusCode()).isEqualTo(403);
        }
    }

    @Test void aNoticeThatIsNotPublishedAnswersNotFoundToThoseWhoCannotManageIt() throws Exception {
        try (var o = login(owner); var wa = login(writerA); var wab = login(writerAB); var s = login(staffA)) {
            var draftForAll = make(o, "전체 임시", "전체", null, null, null);
            var draftForTwo = make(wab, "두 지점 임시", "선택 지점", List.of(a, b), null, null);
            for (var hidden : List.of(draftForAll, draftForTwo)) {
                assertThat(patch(s, hidden, "title", token + " x").statusCode()).isEqualTo(404);
                assertThat(status(s, hidden, "게시 중").statusCode()).isEqualTo(404);
            }
            // writerA holds the grant at A only: a draft for A and B is hidden from the writer, not forbidden.
            assertThat(patch(wa, draftForTwo, "title", token + " x").statusCode()).isEqualTo(404);
            assertThat(status(wa, draftForTwo, "게시 중").statusCode()).isEqualTo(404);
            assertThat(patch(wa, draftForAll, "title", token + " x").statusCode()).isEqualTo(404);
            assertThat(get(wa, "/api/notices/" + id(draftForTwo)).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/notices/" + id(draftForAll)).statusCode()).isEqualTo(404);
            // Published, they are visible but still not changeable by the same people.
            ok(status(wab, draftForTwo, "게시 중"));
            assertThat(patch(wa, draftForTwo, "title", token + " x").statusCode()).isEqualTo(403);
            assertThat(status(s, draftForTwo, "게시 종료").statusCode()).isEqualTo(403);
            ok(status(o, draftForAll, "게시 중"));
            assertThat(patch(wab, draftForAll, "title", token + " x").statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/notices/9999999999").statusCode()).isEqualTo(404);
        }
    }

    @Test void updatesKeepOmittedFieldsAndRejectWhatTheContractForbids() throws Exception {
        try (var o = login(owner); var wa = login(writerA)) {
            var created = make(wa, "수정 대상", "선택 지점", List.of(a), null, null);
            var edited = ok(patch(wa, created, "title", token + " 새 제목", "pinned", true));
            assertThat(edited.get("title").asString()).isEqualTo(token + " 새 제목");
            assertThat(edited.get("body").asString()).isEqualTo(created.get("body").asString());
            assertThat(edited.get("pinned").asBoolean()).isTrue();
            assertThat(edited.get("status").asString()).isEqualTo("임시 저장");
            assertThat(edited.get("version").asLong()).isEqualTo(created.get("version").asLong() + 1);
            assertThat(ids(edited)).isEqualTo(ids(a));

            // Only the target branches: the version still moves, because the row set is part of the notice.
            var moved = ok(patch(o, edited, "branchIds", List.of(Long.toString(a), Long.toString(b))));
            assertThat(moved.get("version").asLong()).isEqualTo(edited.get("version").asLong() + 1);
            assertThat(count("SELECT COUNT(*) FROM announcement_branches WHERE announcement_id=?", id(moved))).isEqualTo(2);
            var narrowed = ok(patch(o, moved, "branchIds", List.of(Long.toString(b))));
            assertThat(count("SELECT COUNT(*) FROM announcement_branches WHERE announcement_id=?", id(moved))).isEqualTo(1);
            assertThat(narrowed.get("version").asLong()).isEqualTo(moved.get("version").asLong() + 1);

            // An identical request changes nothing.
            var same = ok(patch(o, narrowed, "title", narrowed.get("title").asString()));
            assertThat(same.get("version").asLong()).isEqualTo(narrowed.get("version").asLong());

            var onlyVersion = http(o.client(), "PATCH", "/api/notices/" + id(created), o.token(), Map.of("version", narrowed.get("version").asLong()), null);
            assertThat(onlyVersion.statusCode()).isEqualTo(422);
            assertThat(code(onlyVersion)).isEqualTo("VALIDATION_FAILED");
            var statusField = patch(o, narrowed, "status", "게시 중");
            assertThat(statusField.statusCode()).isEqualTo(422);
            assertThat(code(statusField)).isEqualTo("IMMUTABLE_FIELD");
            for (var bad : List.<Object[]>of(new Object[] {"target", "전체", "branchIds", List.of(Long.toString(a))},
                    new Object[] {"branchIds", List.of()}, new Object[] {"branchIds", List.of(Long.toString(a), Long.toString(a))},
                    new Object[] {"title", " "}, new Object[] {"pinned", "yes"}, new Object[] {"authorId", "1"})) {
                var response = patch(o, narrowed, bad);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            }
            // A notice for everyone cannot be given branches without saying it is for selected branches.
            var forAll = make(o, "전체 대상", "전체", null, null, null);
            assertThat(patch(o, forAll, "branchIds", List.of(Long.toString(a))).statusCode()).isEqualTo(422);
            assertThat(patch(o, forAll, "target", "선택 지점").statusCode()).isEqualTo(422);
            assertThat(reload(o, narrowed).get("version").asLong()).isEqualTo(narrowed.get("version").asLong());
        }
    }

    @Test void aStaleVersionIsAConflictForEveryCommandAndSavesNothing() throws Exception {
        try (var wa = login(writerA); var o = login(owner); var s = login(staffA)) {
            var created = make(wa, "버전", "선택 지점", List.of(a), "게시 중", null);
            var current = ok(patch(wa, created, "title", token + " 변경됨"));
            for (var stale : List.of(patch(wa, created, "title", token + " 낡은 수정"), status(wa, created, "게시 종료"),
                    patch(o, created, "pinned", true))) {
                assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
                var error = node(stale);
                assertThat(error.get("code").asString()).isEqualTo("VERSION_CONFLICT");
                assertThat(error.get("latestVersion").asLong()).isEqualTo(current.get("version").asLong());
                assertThat(error.get("reloadUrl").asString()).isEqualTo("/api/notices/" + id(created));
            }
            var after = reload(wa, current);
            assertThat(after.get("title").asString()).isEqualTo(token + " 변경됨");
            assertThat(after.get("status").asString()).isEqualTo("게시 중");
            assertThat(after.get("pinned").asBoolean()).isFalse();
            // Permission is answered before the version: someone who may not change the notice learns nothing from it.
            assertThat(patch(s, created, "title", token + " x").statusCode()).isEqualTo(403);
            assertThat(status(s, created, "게시 종료").statusCode()).isEqualTo(403);
            var firstArchived = archived(wa, current);
            assertThat(status(wa, current, "게시 종료").statusCode()).isEqualTo(409);
            assertThat(firstArchived.get("status").asString()).isEqualTo("게시 종료");
        }
    }

    // ---- states ----------------------------------------------------------------------------------------------

    @Test void statesFollowTheAllowedTransitionsAndPublishingRecordsTheTime() throws Exception {
        try (var wa = login(writerA); var s = login(staffA)) {
            var created = make(wa, "상태", "선택 지점", List.of(a), null, null);
            assertThat(created.get("publishedAt").isNull()).isTrue();
            assertThat(titles(s)).isEmpty();

            var skipped = status(wa, created, "게시 종료");
            assertThat(skipped.statusCode()).as(skipped.body()).isEqualTo(409);
            assertThat(code(skipped)).isEqualTo("INVALID_STATE");
            // Same state: the current value, with no change.
            assertThat(ok(status(wa, created, "임시 저장")).get("version").asLong()).isEqualTo(created.get("version").asLong());
            assertThat(status(wa, created, "삭제").statusCode()).isEqualTo(422);
            assertThat(reload(wa, created).get("status").asString()).isEqualTo("임시 저장");

            var published = ok(status(wa, created, "게시 중"));
            Instant firstPublished = Instant.parse(published.get("publishedAt").asString());
            assertThat(published.get("status").asString()).isEqualTo("게시 중");
            assertThat(published.get("version").asLong()).isEqualTo(created.get("version").asLong() + 1);
            assertThat(titles(s)).containsExactly("상태");
            // A published notice never goes back to a draft.
            var backToDraft = status(wa, published, "임시 저장");
            assertThat(backToDraft.statusCode()).isEqualTo(409);
            assertThat(code(backToDraft)).isEqualTo("INVALID_STATE");
            // Same state: the current value.
            var again = ok(status(wa, published, "게시 중"));
            assertThat(again.get("version").asLong()).isEqualTo(published.get("version").asLong());
            assertThat(again.get("publishedAt").asString()).isEqualTo(published.get("publishedAt").asString());

            Thread.sleep(20);
            var closed = archived(wa, published);
            assertThat(closed.get("publishedAt").asString()).isEqualTo(published.get("publishedAt").asString());
            assertThat(titles(s)).isEmpty();
            assertThat(get(s, "/api/notices/" + id(closed)).statusCode()).isEqualTo(404);
            assertThat(titles(listAll(s, "status=" + enc("게시 종료")))).isEmpty();
            assertThat(titles(listAll(wa, "status=" + enc("게시 종료")))).containsExactly("상태");
            var toDraft = status(wa, closed, "임시 저장");
            assertThat(toDraft.statusCode()).isEqualTo(409);

            Thread.sleep(20);
            var republished = ok(status(wa, closed, "게시 중"));
            assertThat(Instant.parse(republished.get("publishedAt").asString())).isAfter(firstPublished);
            assertThat(titles(s)).containsExactly("상태");
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='notices' AND entity_id=? AND action='STATUS_CHANGED'", id(created))).isEqualTo(3);
        }
    }

    @Test void aNoticeRegisteredAsPublishedCarriesItsPublishTime() throws Exception {
        try (var o = login(owner); var s = login(staffA)) {
            var created = make(o, "바로 게시", "전체", null, "게시 중", true);
            assertThat(created.get("status").asString()).isEqualTo("게시 중");
            assertThat(created.get("publishedAt").isNull()).isFalse();
            assertThat(created.get("pinned").asBoolean()).isTrue();
            assertThat(titles(s)).containsExactly("바로 게시");
            var detail = ok(get(s, "/api/notices/" + id(created)));
            assertThat(detail.get("body").asString()).isEqualTo(created.get("body").asString());
            assertThat(detail.get("canEdit").asBoolean()).isFalse();
        }
    }

    // ---- who sees what ---------------------------------------------------------------------------------------

    /** Eight notices that cover every combination of target and state. */
    Map<String, JsonNode> seed() throws Exception {
        var made = new LinkedHashMap<String, JsonNode>();
        try (var o = login(owner)) {
            made.put("전체-게시", make(o, "전체-게시", "전체", null, "게시 중", null));
            made.put("A-게시", make(o, "A-게시", "선택 지점", List.of(a), "게시 중", null));
            made.put("B-게시", make(o, "B-게시", "선택 지점", List.of(b), "게시 중", null));
            made.put("A-임시", make(o, "A-임시", "선택 지점", List.of(a), null, null));
            made.put("A-종료", archived(o, make(o, "A-종료", "선택 지점", List.of(a), "게시 중", null)));
            made.put("ABC-게시", make(o, "ABC-게시", "선택 지점", List.of(a, b, c), "게시 중", null));
            made.put("전체-임시", make(o, "전체-임시", "전체", null, null, null));
            made.put("전체-종료", archived(o, make(o, "전체-종료", "전체", null, "게시 중", null)));
        }
        return made;
    }

    void assertSees(long person, Set<String> expected, Map<String, JsonNode> made, String scope) throws Exception {
        try (var session = login(person)) {
            String suffix = scope == null ? "" : "?scope=" + scope;
            var page = listAll(session, scope == null ? "" : "scope=" + scope);
            assertThat(new TreeSet<>(titles(page))).as("list of " + person).isEqualTo(new TreeSet<>(expected));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(expected.size());
            for (var entry : made.entrySet()) {
                var detail = get(session, "/api/notices/" + id(entry.getValue()) + suffix);
                assertThat(detail.statusCode()).as(entry.getKey() + " for " + person).isEqualTo(expected.contains(entry.getKey()) ? 200 : 404);
            }
        }
    }

    @Test void everyReaderSeesOnlyWhatItsRoleAndScopeAllow() throws Exception {
        var made = seed();
        var published = Set.of("전체-게시", "A-게시", "B-게시", "ABC-게시");
        assertSees(staffA, Set.of("전체-게시", "A-게시", "ABC-게시"), made, null);
        assertSees(staffB, Set.of("전체-게시", "B-게시", "ABC-게시"), made, null);
        assertSees(dual, published, made, null);
        assertSees(dual, Set.of("전체-게시", "A-게시", "ABC-게시"), made, Long.toString(a));
        assertSees(dual, Set.of("전체-게시", "B-게시", "ABC-게시"), made, Long.toString(b));
        // A manager role without the grant is an ordinary reader.
        assertSees(managerWithoutGrant, Set.of("전체-게시", "A-게시", "ABC-게시"), made, null);
        // With the grant at A, the notices that only reach A can be managed, whatever their state.
        var managedAtA = Set.of("전체-게시", "A-게시", "A-임시", "A-종료", "ABC-게시");
        assertSees(writerA, managedAtA, made, null);
        assertSees(manager, managedAtA, made, null);
        assertSees(partial, Set.of("전체-게시", "A-게시", "B-게시", "A-임시", "A-종료", "ABC-게시"), made, null);
        assertSees(writerB, Set.of("전체-게시", "B-게시", "ABC-게시"), made, null);
        assertSees(writerAB, Set.of("전체-게시", "A-게시", "B-게시", "A-임시", "A-종료", "ABC-게시"), made, null);
        assertSees(owner, made.keySet(), made, null);
        assertSees(owner, Set.of("전체-게시", "B-게시", "ABC-게시", "전체-임시", "전체-종료"), made, Long.toString(b));
        try (var s = login(staffA)) {
            // A scope the reader does not belong to is refused rather than empty.
            assertThat(get(s, "/api/notices?scope=" + b).statusCode()).isEqualTo(403);
            assertThat(titles(listAll(s, "status=" + enc("게시 종료")))).isEmpty();
            assertThat(titles(listAll(s, "status=" + enc("임시 저장")))).isEmpty();
            assertThat(titles(listAll(s, "status=" + enc("게시 중")))).hasSize(3);
            assertThat(get(s, "/api/notices?status=" + enc("보관")).statusCode()).isEqualTo(422);
        }
        try (var w = login(writerA)) {
            assertThat(titles(listAll(w, "status=" + enc("게시 종료")))).containsExactly("A-종료");
            assertThat(titles(listAll(w, "status=" + enc("임시 저장")))).containsExactly("A-임시");
        }
    }

    @Test void canEditFollowsWhatTheReaderMayManage() throws Exception {
        seed();
        var expected = Map.of(staffA, Set.<String>of(), writerA, Set.of("A-게시", "A-임시", "A-종료"),
                writerAB, Set.of("A-게시", "B-게시", "A-임시", "A-종료"), owner, Set.of("전체-게시", "A-게시", "B-게시", "A-임시", "A-종료",
                        "ABC-게시", "전체-임시", "전체-종료"));
        for (var entry : expected.entrySet()) {
            try (var session = login(entry.getKey())) {
                var editable = new TreeSet<String>();
                for (var item : listAll(session, "").get("items"))
                    if (item.get("canEdit").asBoolean()) editable.add(item.get("title").asString().substring(token.length() + 1));
                assertThat(editable).as("canEdit for " + entry.getKey()).isEqualTo(new TreeSet<>(entry.getValue()));
            }
        }
    }

    @Test void branchIdsNameOnlyTheBranchesTheRequesterCanSee() throws Exception {
        var made = seed();
        long wide = id(made.get("ABC-게시"));
        var forAll = made.get("전체-게시");
        var expected = new LinkedHashMap<Long, List<String>>();
        expected.put(staffA, ids(a)); expected.put(staffB, ids(b)); expected.put(dual, ids(a, b)); expected.put(writerAB, ids(a, b));
        expected.put(owner, ids(a, b, c));
        for (var entry : expected.entrySet()) {
            try (var session = login(entry.getKey())) {
                var detail = ok(get(session, "/api/notices/" + wide));
                assertThat(ids(detail)).as("detail for " + entry.getKey()).isEqualTo(entry.getValue());
                var listed = listAll(session, "").get("items");
                for (var item : listed) {
                    if (item.get("id").asLong() == wide) assertThat(ids(item)).isEqualTo(entry.getValue());
                    if (item.get("target").asString().equals("전체")) assertThat(item.get("branchIds")).isEmpty();
                }
                assertThat(ok(get(session, "/api/notices/" + id(forAll))).get("branchIds")).isEmpty();
            }
        }
        // Responses to a write are filtered the same way.
        try (var wab = login(writerAB)) {
            var created = make(wab, "쓰기 응답", "선택 지점", List.of(a, b), "게시 중", null);
            assertThat(ids(created)).isEqualTo(ids(a, b));
            try (var s = login(staffA)) { assertThat(ids(ok(get(s, "/api/notices/" + id(created))))).isEqualTo(ids(a)); }
        }
    }

    @Test void aRevokedMembershipStopsBeingAReaderAtOnce() throws Exception {
        var made = seed();
        try (var s = login(staffA)) {
            assertThat(titles(s)).hasSize(3);
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", staffA, a);
            assertThat(titles(s)).isEmpty();
            assertThat(get(s, "/api/notices/" + id(made.get("전체-게시"))).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/notices/" + id(made.get("A-게시"))).statusCode()).isEqualTo(404);
        }
        try (var w = login(writerA)) {
            assertThat(titles(w)).contains("A-임시");
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id=(SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=?)", writerA, a);
            assertThat(titles(w)).containsExactlyInAnyOrder("전체-게시", "A-게시", "ABC-게시");
            assertThat(patch(w, made.get("A-게시"), "title", token + " x").statusCode()).isEqualTo(403);
        }
    }

    // ---- order -----------------------------------------------------------------------------------------------

    @Test void statusAndBranchRestrictionComeBeforePinnedOrderAndTheTotalFollowsThem() throws Exception {
        JsonNode p1, p2, p3, p4, d1;
        try (var o = login(owner)) {
            p1 = make(o, "p1", "선택 지점", List.of(a), "게시 중", true); Thread.sleep(20);
            p3 = archived(o, make(o, "p3", "선택 지점", List.of(a), "게시 중", true)); Thread.sleep(20);
            p2 = make(o, "p2", "선택 지점", List.of(a), "게시 중", false); Thread.sleep(20);
            p4 = make(o, "p4", "선택 지점", List.of(a), "게시 중", true); Thread.sleep(20);
            d1 = make(o, "d1", "선택 지점", List.of(a), null, false);
            make(o, "other", "선택 지점", List.of(b), "게시 중", true);
        }
        try (var s = login(staffA); var w = login(writerA)) {
            // The archived pinned notice does not float above anything for an ordinary reader.
            assertThat(titles(s)).containsExactly("p4", "p1", "p2");
            assertThat(listAll(s, "").at("/meta/total").asLong()).isEqualTo(3);
            // A manager sees it among the pinned ones, and the draft by its creation time.
            assertThat(titles(w)).containsExactly("p4", "p3", "p1", "d1", "p2");
            assertThat(listAll(w, "").at("/meta/total").asLong()).isEqualTo(5);

            var seen = new ArrayList<String>();
            for (int page = 1; page <= 3; page++) {
                var result = node(get(w, "/api/notices?q=" + token + "&pageSize=2&page=" + page));
                assertThat(result.at("/meta/total").asLong()).isEqualTo(5);
                seen.addAll(titles(result));
            }
            assertThat(seen).containsExactly("p4", "p3", "p1", "d1", "p2");
            var narrow = get(w, "/api/notices?q=" + token + "&pageSize=2&page=2");
            assertThat(titles(node(narrow))).containsExactly("p1", "d1");

            // Closing and reopening gives the notice a new publish time, which moves it up among the pinned ones.
            Thread.sleep(20);
            var closed = archived(w, p1);
            assertThat(titles(s)).containsExactly("p4", "p2");
            Thread.sleep(20);
            ok(status(w, closed, "게시 중"));
            assertThat(titles(s)).containsExactly("p1", "p4", "p2");
            assertThat(titles(w)).containsExactly("p1", "p4", "p3", "d1", "p2");

            // Pinning is a change like any other.
            var pinned = ok(patch(w, p2, "pinned", true));
            assertThat(titles(s)).containsExactly("p1", "p4", "p2");
            assertThat(pinned.get("pinned").asBoolean()).isTrue();
        }
        assertThat(p3.get("status").asString()).isEqualTo("게시 종료");
        assertThat(p4.get("pinned").asBoolean()).isTrue();
        assertThat(d1.get("status").asString()).isEqualTo("임시 저장");
    }

    @Test void searchFiltersTheTitleAndKeepsTheTotalHonest() throws Exception {
        try (var o = login(owner); var s = login(staffA)) {
            make(o, "여름 안내", "전체", null, "게시 중", null);
            make(o, "겨울 안내", "전체", null, "게시 중", null);
            make(o, "100% 환불", "전체", null, "게시 중", null);
            make(o, "여름 임시", "전체", null, null, null);
            var summer = get(s, "/api/notices?q=" + enc(token + " 여름"));
            assertThat(titles(node(summer))).containsExactly("여름 안내");
            assertThat(node(summer).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(titles(node(get(o, "/api/notices?q=" + enc(token + " 여름"))))).containsExactly("여름 임시", "여름 안내");
            // % and _ are literal characters of the search text, not wildcards.
            assertThat(titles(node(get(s, "/api/notices?q=" + enc("%"))))).containsExactly("100% 환불");
            assertThat(titles(node(get(s, "/api/notices?q=" + enc("_"))))).isEmpty();
        }
    }

    // ---- requests --------------------------------------------------------------------------------------------

    @Test void aRepeatedRegistrationReplaysTheSavedResultAndAKeyIsNotReusedForOtherInput() throws Exception {
        try (var wab = login(writerAB)) {
            String key = key();
            var body = draft("멱등", "선택 지점", List.of(a, b), "게시 중", true);
            var first = createRaw(wab, body, key);
            var replay = createRaw(wab, body, key);
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(node(replay).get("id").asString()).isEqualTo(node(first).get("id").asString());
            assertThat(node(replay).get("publishedAt").asString()).isEqualTo(node(first).get("publishedAt").asString());
            assertThat(notices()).isEqualTo(1);
            for (var other : List.of(draft("멱등", "선택 지점", List.of(a), "게시 중", true), draft("멱등 다름", "선택 지점", List.of(a, b), "게시 중", true),
                    draft("멱등", "선택 지점", List.of(a, b), null, true), draft("멱등", "선택 지점", List.of(a, b), "게시 중", false))) {
                var conflict = createRaw(wab, other, key);
                assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
                assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
            }
            // The order of the branch IDs is not a different input.
            var reversed = draft("멱등", "선택 지점", List.of(b, a), "게시 중", true);
            assertThat(createRaw(wab, reversed, key).statusCode()).isEqualTo(201);
            assertThat(notices()).isEqualTo(1);
            // Someone else's key is a different request.
            try (var wa = login(writerA)) {
                assertThat(createRaw(wa, draft("멱등", "선택 지점", List.of(a), null, null), key).statusCode()).isEqualTo(201);
            }
        }
    }

    @Test void aReplayDoesNotHandOutTheResultToSomeoneWhoLostThePermission() throws Exception {
        try (var wa = login(writerA)) {
            String key = key();
            var body = draft("회수", "선택 지점", List.of(a), null, null);
            assertThat(createRaw(wa, body, key).statusCode()).isEqualTo(201);
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id=(SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=?)", writerA, a);
            assertThat(createRaw(wa, body, key).statusCode()).isEqualTo(403);
        }
    }

    // ---- races -----------------------------------------------------------------------------------------------

    <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var ready = new CountDownLatch(calls.size());
            var start = new CountDownLatch(1);
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

    @Test void concurrentUpdatesOnTheSameVersionLeaveExactlyOneWinner() throws Exception {
        JsonNode created;
        try (var wab = login(writerAB)) { created = make(wab, "동시 수정", "선택 지점", List.of(a), "게시 중", null); }
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 6; index++) sessions.add(login(index % 2 == 0 ? writerA : writerAB));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < sessions.size(); index++) {
                var session = sessions.get(index);
                String title = token + " 동시 수정 " + index;
                calls.add(() -> patch(session, created, "title", title));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsExactly(200, 409, 409, 409, 409, 409);
            for (var result : results) if (result.statusCode() == 409) assertThat(code(result)).isEqualTo("VERSION_CONFLICT");
            var winner = results.stream().filter(result -> result.statusCode() == 200).findFirst().orElseThrow();
            assertThat(node(winner).get("title").asString()).isEqualTo(jdbc.queryForObject("SELECT title FROM announcements WHERE id=?", String.class, id(created)));
            assertThat(count("SELECT version FROM announcements WHERE id=?", id(created))).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='notices' AND entity_id=? AND action='UPDATED'", id(created))).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }

    @Test void anUpdateRacingAClosureOnTheSameVersionAppliesExactlyOne() throws Exception {
        JsonNode created;
        try (var o = login(owner)) { created = make(o, "경합", "선택 지점", List.of(a), "게시 중", null); }
        try (var w = login(writerA); var wab = login(writerAB)) {
            var results = race(List.<Callable<HttpResponse<String>>>of(() -> patch(w, created, "title", token + " 경합 수정"),
                    () -> status(wab, created, "게시 종료")));
            assertThat(statuses(results)).containsExactly(200, 409);
            boolean edited = jdbc.queryForObject("SELECT title=? FROM announcements WHERE id=?", Boolean.class, token + " 경합 수정", id(created));
            boolean closed = jdbc.queryForObject("SELECT status='ARCHIVED' FROM announcements WHERE id=?", Boolean.class, id(created));
            assertThat(edited ^ closed).isTrue();
            assertThat(count("SELECT version FROM announcements WHERE id=?", id(created))).isEqualTo(1);

            // Whoever lost reloads and succeeds; the end state holds both changes.
            var current = reload(w, created);
            if (edited) ok(status(wab, current, "게시 종료")); else ok(patch(w, current, "title", token + " 경합 수정"));
            var end = reload(w, created);
            assertThat(end.get("status").asString()).isEqualTo("게시 종료");
            assertThat(end.get("title").asString()).isEqualTo(token + " 경합 수정");
            assertThat(end.get("version").asLong()).isEqualTo(created.get("version").asLong() + 2);
        }
    }

    @Test void aTargetChangeRacingAClosureNeverLeavesAHalfAppliedNotice() throws Exception {
        JsonNode created;
        try (var o = login(owner)) { created = make(o, "대상 경합", "선택 지점", List.of(a, b), "게시 중", null); }
        try (var w = login(writerAB); var o = login(owner)) {
            var results = race(List.<Callable<HttpResponse<String>>>of(() -> patch(w, created, "branchIds", List.of(Long.toString(b))),
                    () -> status(o, created, "게시 종료")));
            assertThat(statuses(results)).containsExactly(200, 409);
            long rows = count("SELECT COUNT(*) FROM announcement_branches WHERE announcement_id=?", id(created));
            boolean closed = jdbc.queryForObject("SELECT status='ARCHIVED' FROM announcements WHERE id=?", Boolean.class, id(created));
            assertThat(rows == 1 ^ closed).isTrue();
            assertThat(count("SELECT version FROM announcements WHERE id=?", id(created))).isEqualTo(1);
        }
    }

    @Test void concurrentRegistrationsWithTheSameKeyCreateOneNotice() throws Exception {
        String key = key();
        var body = draft("동시 등록", "선택 지점", List.of(a, b), "게시 중", null);
        var sessions = new ArrayList<Session>();
        try {
            for (int index = 0; index < 6; index++) sessions.add(login(writerAB));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (var session : sessions) calls.add(() -> createRaw(session, body, key));
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(201);
            assertThat(results.stream().map(result -> node(result).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(notices()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND idempotency_key=?", writerAB, key)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM announcement_branches ab JOIN announcements n ON n.id=ab.announcement_id WHERE n.title LIKE ?", token + "%")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='notices' AND action='CREATED' AND actor_employee_id=?", writerAB)).isEqualTo(1);
        } finally {
            sessions.forEach(Session::close);
        }
    }
}
