package com.openstudy.modules.task;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #75: HTTP and MySQL behaviour of the task contract (collaboration-api.md, ADR-M7-001 M7-D01). */
@MySqlIntegrationTest
class TaskIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    long owner, manager, creator, assignee, bystander, outsider, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "관리자"); creator = person("EMPLOYEE", "등록자"); assignee = person("EMPLOYEE", "담당자");
        bystander = person("EMPLOYEE", "동료"); outsider = person("EMPLOYEE", "타지점");
        first = branch("업무 첫 지점"); second = branch("업무 둘째 지점");
        member(manager, first, "MANAGER"); member(creator, first, "STAFF"); member(assignee, first, "STAFF");
        member(bystander, first, "STAFF"); member(outsider, second, "STAFF");
    }

    @AfterEach void clean() {
        for (long id : branches) {
            jdbc.update("DELETE e FROM task_events e JOIN work_tasks t ON t.id=e.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE i FROM task_checklist_items i JOIN work_tasks t ON t.id=i.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE FROM work_tasks WHERE branch_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@task.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("task-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "task-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "task-test-password"), null).statusCode()).isEqualTo(200);
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
        return http(session.client(), "POST", "/api/tasks", session.token(), body, key);
    }

    Map<String, Object> task(long branch, long assigned, String title) {
        var body = new HashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("title", title); body.put("assigneeId", Long.toString(assigned));
        body.put("category", "운영");
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
    long total(Session session, String query) throws Exception { return node(get(session, "/api/tasks?" + query)).at("/meta/total").asLong(); }

    @Test void anyBranchMemberRegistersATaskWithAChecklistAndTheServerRecordsTheCreator() throws Exception {
        try (var s = login(bystander)) {
            var body = task(first, assignee, "  냉난방 점검  ");
            body.put("body", "2층 확인"); body.put("priority", "긴급");
            body.put("dueAt", "2030-01-02T03:04:05.123456789Z");
            body.put("checklist", List.of("필터 확인", "온도 기록"));
            var response = post(s, body, key());
            assertThat(response.statusCode()).isEqualTo(201);
            var created = node(response);
            assertThat(response.headers().firstValue("Location")).contains("/api/tasks/" + created.get("id").asString());
            assertThat(created.get("title").asString()).isEqualTo("냉난방 점검");
            assertThat(created.get("status").asString()).isEqualTo("할 일");
            assertThat(created.get("priority").asString()).isEqualTo("긴급");
            assertThat(created.at("/createdBy/id").asString()).isEqualTo(Long.toString(bystander));
            assertThat(created.at("/assignee/name").asString()).isEqualTo("담당자");
            assertThat(created.get("dueAt").asString()).isEqualTo("2030-01-02T03:04:05.123456Z");
            assertThat(created.get("version").asLong()).isEqualTo(1);
            assertThat(created.get("canEdit").asBoolean()).isTrue();
            assertThat(created.get("checklistTotal").asLong()).isEqualTo(2);
            assertThat(created.at("/checklist/1/body").asString()).isEqualTo("온도 기록");
            assertThat(created.at("/checklist/1/sortOrder").asInt()).isEqualTo(1);
            long id = created.get("id").asLong();
            assertThat(jdbc.queryForObject("SELECT status FROM work_tasks WHERE id=?", String.class, id)).isEqualTo("TODO");
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='tasks' AND entity_id=?", first, id)).isEqualTo(1);

            var stored = get(s, "/api/tasks/" + id);
            assertThat(stored.statusCode()).isEqualTo(200);
            assertThat(node(stored).get("body").asString()).isEqualTo("2층 확인");
        }
    }

    @Test void createRejectsClientActorFieldsAndRequiresARequestKey() throws Exception {
        try (var s = login(creator)) {
            var forged = task(first, assignee, "위조");
            forged.put("createdBy", Long.toString(owner));
            assertThat(post(s, forged, key()).statusCode()).isEqualTo(422);
            assertThat(post(s, task(first, assignee, "키 없음"), null).statusCode()).isEqualTo(422);
            assertThat(post(s, task(second, outsider, "다른 지점"), key()).statusCode()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM work_tasks WHERE branch_id IN (?,?)", first, second)).isZero();
        }
    }

    @Test void onlyAnActiveMemberOfTheTaskBranchCanBeTheAssignee() throws Exception {
        long revoked = person("EMPLOYEE", "회수"), disabled = person("EMPLOYEE", "비활성");
        member(revoked, first, "STAFF"); member(disabled, first, "STAFF");
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", revoked);
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", disabled);
        try (var s = login(creator)) {
            for (long invalid : List.of(outsider, revoked, disabled, owner, 999_999_999L)) {
                var refused = post(s, task(first, invalid, "잘못된 담당자"), key());
                assertThat(refused.statusCode()).as("%d", invalid).isEqualTo(422);
                assertThat(code(refused)).isEqualTo("INVALID_ASSIGNEE");
            }
            var task = made(s, task(first, assignee, "정상"));
            var changed = send(s, "PATCH", "/api/tasks/" + task.get("id").asString(), versioned(task, "assigneeId", Long.toString(outsider)));
            assertThat(code(changed)).isEqualTo("INVALID_ASSIGNEE");
            assertThat(node(get(s, "/api/tasks/" + task.get("id").asString())).at("/assignee/id").asString()).isEqualTo(Long.toString(assignee));

            var candidates = node(get(s, "/api/assignee-candidates?branchId=" + first)).get("items");
            assertThat(candidates.findValuesAsString("id")).containsExactlyInAnyOrder(
                    Long.toString(manager), Long.toString(creator), Long.toString(assignee), Long.toString(bystander));
            assertThat(get(s, "/api/assignee-candidates?branchId=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/assignee-candidates").statusCode()).isEqualTo(422);
        }
    }

    @Test void creatorAssigneeManagerAndOwnerMayEditAndOtherMembersGet403() throws Exception {
        JsonNode task;
        try (var s = login(creator)) { task = made(s, task(first, assignee, "수정 권한")); }
        String path = "/api/tasks/" + task.get("id").asString();
        long version = task.get("version").asLong();
        for (long editor : List.of(creator, assignee, manager, owner)) {
            try (var s = login(editor)) {
                var response = send(s, "PATCH", path, Map.of("version", version, "title", "수정 " + editor));
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                version = node(response).get("version").asLong();
            }
        }
        var snapshot = jdbc.queryForObject("SELECT title FROM work_tasks WHERE id=?", String.class, task.get("id").asLong());
        try (var s = login(bystander)) {
            var current = node(get(s, path));
            assertThat(current.get("canEdit").asBoolean()).isFalse();
            assertThat(send(s, "PATCH", path, versioned(current, "title", "거부")).statusCode()).isEqualTo(403);
            assertThat(send(s, "POST", path + "/status", versioned(current, "status", "완료")).statusCode()).isEqualTo(403);
            assertThat(send(s, "POST", path + "/checklist-items", versioned(current, "body", "거부")).statusCode()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT title FROM work_tasks WHERE id=?", String.class, task.get("id").asLong())).isEqualTo(snapshot);
    }

    @Test void aRevokedMembershipOutranksBeingCreatorOrAssignee() throws Exception {
        JsonNode task;
        try (var s = login(creator)) { task = made(s, task(first, assignee, "소속 회수")); }
        String path = "/api/tasks/" + task.get("id").asString();
        try (var s = login(assignee)) {
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", assignee, first);
            assertThat(send(s, "PATCH", path, versioned(task, "title", "거부")).statusCode()).isEqualTo(404);
            assertThat(get(s, path).statusCode()).isEqualTo(404);
        }
        // The stored assignee stays; only a new assignee is validated, so other edits keep working.
        try (var s = login(creator)) {
            var changed = send(s, "PATCH", path, versioned(task, "title", "제목만 수정"));
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(node(changed).at("/assignee/id").asString()).isEqualTo(Long.toString(assignee));
        }
    }

    @Test void statusFollowsTheAllowedMovesAndCompletionTimeFollowsDone() throws Exception {
        try (var s = login(creator)) {
            var task = made(s, task(first, assignee, "상태"));
            String path = "/api/tasks/" + task.get("id").asString() + "/status";
            var started = node(send(s, "POST", path, versioned(task, "status", "진행 중")));
            assertThat(started.get("status").asString()).isEqualTo("진행 중");
            var back = node(send(s, "POST", path, versioned(started, "status", "할 일")));
            var done = node(send(s, "POST", path, versioned(back, "status", "완료")));
            assertThat(done.get("status").asString()).isEqualTo("완료");
            assertThat(done.get("completedAt").isNull()).isFalse();

            var invalid = send(s, "POST", path, versioned(done, "status", "진행 중"));
            assertThat(invalid.statusCode()).isEqualTo(409);
            assertThat(code(invalid)).isEqualTo("INVALID_STATE");

            var same = send(s, "POST", path, versioned(done, "status", "완료"));
            assertThat(same.statusCode()).isEqualTo(200);
            assertThat(node(same).get("version").asLong()).isEqualTo(done.get("version").asLong());
            long history = count("SELECT COUNT(*) FROM task_events WHERE task_id=? AND event_type='STATUS_CHANGED'", task.get("id").asLong());
            assertThat(history).isEqualTo(3);

            var reopened = node(send(s, "POST", path, versioned(done, "status", "할 일")));
            assertThat(reopened.get("status").asString()).isEqualTo("할 일");
            assertThat(reopened.get("completedAt").isNull()).isTrue();

            var body = task(first, assignee, "완료로 등록");
            body.put("status", "완료");
            assertThat(made(s, body).get("completedAt").isNull()).isFalse();
        }
    }

    @Test void aStaleVersionIs409WithTheLatestVersionAndAReloadUrl() throws Exception {
        try (var s = login(creator)) {
            var task = made(s, task(first, assignee, "버전"));
            String path = "/api/tasks/" + task.get("id").asString();
            assertThat(send(s, "PATCH", path, versioned(task, "title", "먼저")).statusCode()).isEqualTo(200);
            for (var stale : List.of(send(s, "PATCH", path, versioned(task, "title", "나중")),
                    send(s, "POST", path + "/status", versioned(task, "status", "완료")),
                    send(s, "POST", path + "/checklist-items", versioned(task, "body", "항목")))) {
                assertThat(stale.statusCode()).isEqualTo(409);
                assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
                assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
                assertThat(node(stale).get("reloadUrl").asString()).isEqualTo(path);
            }
            assertThat(node(get(s, path)).get("title").asString()).isEqualTo("먼저");
        }
    }

    @Test void updateRejectsImmutableFieldsAndAnIdenticalRequestChangesNothing() throws Exception {
        try (var s = login(creator)) {
            var task = made(s, task(first, assignee, "수정"));
            String path = "/api/tasks/" + task.get("id").asString();
            for (var field : List.of("branchId", "status", "checklist")) {
                var refused = send(s, "PATCH", path, versioned(task, field, Long.toString(second)));
                assertThat(refused.statusCode()).as(field).isEqualTo(422);
                assertThat(code(refused)).isEqualTo("IMMUTABLE_FIELD");
            }
            var same = node(send(s, "PATCH", path, versioned(task, "title", "수정", "category", "운영")));
            assertThat(same.get("version").asLong()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM task_events WHERE task_id=?", task.get("id").asLong())).isEqualTo(1);
        }
    }

    @Test void everyChangeLeavesTimeActorAndBeforeAndAfterValuesInTheHistory() throws Exception {
        try (var s = login(creator)) {
            var body = task(first, assignee, "이력");
            body.put("dueAt", "2030-01-01T00:00:00Z");
            var task = made(s, body);
            String path = "/api/tasks/" + task.get("id").asString();
            var edited = node(send(s, "PATCH", path, versioned(task, "title", "이력 수정", "priority", "높음",
                    "assigneeId", Long.toString(bystander), "dueAt", "2030-02-01T00:00:00Z")));
            var status = node(send(s, "POST", path + "/status", versioned(edited, "status", "진행 중")));
            var added = send(s, "POST", path + "/checklist-items", versioned(status, "body", "항목"));
            assertThat(added.statusCode()).isEqualTo(201);
            var withItem = node(added);
            assertThat(withItem.get("version").asLong()).isEqualTo(status.get("version").asLong() + 1);
            String item = withItem.at("/checklist/0/id").asString();
            var checked = node(send(s, "PATCH", path + "/checklist-items/" + item, versioned(withItem, "done", true)));
            assertThat(checked.get("checklistDone").asLong()).isEqualTo(1);
            assertThat(checked.at("/checklist/0/updatedBy/id").asString()).isEqualTo(Long.toString(creator));
            var removed = send(s, "DELETE", path + "/checklist-items/" + item + "?version=" + checked.get("version").asLong(), null);
            assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);
            assertThat(node(removed).get("checklistTotal").asLong()).isZero();

            var history = node(get(s, path + "/events?pageSize=100"));
            assertThat(history.at("/meta/total").asInt()).isEqualTo(8);
            var types = new ArrayList<String>();
            history.get("items").forEach(row -> types.add(row.get("type").asString()));
            assertThat(types).containsExactly("CHECKLIST_REMOVED", "CHECKLIST_CHANGED", "CHECKLIST_ADDED", "STATUS_CHANGED",
                    "DUE_CHANGED", "ASSIGNEE_CHANGED", "CONTENT_CHANGED", "CREATED");
            var status_ = history.at("/items/3");
            assertThat(status_.at("/actor/name").asString()).isEqualTo("등록자");
            assertThat(status_.at("/before/status").asString()).isEqualTo("할 일");
            assertThat(status_.at("/after/status").asString()).isEqualTo("진행 중");
            assertThat(status_.get("at").isNull()).isFalse();
            var due = history.at("/items/4");
            assertThat(due.at("/before/dueAt").asString()).isEqualTo("2030-01-01T00:00:00Z");
            assertThat(due.at("/after/dueAt").asString()).isEqualTo("2030-02-01T00:00:00Z");
            assertThat(history.at("/items/5/after/assigneeId").asString()).isEqualTo(Long.toString(bystander));
            var content = history.at("/items/6");
            assertThat(content.at("/before/title").asString()).isEqualTo("이력");
            assertThat(content.at("/after/priority").asString()).isEqualTo("높음");
            assertThat(history.at("/items/0/before/body").asString()).isEqualTo("항목");
        }
    }

    @Test void checklistItemsBelongToTheirTaskAndDeleteNeedsAVersion() throws Exception {
        try (var s = login(creator)) {
            var one = made(s, task(first, assignee, "하나"));
            var body = task(first, assignee, "둘");
            body.put("checklist", List.of("항목"));
            var two = made(s, body);
            String foreign = two.at("/checklist/0/id").asString();
            String path = "/api/tasks/" + one.get("id").asString() + "/checklist-items/" + foreign;
            assertThat(send(s, "PATCH", path, versioned(one, "done", true)).statusCode()).isEqualTo(404);
            assertThat(send(s, "DELETE", path + "?version=1", null).statusCode()).isEqualTo(404);
            assertThat(send(s, "DELETE", "/api/tasks/" + two.get("id").asString() + "/checklist-items/" + foreign, null).statusCode()).isEqualTo(422);
            assertThat(send(s, "PATCH", "/api/tasks/" + two.get("id").asString() + "/checklist-items/" + foreign,
                    versioned(two, "done", "예")).statusCode()).isEqualTo(422);
        }
    }

    @Test void thereIsNoTaskDeleteOperation() throws Exception {
        try (var s = login(owner)) {
            var task = made(s, task(first, manager, "삭제 없음"));
            assertThat(send(s, "DELETE", "/api/tasks/" + task.get("id").asString(), null).statusCode()).isEqualTo(405);
        }
    }

    @Test void listsSearchesAndDetailsStayInsideTheAccessibleBranches() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        JsonNode inFirst, inSecond;
        try (var s = login(creator)) { inFirst = made(s, task(first, assignee, tag + " 첫 지점")); }
        try (var s = login(outsider)) { inSecond = made(s, task(second, outsider, tag + " 둘째 지점")); }
        try (var s = login(creator)) {
            var page = node(get(s, "/api/tasks?q=" + tag));
            assertThat(page.at("/meta/total").asInt()).isEqualTo(1);
            assertThat(page.at("/items/0/id").asString()).isEqualTo(inFirst.get("id").asString());
            assertThat(get(s, "/api/tasks/" + inSecond.get("id").asString()).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/tasks/" + inSecond.get("id").asString() + "/events").statusCode()).isEqualTo(404);
            assertThat(send(s, "PATCH", "/api/tasks/" + inSecond.get("id").asString(), versioned(inSecond, "title", "거부")).statusCode()).isEqualTo(404);
            assertThat(get(s, "/api/tasks?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/tasks/summary?scope=" + second).statusCode()).isEqualTo(403);
            assertThat(get(s, "/api/tasks/" + inFirst.get("id").asString() + "?scope=" + first).statusCode()).isEqualTo(200);
            var summary = node(get(s, "/api/tasks/summary"));
            assertThat(summary.get("branches").size()).isEqualTo(1);
            assertThat(summary.at("/branches/0/branchId").asString()).isEqualTo(Long.toString(first));
        }
        try (var s = login(owner)) {
            assertThat(node(get(s, "/api/tasks?q=" + tag)).at("/meta/total").asInt()).isEqualTo(2);
        }
    }

    @Test void filtersSortAndPageKeepDueLessTasksLast() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Instant soon = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        try (var s = login(creator)) {
            var late = task(first, assignee, tag + " 늦음"); late.put("dueAt", soon.plus(5, ChronoUnit.DAYS).toString()); late.put("priority", "낮음");
            var early = task(first, creator, tag + " 빠름"); early.put("dueAt", soon.toString()); early.put("priority", "긴급");
            var open = task(first, assignee, tag + " 기한없음");
            made(s, late); made(s, early); made(s, open);
            for (String direction : List.of("asc", "desc")) {
                var rows = node(get(s, "/api/tasks?q=" + tag + "&sort=dueAt&direction=" + direction)).get("items");
                assertThat(rows.get(2).get("dueAt").isNull()).isTrue();
            }
            var ascending = node(get(s, "/api/tasks?q=" + tag + "&sort=dueAt&direction=asc")).get("items");
            assertThat(ascending.get(0).get("title").asString()).endsWith("빠름");
            var priority = node(get(s, "/api/tasks?q=" + tag + "&sort=priority&direction=desc")).get("items");
            assertThat(priority.get(0).get("title").asString()).endsWith("빠름");
            assertThat(total(s, "q=" + tag + "&mine=true")).isEqualTo(1);
            assertThat(total(s, "q=" + tag + "&assigneeId=" + assignee)).isEqualTo(2);
            assertThat(total(s, "q=" + tag + "&dueFrom=" + soon + "&dueTo=" + soon.plus(1, ChronoUnit.DAYS))).isEqualTo(1);
            var paged = node(get(s, "/api/tasks?q=" + tag + "&page=2&pageSize=2"));
            assertThat(paged.get("items").size()).isEqualTo(1);
            assertThat(paged.at("/meta/total").asInt()).isEqualTo(3);
            assertThat(get(s, "/api/tasks?sort=password").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/tasks?status=없음").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/tasks?dueFrom=내일").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/tasks?mine=yes").statusCode()).isEqualTo(422);
            assertThat(get(s, "/api/tasks?q=%25&sort=dueAt").statusCode()).isEqualTo(200);
        }
    }

    @Test void summaryCountsEqualTheSameListTotals() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        try (var s = login(owner)) {
            var todo = task(first, manager, tag + " 할 일");
            todo.put("dueAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
            made(s, todo);
            var progress = task(first, manager, tag + " 진행"); progress.put("status", "진행 중"); made(s, progress);
            var done = task(first, manager, tag + " 완료");
            done.put("status", "완료"); done.put("dueAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
            made(s, done);
            made(s, task(second, outsider, tag + " 둘째"));
            for (String filter : List.of("q=" + tag, "q=" + tag + "&scope=" + first, "q=" + tag + "&assigneeId=" + manager, "q=" + tag + "&overdue=true")) {
                var summary = node(get(s, "/api/tasks/summary?" + filter));
                assertThat(summary.at("/totals/total").asLong()).as(filter).isEqualTo(total(s, filter));
                assertThat(summary.at("/totals/todo").asLong()).isEqualTo(total(s, filter + "&status=" + enc("할 일")));
                assertThat(summary.at("/totals/inProgress").asLong()).isEqualTo(total(s, filter + "&status=" + enc("진행 중")));
                assertThat(summary.at("/totals/done").asLong()).isEqualTo(total(s, filter + "&status=" + enc("완료")));
                assertThat(summary.at("/totals/overdue").asLong()).isEqualTo(total(s, filter.endsWith("overdue=true") ? filter : filter + "&overdue=true"));
            }
            var scoped = node(get(s, "/api/tasks/summary?q=" + tag));
            assertThat(scoped.at("/totals/total").asLong()).isEqualTo(4);
            assertThat(scoped.at("/totals/overdue").asLong()).isEqualTo(1);
            assertThat(node(get(s, "/api/tasks?q=" + tag + "&overdue=true")).at("/items/0/overdue").asBoolean()).isTrue();
            long inFirst = 0;
            for (var branch : scoped.get("branches")) if (branch.get("branchId").asLong() == first) inFirst = branch.get("total").asLong();
            assertThat(inFirst).isEqualTo(3);
        }
    }

    @Test void theSameKeyReplaysAndADifferentInputConflicts() throws Exception {
        try (var s = login(creator)) {
            String key = key();
            var body = task(first, assignee, "멱등");
            var once = post(s, body, key);
            var again = post(s, body, key);
            assertThat(again.statusCode()).isEqualTo(201);
            assertThat(node(again).get("id").asString()).isEqualTo(node(once).get("id").asString());
            assertThat(count("SELECT COUNT(*) FROM work_tasks WHERE branch_id=?", first)).isEqualTo(1);
            var conflict = post(s, task(first, assignee, "다른 입력"), key);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
    }

    @Test void aFailedChecklistRollsBackNothingPartial() throws Exception {
        try (var s = login(creator)) {
            var body = task(first, assignee, "검증");
            body.put("checklist", List.of("정상", " "));
            assertThat(post(s, body, key()).statusCode()).isEqualTo(422);
            var many = task(first, assignee, "개수");
            many.put("checklist", java.util.Collections.nCopies(101, "항목"));
            assertThat(post(s, many, key()).statusCode()).isEqualTo(422);
            assertThat(count("SELECT COUNT(*) FROM work_tasks WHERE branch_id=?", first)).isZero();
        }
    }

    static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
