package com.openstudy.modules.workforce;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #84 over HTTP and the database: clocking in, breaks and clocking out on the requester's own attendance only
 * (M8-D08), the state table and its codes, worked minutes (M8-D07), the shift link, readers by recorded branch (M8-D02)
 * and a stopped branch (M8-D04). The server clock is pinned per step so every recorded instant is known exactly; each
 * step logs in again at its own time, since a session cannot outlive an hour without activity.
 */
class AttendanceIntegrationTests extends ShiftIntegrationSupport {
    static final Set<String> RECORD = Set.of("id", "version", "status", "branchId", "employee", "shiftId", "clockInAt", "clockOutAt",
            "workDate", "breaks", "workedMinutes");

    /** null follows the real clock. */
    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock attendanceClock() { return new MutableClock(); }
    }

    @Autowired MutableClock clock;
    final List<Session> sessions = new ArrayList<>();

    @BeforeEach void realClock() { clock.fixed = null; }

    @AfterEach void closeSessions() {
        sessions.forEach(Session::close);
        sessions.clear();
        clock.fixed = null;
    }

    /** A fresh session of this person with the server clock at this instant. */
    Session at(long person, Instant instant) throws Exception {
        clock.fixed = instant;
        var session = login(person);
        sessions.add(session);
        return session;
    }

    /** "2026-11-04 09:00", "2026-11-04 09:00:30" or "2026-11-04 09:00:30.789" in Seoul. */
    Session at(long person, String time) throws Exception { return at(person, seoul(time)); }

    static String id(long value) { return Long.toString(value); }

    static Map<String, Object> body(Object... pairs) {
        var body = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    java.net.http.HttpResponse<String> clockInBody(Session session, Object body) throws Exception {
        return http(session.client(), "POST", "/api/me/attendance/clock-in", session.token(), body, key());
    }

    /** A query value as the browser sends it, so Korean labels arrive intact. */
    static String encoded(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); }

    /** Clock in, take the breaks and clock out through the API, each step at its own Seoul time. Returns the clock-out answer. */
    JsonNode work(long person, long branch, String in, String out, String... breaks) throws Exception {
        var record = created(clockIn(at(person, in), branch));
        long version = record.get("version").asLong();
        for (int index = 0; index < breaks.length; index += 2) {
            version = ok(breakStart(at(person, breaks[index]), version)).get("version").asLong();
            version = ok(breakEnd(at(person, breaks[index + 1]), version)).get("version").asLong();
        }
        return ok(clockOut(at(person, out), version));
    }

    Map<String, Long> minutes(JsonNode items) {
        var result = new HashMap<String, Long>();
        for (var item : items) result.put(item.get("id").asString(), item.get("workedMinutes").isNull() ? null : item.get("workedMinutes").asLong());
        return result;
    }

    // ---- clock-in -------------------------------------------------------------------------------------------

    @Test void aClockInIsRecordedAtTheServerTimeWithoutItsFractionAndAtTheChosenBranch() throws Exception {
        var own = at(staff, seoul("2026-11-04 09:00:30").plusMillis(789));
        var response = clockIn(own, first);
        var made = created(response);
        assertThat(fields(made)).isEqualTo(RECORD);
        long id = made.get("id").asLong();
        assertThat(response.headers().firstValue("Location")).hasValue("/api/attendance-records/" + id);
        assertThat(made.get("clockInAt").asString()).isEqualTo("2026-11-04T00:00:30Z");
        assertThat(made.get("status").asString()).isEqualTo("근무 중");
        assertThat(made.get("branchId").asString()).isEqualTo(id(first));
        assertThat(made.at("/employee/id").asString()).isEqualTo(id(staff));
        assertThat(made.at("/employee/name").asString()).isEqualTo("첫 지점 직원");
        assertThat(made.get("shiftId").isNull()).isTrue();
        assertThat(made.get("clockOutAt").isNull()).isTrue();
        assertThat(made.get("workedMinutes").isNull()).as("null until clock-out").isTrue();
        assertThat(made.get("breaks").size()).isZero();
        assertThat(made.get("version").asLong()).isEqualTo(1);
        assertThat(made.get("workDate").asString()).isEqualTo("2026-11-04");
        assertThat(count("SELECT MICROSECOND(clock_in_at) FROM attendance_records WHERE id=?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE id=? AND employee_id=? AND branch_id=? AND status='WORKING' "
                + "AND clock_in_at=?", id, staff, first, sql("2026-11-04 09:00:30"))).isEqualTo(1);
        var current = ok(get(own, "/api/me/attendance/current"));
        assertThat(fields(current)).containsExactlyInAnyOrder("attendance", "asOf");
        assertThat(current.at("/attendance/id").asLong()).isEqualTo(id);
        assertThat(current.get("asOf").asString()).isEqualTo("2026-11-04T00:00:30.789Z");
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='attendance-records' AND entity_id=? AND branch_id=?",
                id, first)).isEqualTo(1);
    }

    @Test void aClockInTakesNoTimeNoEmployeeAndNeedsARequestKey() throws Exception {
        var own = at(colleague, "2026-11-04 09:00");
        for (var input : List.<Map<String, Object>>of(body("branchId", id(first), "clockInAt", at("2026-11-04 08:00")),
                body("branchId", id(first), "employeeId", id(staff)), body("branchId", id(first), "shiftId", "1"),
                body(), body("branchId", "abc"), body("branchId", 5)))
            refused(clockInBody(own, input), 422, "VALIDATION_FAILED");
        refused(clockIn(own, first, null), 422, "VALIDATION_FAILED");
        refused(clockIn(own, first, "short"), 422, "VALIDATION_FAILED");
        // A malformed body is answered before the branch: 422 even for a branch the requester does not belong to.
        refused(clockInBody(own, body("branchId", id(second), "employeeId", id(staff))), 422, "VALIDATION_FAILED");
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", colleague)).isZero();

        String repeat = key();
        var made = created(clockIn(own, first, repeat));
        assertThat(created(clockIn(own, first, repeat)).get("id").asString()).isEqualTo(made.get("id").asString());
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", colleague)).isEqualTo(1);

        var two = at(worker, "2026-11-04 09:00");
        String sameKey = key();
        long kept = created(clockIn(two, first, sameKey)).get("id").asLong();
        refused(clockIn(two, second, sameKey), 409, "IDEMPOTENCY_CONFLICT");
        // Another branch with a new key: an attendance in progress anywhere refuses the clock-in.
        refused(clockIn(two, second), 409, "ATTENDANCE_ALREADY_WORKING");
        ok(clockOut(two, 1));
        // A retry with the first key still answers the first result; the screen reloads current afterwards.
        assertThat(created(clockIn(two, first, sameKey)).get("id").asLong()).isEqualTo(kept);
        assertThat(current(two).isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", worker)).isEqualTo(1);
    }

    @Test void onlyAnActiveMemberOfTheChosenBranchClocksInAndAnOwnerNeedsAMembershipRow() throws Exception {
        var own = at(staff, "2026-11-04 09:00");
        refused(clockIn(own, second), 403, "FORBIDDEN");
        refused(clockIn(own, 999_999_999L), 403, "FORBIDDEN");
        var boss = at(owner, "2026-11-04 09:00");
        refused(clockIn(boss, first), 403, "FORBIDDEN");
        assertThat(working(owner)).isZero();
        member(owner, first, "MANAGER");
        assertThat(created(clockIn(boss, first)).get("branchId").asString()).isEqualTo(id(first));
        var peer = at(colleague, "2026-11-04 09:00");
        revoke(colleague, first);
        refused(clockIn(peer, first), 403, "FORBIDDEN");
        assertThat(working(staff) + working(colleague)).isZero();
        assertThat(working(owner)).isEqualTo(1);
    }

    @Test void aStoppedBranchRefusesANewClockInButLetsTheOpenAttendanceBreakCloseAndBeRead() throws Exception {
        long id = created(clockIn(at(staff, "2026-11-04 09:00"), first)).get("id").asLong();
        var two = at(worker, "2026-11-04 09:00");
        created(clockIn(two, second));
        stop(first);
        refused(clockIn(at(colleague, "2026-11-04 09:01"), first), 409, "BRANCH_NOT_ACTIVE");
        // Not a member is still 403, and a stopped branch comes before the attendance already in progress.
        refused(clockIn(at(outsider, "2026-11-04 09:01"), first), 403, "FORBIDDEN");
        refused(clockIn(two, first), 409, "BRANCH_NOT_ACTIVE");
        assertThat(ok(breakStart(at(staff, "2026-11-04 12:00"), 1)).get("status").asString()).isEqualTo("휴게 중");
        assertThat(ok(breakEnd(at(staff, "2026-11-04 13:00"), 2)).get("status").asString()).isEqualTo("근무 중");
        var own = at(staff, "2026-11-04 18:00");
        var left = ok(clockOut(own, 3));
        assertThat(left.get("status").asString()).isEqualTo("퇴근");
        assertThat(left.get("workedMinutes").asLong()).isEqualTo(480);
        assertThat(ok(get(own, "/api/attendance-records/" + id)).get("workedMinutes").asLong()).isEqualTo(480);
        assertThat(ids(ok(get(own, "/api/attendance-records?mine=true")).get("items"))).containsExactly(id(id));
        var lead = at(manager, "2026-11-04 18:01");
        assertThat(ok(get(lead, "/api/attendance-records/" + id)).get("status").asString()).isEqualTo("퇴근");
        assertThat(ids(ok(get(lead, "/api/attendance-records?scope=" + first)).get("items"))).containsExactly(id(id));
    }

    // ---- state table ----------------------------------------------------------------------------------------

    @Test void theCommandsFollowTheStateTableCheckingTheStateBeforeTheVersion() throws Exception {
        var own = at(staff, "2026-11-04 08:59");
        refused(breakStart(own, 1), 409, "ATTENDANCE_NOT_WORKING");
        refused(breakEnd(own, 1), 409, "ATTENDANCE_NOT_WORKING");
        refused(clockOut(own, 1), 409, "ATTENDANCE_NOT_WORKING");
        assertThat(current(own).isNull()).isTrue();

        own = at(staff, "2026-11-04 09:00");
        long id = created(clockIn(own, first)).get("id").asLong();
        refused(clockIn(own, first), 409, "ATTENDANCE_ALREADY_WORKING");
        refused(breakEnd(own, 1), 409, "ATTENDANCE_NOT_ON_BREAK");
        // State before version: a stale version on a wrong state is answered by the state.
        refused(breakEnd(own, 7), 409, "ATTENDANCE_NOT_ON_BREAK");

        own = at(staff, "2026-11-04 10:00");
        var paused = ok(breakStart(own, 1));
        assertThat(paused.get("status").asString()).isEqualTo("휴게 중");
        assertThat(paused.get("version").asLong()).isEqualTo(2);
        assertThat(paused.at("/breaks/0/startedAt").asString()).isEqualTo(at("2026-11-04 10:00"));
        assertThat(paused.at("/breaks/0/endedAt").isNull()).isTrue();
        assertThat(paused.get("workedMinutes").isNull()).isTrue();
        assertThat(fields(paused.at("/breaks/0"))).containsExactlyInAnyOrder("id", "startedAt", "endedAt");
        refused(breakStart(own, 2), 409, "ATTENDANCE_ON_BREAK");
        refused(clockIn(own, first), 409, "ATTENDANCE_ALREADY_WORKING");
        refused(clockOut(own, 2), 409, "ATTENDANCE_ON_BREAK");
        refused(clockOut(own, 1), 409, "ATTENDANCE_ON_BREAK");
        assertThat(current(own).get("status").asString()).isEqualTo("휴게 중");

        own = at(staff, "2026-11-04 10:30");
        // The right state with an old version: 409 VERSION_CONFLICT pointing at the current attendance.
        var stale = breakEnd(own, 1);
        refused(stale, 409, "VERSION_CONFLICT");
        assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
        assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/me/attendance/current");
        assertThat(ok(breakEnd(own, 2)).get("status").asString()).isEqualTo("근무 중");
        refused(breakStart(own, 2), 409, "VERSION_CONFLICT");
        refused(clockOut(own, 2), 409, "VERSION_CONFLICT");

        // A second break is allowed; each keeps its own start and end.
        own = at(staff, "2026-11-04 15:00");
        ok(breakStart(own, 3));
        own = at(staff, "2026-11-04 15:10");
        ok(breakEnd(own, 4));
        own = at(staff, "2026-11-04 18:00");
        var left = ok(clockOut(own, 5));
        assertThat(left.get("status").asString()).isEqualTo("퇴근");
        assertThat(left.get("version").asLong()).isEqualTo(6);
        assertThat(left.get("clockOutAt").asString()).isEqualTo(at("2026-11-04 18:00"));
        assertThat(left.get("breaks").size()).isEqualTo(2);
        assertThat(left.get("workedMinutes").asLong()).as("540 − 30 − 10").isEqualTo(500);

        // Clocking out twice: no attendance in progress any more, whatever the version.
        refused(clockOut(own, 6), 409, "ATTENDANCE_NOT_WORKING");
        refused(clockOut(own, 1), 409, "ATTENDANCE_NOT_WORKING");
        refused(breakStart(own, 6), 409, "ATTENDANCE_NOT_WORKING");
        assertThat(current(own).isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=?", id)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=? AND ended_at IS NULL", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE id=? AND status='LEFT' AND version=5", id)).isEqualTo(1);
        // After clocking out a new clock-in is possible.
        assertThat(created(clockIn(at(staff, "2026-11-04 19:00"), first)).get("id").asLong()).isNotEqualTo(id);
    }

    @Test void theCommandsTakeOnlyAVersion() throws Exception {
        var own = at(staff, "2026-11-04 09:00");
        created(clockIn(own, first));
        for (String command : List.of("break-start", "break-end", "clock-out")) {
            for (var input : List.<Map<String, Object>>of(body(), body("version", "1"), body("version", 0), body("version", 1.5),
                    body("version", 1, "employeeId", id(staff)), body("version", 1, "attendanceId", "1"), body("version", 1, "id", "1"),
                    body("version", 1, "at", at("2026-11-04 09:30")), body("version", 1, "startedAt", at("2026-11-04 09:30"))))
                refused(self(own, command, input), 422, "VALIDATION_FAILED");
        }
        assertThat(count("SELECT COUNT(*) FROM attendance_breaks b JOIN attendance_records a ON a.id=b.attendance_id WHERE a.employee_id=?", staff)).isZero();
        assertThat(current(own).get("version").asLong()).isEqualTo(1);
    }

    @Test void nobodyCanChangeSomebodyElsesAttendance() throws Exception {
        long id = created(clockIn(at(staff, "2026-11-04 09:00"), first)).get("id").asLong();
        for (long person : List.of(colleague, manager, owner)) {
            var other = at(person, "2026-11-04 10:00");
            // There is no way to name the attendance: the commands find only the requester's own, and they have none.
            refused(breakStart(other, 1), 409, "ATTENDANCE_NOT_WORKING");
            refused(breakEnd(other, 1), 409, "ATTENDANCE_NOT_WORKING");
            refused(clockOut(other, 1), 409, "ATTENDANCE_NOT_WORKING");
            refused(self(other, "clock-out", body("version", 1, "employeeId", id(staff))), 422, "VALIDATION_FAILED");
            refused(self(other, "break-start", body("version", 1, "attendanceId", id(id))), 422, "VALIDATION_FAILED");
            assertThat(http(other.client(), "POST", "/api/attendance-records/" + id + "/clock-out", other.token(), body("version", 1), null)
                    .statusCode()).isIn(404, 405);
            for (String method : List.of("PATCH", "PUT", "DELETE"))
                assertThat(http(other.client(), method, "/api/attendance-records/" + id, other.token(), body("version", 1), null).statusCode())
                        .as(method).isIn(404, 405);
        }
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE id=? AND status='WORKING' AND version=0", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id IN (?,?,?)", colleague, manager, owner)).isZero();
    }

    // ---- worked minutes (M8-D07) ----------------------------------------------------------------------------

    /**
     * Every boundary example of the contract, recorded through the commands at pinned server times, then read back from
     * the clock-out answer, the detail, two lists, the weekly schedule and the linked shift: one value everywhere.
     */
    @Test void workedMinutesFollowEveryContractExampleAndAgreeEverywhere() throws Exception {
        var lead = at(manager, "2026-11-01 08:00");
        String[][] examples = {
                {"2026-11-02 09:00", "2026-11-02 18:00", "2026-11-02 09:00", "2026-11-02 18:00:00", "480", "2026-11-02 12:00:00", "2026-11-02 13:00:00"},
                {"2026-11-03 09:00", "2026-11-03 19:00", "2026-11-03 09:00:00.999", "2026-11-03 18:00:59.999", "540"},
                {"2026-11-04 09:00", "2026-11-04 10:00", "2026-11-04 09:00:30", "2026-11-04 09:01:29", "0"},
                {"2026-11-05 09:00", "2026-11-05 10:00", "2026-11-05 09:00:00", "2026-11-05 09:00:00", "0"},
                {"2026-11-06 09:00", "2026-11-06 18:00", "2026-11-06 09:00:00", "2026-11-06 18:00:00", "498",
                        "2026-11-06 12:00:00", "2026-11-06 12:30:30", "2026-11-06 15:00:00", "2026-11-06 15:10:45"},
                {"2026-11-07 22:00", "2026-11-08 07:00", "2026-11-07 22:00:00", "2026-11-08 06:30:00", "480", "2026-11-08 02:00:00", "2026-11-08 02:30:00"},
                {"2026-11-09 09:00", "2026-11-09 10:00", "2026-11-09 09:00:00", "2026-11-09 09:10:00", "0", "2026-11-09 09:00:00", "2026-11-09 09:10:00"}};
        var shifts = new ArrayList<Long>();
        for (var example : examples) shifts.add(registered(lead, shift(first, staff, example[0], example[1])));
        var shiftOf = new HashMap<String, Long>();
        var expected = new LinkedHashMap<String, Long>();
        for (int index = 0; index < examples.length; index++) {
            var example = examples[index];
            long shift = shifts.get(index);
            String[] breaks = java.util.Arrays.copyOfRange(example, 5, example.length);
            var left = work(staff, first, example[2], example[3], breaks);
            String id = left.get("id").asString();
            assertThat(left.get("workedMinutes").asLong()).as("example from %s", example[2]).isEqualTo(Long.parseLong(example[4]));
            assertThat(left.get("shiftId").asString()).as("linked to the shift containing %s", example[2]).isEqualTo(Long.toString(shift));
            assertThat(left.get("breaks").size()).isEqualTo(breaks.length / 2);
            expected.put(id, Long.parseLong(example[4]));
            shiftOf.put(id, shift);
        }
        // Times are stored without their fraction of a second, so 18:00:59.999 is 18:00:59.
        String secondExample = expected.keySet().stream().skip(1).findFirst().orElseThrow();
        var own = at(staff, "2026-11-10 09:00");
        var detail = ok(get(own, "/api/attendance-records/" + secondExample));
        assertThat(detail.get("clockInAt").asString()).isEqualTo(at("2026-11-03 09:00"));
        assertThat(detail.get("clockOutAt").asString()).isEqualTo(seoul("2026-11-03 18:00:59").toString());
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=? AND (MICROSECOND(clock_in_at)<>0 OR MICROSECOND(clock_out_at)<>0)", staff)).isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_breaks b JOIN attendance_records a ON a.id=b.attendance_id WHERE a.employee_id=? "
                + "AND (MICROSECOND(b.started_at)<>0 OR MICROSECOND(b.ended_at)<>0)", staff)).isZero();
        var fifth = ok(get(own, "/api/attendance-records/" + expected.keySet().stream().skip(4).findFirst().orElseThrow()));
        assertThat(fifth.at("/breaks/0/startedAt").asString()).isEqualTo(at("2026-11-06 12:00"));
        assertThat(fifth.at("/breaks/0/endedAt").asString()).isEqualTo(seoul("2026-11-06 12:30:30").toString());
        assertThat(fifth.at("/breaks/1/endedAt").asString()).isEqualTo(seoul("2026-11-06 15:10:45").toString());

        for (var entry : expected.entrySet())
            assertThat(ok(get(own, "/api/attendance-records/" + entry.getKey())).get("workedMinutes").asLong()).as("detail %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        assertThat(minutes(ok(get(own, "/api/attendance-records?mine=true&pageSize=50")).get("items"))).isEqualTo(expected);
        lead = at(manager, "2026-11-10 09:00");
        assertThat(minutes(ok(get(lead, "/api/attendance-records?scope=" + first + "&employeeId=" + staff + "&pageSize=50")).get("items")))
                .isEqualTo(expected);
        var weekly = new HashMap<String, Long>();
        weekly.putAll(minutes(ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-02&employeeId=" + staff)).get("attendance")));
        weekly.putAll(minutes(ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-09&employeeId=" + staff)).get("attendance")));
        assertThat(weekly).isEqualTo(expected);
        for (var entry : expected.entrySet()) {
            var linked = ok(get(lead, "/api/shifts/" + shiftOf.get(entry.getKey()))).get("attendance");
            assertThat(linked.size()).isEqualTo(1);
            assertThat(linked.get(0).get("id").asString()).isEqualTo(entry.getKey());
            assertThat(linked.get(0).get("workedMinutes").asLong()).as("shift summary %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(linked.get(0).get("status").asString()).isEqualTo("퇴근");
        }
    }

    @Test void workedMinutesNeverGoBelowZeroAndStayNullWhileWorking() throws Exception {
        // Only a correction could leave a break wider than the attendance; the formula still stops at zero (FR-ATT-04).
        long odd = attendance(staff, first, null, "2026-11-10 09:00", "2026-11-10 09:10");
        pause(odd, "2026-11-10 08:00", "2026-11-10 09:30");
        long open = attendance(colleague, first, null, "2026-11-10 09:00", null);
        long paused = attendance(worker, first, null, "2026-11-10 09:00", null);
        pause(paused, "2026-11-10 09:30", null);
        var lead = at(manager, "2026-11-10 10:00");
        assertThat(ok(get(lead, "/api/attendance-records/" + odd)).get("workedMinutes").asLong()).isZero();
        var items = ok(get(lead, "/api/attendance-records?scope=" + first)).get("items");
        var byId = new HashMap<String, JsonNode>();
        for (var item : items) byId.put(item.get("id").asString(), item);
        assertThat(byId.get(id(odd)).get("workedMinutes").asLong()).isZero();
        assertThat(byId.get(id(open)).get("workedMinutes").isNull()).isTrue();
        assertThat(byId.get(id(open)).get("status").asString()).isEqualTo("근무 중");
        assertThat(byId.get(id(paused)).get("workedMinutes").isNull()).isTrue();
        assertThat(byId.get(id(paused)).get("status").asString()).isEqualTo("휴게 중");
    }

    // ---- shift link (M8-D08) --------------------------------------------------------------------------------

    @Test void aClockInLinksOnlyTheShiftOfTheSameBranchThatContainsIt() throws Exception {
        var lead = at(manager, "2026-11-09 07:00");
        long planned = registered(lead, shift(first, worker, "2026-11-10 09:00", "2026-11-10 18:00"));
        long elsewhere = registered(at(secondManager, "2026-11-09 07:00"), shift(second, worker, "2026-11-11 09:00", "2026-11-11 18:00"));
        long dropped = registered(lead, shift(first, worker, "2026-11-12 09:00", "2026-11-12 12:00"));
        ok(cancel(lead, dropped, 1));

        // A planned shift in progress clocks nobody in.
        var two = at(worker, "2026-11-10 10:00");
        assertThat(current(two).isNull()).isTrue();
        assertThat(ok(get(two, "/api/attendance-records?mine=true")).at("/meta/total").asLong()).isZero();
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", worker)).isZero();

        record Case(String in, long branch, Long shift, String why) {}
        for (var check : List.of(
                new Case("2026-11-10 08:55", first, null, "early clock-in before the start"),
                new Case("2026-11-10 09:00", first, planned, "exactly at the start"),
                new Case("2026-11-10 17:59:59", first, planned, "the last second inside"),
                new Case("2026-11-10 18:00", first, null, "at the end, which is outside"),
                new Case("2026-11-11 10:00", first, null, "the shift is at the other branch"),
                new Case("2026-11-11 10:30", second, elsewhere, "the same shift from its own branch"),
                new Case("2026-11-12 10:00", first, null, "a cancelled shift"))) {
            var session = at(worker, check.in());
            var made = created(clockIn(session, check.branch()));
            if (check.shift() == null) assertThat(made.get("shiftId").isNull()).as(check.why()).isTrue();
            else assertThat(made.get("shiftId").asString()).as(check.why()).isEqualTo(Long.toString(check.shift()));
            ok(clockOut(session, 1));
        }
        lead = at(manager, "2026-11-13 07:00");
        var linked = ok(get(lead, "/api/shifts/" + planned)).get("attendance");
        assertThat(linked.size()).isEqualTo(2);
        assertThat(linked.get(0).get("clockInAt").asString()).isEqualTo(at("2026-11-10 09:00"));
        assertThat(ids(ok(get(lead, "/api/attendance-records?shiftId=" + planned)).get("items"))).hasSize(2);
        // The link is a fact of the clock-in: changing the shift afterwards keeps it.
        ok(edit(lead, planned, change(1, "startsAt", at("2026-11-10 10:00"))));
        assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE shift_id=?", planned)).isEqualTo(2);
        assertThat(ok(get(lead, "/api/shifts/" + dropped)).get("attendance").size()).isZero();
    }

    // ---- readers (M8-D02) -----------------------------------------------------------------------------------

    @Test void readersSeeAttendanceByItsRecordedBranchAndChoosingABranchOpensNobodyElse() throws Exception {
        member(manager, second, "STAFF");
        long mine = attendance(staff, first, null, "2026-11-04 09:00", "2026-11-04 18:00");
        long peer = attendance(colleague, first, null, "2026-11-04 09:00", "2026-11-04 18:00");
        long workerFirst = attendance(worker, first, null, "2026-11-04 07:00", "2026-11-04 08:00");
        long workerSecond = attendance(worker, second, null, "2026-11-04 10:00", "2026-11-04 16:00");
        long leadFirst = attendance(manager, first, null, "2026-11-04 08:00", "2026-11-04 17:00");
        long leadSecond = attendance(manager, second, null, "2026-11-05 08:00", "2026-11-05 09:00");
        long outsiderRecord = attendance(outsider, second, null, "2026-11-04 11:00", "2026-11-04 15:00");
        long otherLead = attendance(secondManager, second, null, "2026-11-04 09:30", "2026-11-04 15:00");

        var own = at(staff, "2026-11-06 09:00");
        var list = ok(get(own, "/api/attendance-records"));
        assertThat(ids(list.get("items"))).containsExactly(id(mine));
        assertThat(fields(list.get("items").get(0))).isEqualTo(RECORD);
        assertThat(list.at("/meta/total").asLong()).isEqualTo(1);
        assertThat(ids(ok(get(own, "/api/attendance-records?scope=" + first)).get("items"))).containsExactly(id(mine));
        var named = ok(get(own, "/api/attendance-records?scope=" + first + "&employeeId=" + colleague));
        assertThat(named.get("items").size()).isZero();
        assertThat(named.at("/meta/total").asLong()).isZero();
        assertThat(ids(ok(get(own, "/api/attendance-records?mine=true")).get("items"))).containsExactly(id(mine));
        refused(get(own, "/api/attendance-records?scope=" + second), 403, "FORBIDDEN");
        assertThat(get(own, "/api/attendance-records/" + peer).statusCode()).isEqualTo(404);
        assertThat(get(own, "/api/attendance-records/" + workerFirst).statusCode()).isEqualTo(404);
        assertThat(get(own, "/api/attendance-records/999999999").statusCode()).isEqualTo(404);
        assertThat(ok(get(own, "/api/attendance-records/" + mine)).get("id").asString()).isEqualTo(id(mine));

        // MANAGER of the first branch, STAFF at the second: everyone at the first, only themself at the second.
        var lead = at(manager, "2026-11-06 09:00");
        assertThat(ids(ok(get(lead, "/api/attendance-records")).get("items")))
                .containsExactlyInAnyOrder(id(mine), id(peer), id(workerFirst), id(leadFirst), id(leadSecond));
        assertThat(ids(ok(get(lead, "/api/attendance-records?employeeId=" + worker)).get("items"))).containsExactly(id(workerFirst));
        assertThat(ids(ok(get(lead, "/api/attendance-records?scope=" + second)).get("items"))).containsExactly(id(leadSecond));
        assertThat(ids(ok(get(lead, "/api/attendance-records?mine=true")).get("items"))).containsExactly(id(leadSecond), id(leadFirst));
        assertThat(ids(ok(get(lead, "/api/attendance-records?mine=true&employeeId=" + staff)).get("items"))).isEmpty();
        assertThat(get(lead, "/api/attendance-records/" + workerSecond).statusCode()).isEqualTo(404);
        assertThat(get(lead, "/api/attendance-records/" + otherLead).statusCode()).isEqualTo(404);
        assertThat(ok(get(lead, "/api/attendance-records/" + peer)).at("/employee/id").asString()).isEqualTo(id(colleague));

        // Working at two branches opens neither branch's colleagues.
        var two = at(worker, "2026-11-06 09:00");
        assertThat(ids(ok(get(two, "/api/attendance-records")).get("items"))).containsExactly(id(workerSecond), id(workerFirst));
        assertThat(get(two, "/api/attendance-records/" + outsiderRecord).statusCode()).isEqualTo(404);

        var other = at(secondManager, "2026-11-06 09:00");
        assertThat(ids(ok(get(other, "/api/attendance-records")).get("items")))
                .containsExactlyInAnyOrder(id(workerSecond), id(outsiderRecord), id(otherLead), id(leadSecond));
        assertThat(get(other, "/api/attendance-records/" + workerFirst).statusCode()).isEqualTo(404);

        var boss = at(owner, "2026-11-06 09:00");
        assertThat(ids(ok(get(boss, "/api/attendance-records?scope=" + first)).get("items")))
                .containsExactlyInAnyOrder(id(mine), id(peer), id(workerFirst), id(leadFirst));
        assertThat(ok(get(boss, "/api/attendance-records/" + otherLead)).get("branchId").asString()).isEqualTo(id(second));
    }

    @Test void theListFiltersBySeoulDaysStateAndShiftInAFixedOrder() throws Exception {
        // 23:30 on the 4th and 00:30 on the 5th in Seoul are both the 4th in UTC.
        long late = attendance(staff, first, null, "2026-11-04 23:30", "2026-11-05 01:00");
        long early = attendance(colleague, first, null, "2026-11-05 00:30", "2026-11-05 02:00");
        long sameTime = attendance(worker, first, null, "2026-11-05 00:30", null);
        long paused = attendance(manager, first, null, "2026-11-05 00:10", null);
        pause(paused, "2026-11-05 01:00", null);
        var lead = at(manager, "2026-11-05 03:00");
        String base = "/api/attendance-records?scope=" + first;
        assertThat(ids(ok(get(lead, base)).get("items"))).as("newest clock-in first, then id descending")
                .containsExactly(id(sameTime), id(early), id(paused), id(late));
        var fifth = ok(get(lead, base + "&from=2026-11-05&to=2026-11-05")).get("items");
        assertThat(ids(fifth)).containsExactly(id(sameTime), id(early), id(paused));
        for (var item : fifth) assertThat(item.get("workDate").asString()).isEqualTo("2026-11-05");
        assertThat(ids(ok(get(lead, base + "&to=2026-11-04")).get("items"))).containsExactly(id(late));
        assertThat(ok(get(lead, base + "&from=2026-11-04&to=2026-11-04")).at("/items/0/workDate").asString()).isEqualTo("2026-11-04");
        assertThat(ids(ok(get(lead, base + "&status=" + encoded("퇴근"))).get("items"))).containsExactly(id(early), id(late));
        assertThat(ids(ok(get(lead, base + "&status=" + encoded("근무 중"))).get("items"))).containsExactly(id(sameTime));
        assertThat(ids(ok(get(lead, base + "&status=" + encoded("휴게 중"))).get("items"))).containsExactly(id(paused));
        var page = ok(get(lead, base + "&page=2&pageSize=3"));
        assertThat(ids(page.get("items"))).containsExactly(id(late));
        assertThat(page.at("/meta/total").asLong()).isEqualTo(4);
        for (String bad : List.of("from=2026-11-05&to=2026-11-04", "from=2026-13-01", "from=05-11-2026", "status=WORKING", "status=" + encoded("예정"),
                "shiftId=abc", "employeeId=0", "mine=yes", "page=0"))
            refused(get(lead, base + "&" + bad), 422, "VALIDATION_FAILED");
        assertThat(ok(get(lead, base + "&shiftId=999999999")).get("items").size()).isZero();
    }

    // ---- membership ended -----------------------------------------------------------------------------------

    @Test void afterTheMembershipEndsTheOpenAttendanceCanBeClosedButNotExtended() throws Exception {
        var two = at(worker, "2026-11-04 09:00");
        long id = created(clockIn(two, second)).get("id").asLong();
        two = at(worker, "2026-11-04 12:00");
        ok(breakStart(two, 1));
        revoke(worker, second);
        // The record is hidden from the lists and the detail, but its owner still gets it to close it.
        assertThat(current(two).get("id").asLong()).isEqualTo(id);
        assertThat(current(two).get("status").asString()).isEqualTo("휴게 중");
        assertThat(ids(ok(get(two, "/api/attendance-records")).get("items"))).doesNotContain(id(id));
        assertThat(get(two, "/api/attendance-records/" + id).statusCode()).isEqualTo(404);
        two = at(worker, "2026-11-04 12:30");
        assertThat(ok(breakEnd(two, 2)).get("status").asString()).isEqualTo("근무 중");
        // A new break is new activity at a branch that is no longer theirs.
        refused(breakStart(two, 3), 403, "FORBIDDEN");
        refused(breakStart(two, 1), 409, "VERSION_CONFLICT");
        two = at(worker, "2026-11-04 15:00");
        var left = ok(clockOut(two, 3));
        assertThat(left.get("status").asString()).isEqualTo("퇴근");
        assertThat(left.get("workedMinutes").asLong()).isEqualTo(330);
        assertThat(current(two).isNull()).isTrue();
        assertThat(get(two, "/api/attendance-records/" + id).statusCode()).isEqualTo(404);
        // Closed, it no longer blocks a clock-in at the branch still theirs.
        assertThat(created(clockIn(at(worker, "2026-11-04 16:00"), first)).get("branchId").asString()).isEqualTo(id(first));
        // The second branch's MANAGER still reads it: the record stays where it was recorded.
        assertThat(ok(get(at(secondManager, "2026-11-04 16:00"), "/api/attendance-records/" + id)).get("workedMinutes").asLong()).isEqualTo(330);
    }
}
