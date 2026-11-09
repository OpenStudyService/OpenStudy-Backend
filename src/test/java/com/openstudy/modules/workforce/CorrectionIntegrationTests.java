package com.openstudy.modules.workforce;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.EmployeeAttendanceComparisonRow;
import com.openstudy.modules.ai.service.OpenStudyClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #107 over HTTP and the database, one test per acceptance criterion: only the employee corrects their own closed
 * attendance and nothing changes before approval; the attendance branch's MANAGER or the OWNER decides, never on their
 * own request; an approval refuses an overlap with another attendance or a break outside the stretch (M12-D08); an
 * approval changes the attendance and its M8 worked minutes and keeps the original, the correction and the approver; a
 * rejection keeps its reason and handler and changes nothing; only the requester cancels, never after approval; and
 * repeated or simultaneous approvals change the attendance once.
 */
class CorrectionIntegrationTests extends ShiftIntegrationSupport {
    /** Ten days ago in Seoul: every attendance here is in the past, so no correction is in the future. */
    static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(10);

    static String at(int plusDays, String time) { return DAY.plusDays(plusDays) + " " + time; }

    @Autowired OpenStudyAnalyses analyses;
    @Autowired OpenStudyClock analysisClock;

    static Map<String, Object> clock(String in, String out) {
        var body = new LinkedHashMap<String, Object>();
        body.put("kind", "출퇴근"); body.put("clockInAt", at(in)); body.put("clockOutAt", at(out)); body.put("reason", "출근 버튼 누락");
        return body;
    }

    static Map<String, Object> breakFix(long breakId, String from, String to) {
        var body = new LinkedHashMap<String, Object>();
        body.put("kind", "휴게"); body.put("breakId", Long.toString(breakId)); body.put("breakStartedAt", at(from));
        body.put("breakEndedAt", at(to)); body.put("reason", "휴게 종료 누락");
        return body;
    }

    HttpResponse<String> ask(Session session, long record, Map<String, Object> body, String key) throws Exception {
        return http(session.client(), "POST", "/api/attendance-records/" + record + "/corrections", session.token(), body, key);
    }

    HttpResponse<String> ask(Session session, long record, Map<String, Object> body) throws Exception { return ask(session, record, body, key()); }

    JsonNode asked(Session session, long record, Map<String, Object> body) throws Exception { return created(ask(session, record, body)); }

    HttpResponse<String> command(Session session, long correction, String name, Map<String, Object> body) throws Exception {
        return http(session.client(), "POST", "/api/attendance-corrections/" + correction + "/" + name, session.token(), body, null);
    }

    HttpResponse<String> approve(Session session, long correction, long version) throws Exception {
        return command(session, correction, "approve", Map.of("version", version));
    }

    HttpResponse<String> reject(Session session, long correction, long version) throws Exception {
        return command(session, correction, "reject", Map.of("version", version, "reason", "근무표와 맞지 않습니다"));
    }

    long breakOf(long attendance) { return count("SELECT MAX(id) FROM attendance_breaks WHERE attendance_id=?", attendance); }

    String clockIn(long attendance) {
        return jdbc.queryForObject("SELECT DATE_FORMAT(clock_in_at,'%Y-%m-%d %H:%i:%s') FROM attendance_records WHERE id=?", String.class, attendance);
    }

    long recordVersion(long attendance) { return count("SELECT version FROM attendance_records WHERE id=?", attendance); }

    @Test void onlyTheEmployeeCorrectsTheirOwnClosedAttendanceAndNothingChangesBeforeApproval() throws Exception {
        try (var lead = login(manager); var member = login(staff); var peer = login(colleague)) {
            long record = attendance(staff, first, null, at(0, "09:00"), at(0, "18:00"));
            String original = clockIn(record);
            refused(ask(lead, record, clock(at(0, "08:00"), at(0, "18:00"))), 403, "FORBIDDEN");
            refused(ask(peer, record, clock(at(0, "08:00"), at(0, "18:00"))), 404, "NOT_FOUND");
            refused(ask(member, record, with(clock(at(0, "08:00"), at(0, "18:00")), "breakId", "1")), 422, "VALIDATION_FAILED");
            refused(ask(member, record, clock(at(0, "18:00"), at(0, "08:00"))), 422, "CORRECTION_TIME_INVALID");
            refused(ask(member, record, clock(at(0, "08:00"), at(2, "08:01"))), 422, "CORRECTION_TIME_INVALID");
            var fraction = with(clock(at(0, "08:00"), at(0, "18:00")), "clockInAt", seoul(at(0, "08:00")).plusMillis(500).toString());
            refused(ask(member, record, fraction), 422, "CORRECTION_TIME_INVALID");
            var future = with(clock(at(0, "08:00"), at(0, "18:00")), "clockOutAt", java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            refused(ask(member, record, future), 422, "CORRECTION_TIME_INVALID");
            refused(ask(member, record, clock(at(0, "09:00"), at(0, "18:00"))), 422, "CORRECTION_UNCHANGED");

            String key = key();
            var body = created(ask(member, record, clock(at(0, "08:00"), at(0, "18:00")), key));
            assertThat(body.get("status").asString()).isEqualTo("신청");
            assertThat(body.get("kind").asString()).isEqualTo("출퇴근");
            assertThat(body.at("/before/clockInAt").asString()).isEqualTo(seoul(at(0, "09:00")).toString());
            assertThat(body.at("/after/clockInAt").asString()).isEqualTo(seoul(at(0, "08:00")).toString());
            assertThat(body.at("/before/breakStartedAt").isNull()).isTrue();
            assertThat(body.get("canCancel").asBoolean()).isTrue();
            assertThat(body.get("canApprove").asBoolean()).isFalse();
            // The same key and input replay the first answer; the attendance itself has not moved.
            assertThat(created(ask(member, record, clock(at(0, "08:00"), at(0, "18:00")), key)).get("id").asString()).isEqualTo(body.get("id").asString());
            refused(ask(member, record, clock(at(0, "07:00"), at(0, "18:00")), key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(clockIn(record)).isEqualTo(original);
            assertThat(count("SELECT COUNT(*) FROM attendance_corrections WHERE attendance_id=?", record)).isEqualTo(1);
            var pending = ask(member, record, clock(at(0, "07:00"), at(0, "18:00")));
            refused(pending, 409, "CORRECTION_PENDING");
            assertThat(node(pending).get("pendingCorrectionId").asString()).isEqualTo(body.get("id").asString());

            long open = attendance(staff, first, null, at(1, "09:00"), null);
            refused(ask(member, open, clock(at(1, "08:00"), at(1, "10:00"))), 409, "CORRECTION_TARGET_OPEN");
            long other = attendance(colleague, first, null, at(2, "09:00"), at(2, "18:00"));
            pause(other, at(2, "12:00"), at(2, "13:00"));
            refused(ask(member, record, breakFix(breakOf(other), at(2, "12:00"), at(2, "12:30"))), 422, "VALIDATION_FAILED");
        }
    }

    @Test void decisionsBelongToTheAttendanceBranchManagerAndNeverToTheRequester() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var peer = login(colleague);
                var boss = login(owner)) {
            long record = attendance(staff, first, null, at(3, "09:00"), at(3, "18:00"));
            long correction = asked(member, record, clock(at(3, "08:30"), at(3, "18:00"))).get("id").asLong();
            refused(approve(peer, correction, 1), 404, "NOT_FOUND");
            refused(approve(member, correction, 1), 403, "FORBIDDEN");
            refused(reject(member, correction, 1), 403, "FORBIDDEN");
            refused(approve(other, correction, 1), 404, "NOT_FOUND");
            assertThat(ok(get(lead, "/api/attendance-corrections/" + correction)).get("canApprove").asBoolean()).isTrue();

            long own = attendance(manager, first, null, at(3, "09:00"), at(3, "18:00"));
            long mine = asked(lead, own, clock(at(3, "08:00"), at(3, "18:00"))).get("id").asLong();
            assertThat(ok(get(lead, "/api/attendance-corrections/" + mine)).get("canApprove").asBoolean()).isFalse();
            refused(approve(lead, mine, 1), 403, "SELF_DECISION_FORBIDDEN");
            refused(reject(lead, mine, 1), 403, "SELF_DECISION_FORBIDDEN");
            assertThat(ok(approve(boss, mine, 1)).get("status").asString()).isEqualTo("승인");
            assertThat(ok(approve(lead, correction, 1)).at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
        }
    }

    @Test void anApprovalRefusesAnOverlapWithOtherAttendanceOrABreakOutsideTheStretch() throws Exception {
        try (var lead = login(manager); var member = login(staff); var person = login(worker)) {
            // At the request: the corrected stretch meets the employee's previous attendance.
            long before = attendance(staff, first, null, at(4, "06:00"), at(4, "08:00"));
            long record = attendance(staff, first, null, at(4, "09:00"), at(4, "18:00"));
            var overlap = ask(member, record, clock(at(4, "07:00"), at(4, "18:00")));
            refused(overlap, 409, "CORRECTION_OVERLAP");
            var conflict = node(overlap).get("attendanceConflicts").get(0);
            assertThat(conflict.get("visible").asBoolean()).isTrue();
            assertThat(conflict.get("attendanceRecordId").asString()).isEqualTo(Long.toString(before));
            assertThat(conflict.get("overlapStartsAt").asString()).isEqualTo(seoul(at(4, "07:00")).toString());
            assertThat(conflict.get("overlapEndsAt").asString()).isEqualTo(seoul(at(4, "08:00")).toString());
            // Half-open: ending where the other starts is no overlap.
            pause(record, at(4, "12:00"), at(4, "13:00"));
            refused(ask(member, record, clock(at(4, "08:00"), at(4, "11:00"))), 409, "CORRECTION_BREAK_OUTSIDE");

            // At the approval: an attendance at another branch written after the request, hidden from the first branch's manager.
            long moving = attendance(worker, first, null, at(5, "09:00"), at(5, "18:00"));
            long correction = asked(person, moving, clock(at(5, "09:00"), at(5, "21:00"))).get("id").asLong();
            attendance(worker, second, null, at(5, "19:00"), at(5, "20:00"));
            attendance(worker, second, null, at(5, "20:00"), at(5, "22:00"));
            var hidden = approve(lead, correction, 1);
            refused(hidden, 409, "CORRECTION_OVERLAP");
            var merged = node(hidden).get("attendanceConflicts");
            assertThat(merged.size()).isEqualTo(1);
            assertThat(merged.get(0).get("visible").asBoolean()).isFalse();
            assertThat(merged.get(0).get("label").asString()).isEqualTo("다른 지점 근태");
            assertThat(merged.get(0).get("attendanceRecordId").isNull()).isTrue();
            assertThat(merged.get(0).get("overlapStartsAt").asString()).isEqualTo(seoul(at(5, "19:00")).toString());
            assertThat(merged.get(0).get("overlapEndsAt").asString()).isEqualTo(seoul(at(5, "21:00")).toString());
            assertThat(clockIn(moving)).isEqualTo(sql(at(5, "09:00")));

            // A break moved outside the attendance, or onto another break.
            long breaks = attendance(colleague, first, null, at(6, "09:00"), at(6, "18:00"));
            pause(breaks, at(6, "12:00"), at(6, "13:00"));
            long lunch = breakOf(breaks);
            pause(breaks, at(6, "15:00"), at(6, "15:30"));
            long tea = breakOf(breaks);
            try (var peer = login(colleague)) {
                var outside = ask(peer, breaks, breakFix(lunch, at(6, "17:30"), at(6, "18:30")));
                refused(outside, 409, "CORRECTION_BREAK_OUTSIDE");
                assertThat(node(outside).get("breakIds").get(0).asString()).isEqualTo(Long.toString(lunch));
                var onTop = ask(peer, breaks, breakFix(lunch, at(6, "15:00"), at(6, "16:00")));
                refused(onTop, 409, "CORRECTION_BREAK_OUTSIDE");
                assertThat(node(onTop).get("breakIds").toString()).contains(Long.toString(lunch)).contains(Long.toString(tea));
            }
        }
    }

    @Test void anApprovalChangesTheAttendanceKeepsTheOriginalAndM8RecountsTheMinutes() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long record = attendance(staff, first, null, at(7, "09:00"), at(7, "18:00"));
            pause(record, at(7, "12:00"), at(7, "13:00"));
            long lunch = breakOf(record);
            var before = ok(get(lead, "/api/attendance-records/" + record));
            assertThat(before.get("workedMinutes").asLong()).isEqualTo(480);

            long correction = asked(member, record, clock(at(7, "08:30"), at(7, "18:00"))).get("id").asLong();
            var approved = ok(approve(lead, correction, 1));
            assertThat(approved.get("status").asString()).isEqualTo("승인");
            assertThat(approved.at("/before/clockInAt").asString()).isEqualTo(seoul(at(7, "09:00")).toString());
            assertThat(approved.at("/after/clockInAt").asString()).isEqualTo(seoul(at(7, "08:30")).toString());
            assertThat(approved.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(approved.at("/decision/at").isNull()).isFalse();
            assertThat(approved.at("/decision/reason").isNull()).isTrue();
            assertThat(approved.get("canCancel").asBoolean()).isFalse();
            var after = ok(get(lead, "/api/attendance-records/" + record));
            assertThat(after.get("clockInAt").asString()).isEqualTo(seoul(at(7, "08:30")).toString());
            assertThat(after.get("workedMinutes").asLong()).isEqualTo(510);
            assertThat(after.get("version").asLong()).isEqualTo(before.get("version").asLong() + 1);
            assertThat(count("SELECT COUNT(*) FROM attendance_corrections WHERE id=? AND before_clock_in_at=? AND after_clock_in_at=? AND decided_by=?",
                    correction, sql(at(7, "09:00")), sql(at(7, "08:30")), manager)).isEqualTo(1);

            // A further change is a new correction from the corrected values; a break correction recounts too.
            var second = asked(member, record, breakFix(lunch, at(7, "12:00"), at(7, "12:30")));
            assertThat(second.at("/before/breakEndedAt").asString()).isEqualTo(seoul(at(7, "13:00")).toString());
            ok(approve(lead, second.get("id").asLong(), 1));
            var recounted = ok(get(lead, "/api/attendance-records/" + record));
            assertThat(recounted.get("workedMinutes").asLong()).isEqualTo(540);
            assertThat(recounted.at("/breaks/0/endedAt").asString()).isEqualTo(seoul(at(7, "12:30")).toString());
            assertThat(recounted.get("version").asLong()).isEqualTo(before.get("version").asLong() + 2);
        }
    }

    @Test void aRejectionKeepsItsReasonAndHandlerAndChangesNothing() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long record = attendance(staff, first, null, at(8, "09:00"), at(8, "18:00"));
            long version = recordVersion(record);
            long correction = asked(member, record, clock(at(8, "08:00"), at(8, "18:00"))).get("id").asLong();
            refused(command(lead, correction, "reject", Map.of("version", 1)), 422, "VALIDATION_FAILED");
            refused(command(lead, correction, "reject", Map.of("version", 1, "reason", " ")), 422, "VALIDATION_FAILED");
            var rejected = ok(reject(lead, correction, 1));
            assertThat(rejected.get("status").asString()).isEqualTo("반려");
            assertThat(rejected.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(rejected.at("/decision/reason").asString()).isEqualTo("근무표와 맞지 않습니다");
            assertThat(ok(get(member, "/api/attendance-corrections/" + correction)).at("/decision/reason").asString()).isEqualTo("근무표와 맞지 않습니다");
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE entity_type='attendance-corrections' AND entity_id=? AND action='REJECTED' "
                    + "AND actor_employee_id=? AND branch_id=? AND reason=?", correction, manager, first, "근무표와 맞지 않습니다")).isEqualTo(1);
            assertThat(clockIn(record)).isEqualTo(sql(at(8, "09:00")));
            assertThat(recordVersion(record)).isEqualTo(version);
            var again = approve(lead, correction, 2);
            refused(again, 409, "INVALID_STATE");
            assertThat(node(again).get("latestVersion").asLong()).isEqualTo(2);
            assertThat(node(again).get("reloadUrl").asString()).isEqualTo("/api/attendance-corrections/" + correction);
        }
    }

    @Test void onlyTheRequesterCancelsAndNeverAnApprovedCorrection() throws Exception {
        try (var lead = login(manager); var member = login(staff); var peer = login(colleague)) {
            long record = attendance(staff, first, null, at(9, "09:00"), at(9, "18:00"));
            long correction = asked(member, record, clock(at(9, "08:00"), at(9, "18:00"))).get("id").asLong();
            refused(command(lead, correction, "cancel", Map.of("version", 1)), 403, "FORBIDDEN");
            refused(command(peer, correction, "cancel", Map.of("version", 1)), 404, "NOT_FOUND");
            refused(command(member, correction, "cancel", Map.of("version", 2)), 409, "VERSION_CONFLICT");
            var cancelled = ok(command(member, correction, "cancel", Map.of("version", 1)));
            assertThat(cancelled.get("status").asString()).isEqualTo("취소");
            assertThat(cancelled.at("/cancellation/kind").asString()).isEqualTo("신청 취소");
            assertThat(cancelled.get("decision").isNull()).isTrue();
            assertThat(clockIn(record)).isEqualTo(sql(at(9, "09:00")));

            long approved = asked(member, record, clock(at(9, "08:00"), at(9, "18:00"))).get("id").asLong();
            ok(approve(lead, approved, 1));
            refused(command(member, approved, "cancel", Map.of("version", 2)), 409, "INVALID_STATE");
            assertThat(clockIn(record)).isEqualTo(sql(at(9, "08:00")));
            refused(command(lead, approved, "cancel-approval", Map.of("version", 2)), 404, "NOT_FOUND");
        }
    }

    @Test void repeatedOrSimultaneousApprovalsChangeTheAttendanceOnce() throws Exception {
        try (var lead = login(manager); var boss = login(owner); var member = login(staff)) {
            long record = attendance(staff, first, null, at(8, "09:00"), at(8, "18:00"));
            long version = recordVersion(record);
            long correction = asked(member, record, clock(at(8, "08:00"), at(8, "18:00"))).get("id").asLong();
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 4; index++) {
                var session = index % 2 == 0 ? lead : boss;
                calls.add(index < 2 ? () -> approve(session, correction, 1) : () -> reject(session, correction, 1));
            }
            var responses = race(calls);
            assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
            for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "INVALID_STATE");
            String status = count("SELECT COUNT(*) FROM attendance_corrections WHERE id=? AND status='APPROVED'", correction) == 1 ? "APPROVED" : "REJECTED";
            assertThat(recordVersion(record)).isEqualTo("APPROVED".equals(status) ? version + 1 : version);
            refused(approve(lead, correction, 2), 409, "INVALID_STATE");
            assertThat(recordVersion(record)).isEqualTo("APPROVED".equals(status) ? version + 1 : version);
        }
    }

    @Test void listsShowAManagedBranchAndTheRequestersOwn() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var peer = login(colleague)) {
            long record = attendance(staff, first, null, at(0, "09:00"), at(0, "18:00"));
            String correction = asked(member, record, clock(at(0, "08:00"), at(0, "18:00"))).get("id").asString();
            assertThat(ids(ok(get(lead, "/api/attendance-corrections")).get("items"))).contains(correction);
            assertThat(ids(ok(get(member, "/api/attendance-corrections?mine=true")).get("items"))).containsExactly(correction);
            assertThat(ids(ok(get(peer, "/api/attendance-corrections")).get("items"))).isEmpty();
            assertThat(ids(ok(get(other, "/api/attendance-corrections")).get("items"))).isEmpty();
            assertThat(ids(ok(get(lead, "/api/attendance-corrections?attendanceRecordId=" + record)).get("items"))).containsExactly(correction);
            assertThat(ids(ok(get(lead, "/api/attendance-corrections?from=" + DAY + "&to=" + DAY)).get("items"))).contains(correction);
            assertThat(ids(ok(get(lead, "/api/attendance-corrections?from=" + DAY.plusDays(1))).get("items"))).doesNotContain(correction);
            assertThat(ids(ok(get(lead, "/api/attendance-corrections?status=" + URLEncoder.encode("승인", StandardCharsets.UTF_8))).get("items")))
                    .doesNotContain(correction);
            revoke(staff, first);
            refused(get(member, "/api/attendance-corrections/" + correction), 404, "NOT_FOUND");
        }
    }

    /**
     * #197: approval rewrites the attendance, so the weekly work analysis (POC-010) reads the corrected minutes from the
     * attendance and restores the original from the earliest approved correction's before values, clock and break alike.
     */
    @Test void theWeeklyWorkAnalysisComparesTheOriginalAttendanceWithTheApprovedOne() throws Exception {
        LocalDate monday = analysisClock.lastWeek().startInclusive().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        try (var lead = login(manager); var member = login(staff)) {
            // Corrected three times through #107: clock 09:00 -> 08:30 -> 08:00, lunch 12:00-13:00 -> 12:30-13:00.
            long record = attendance(staff, first, null, monday.plusDays(1) + " 09:00", monday.plusDays(1) + " 18:00");
            pause(record, monday.plusDays(1) + " 12:00", monday.plusDays(1) + " 13:00");
            long lunch = breakOf(record);
            long early = asked(member, record, clock(monday.plusDays(1) + " 08:30", monday.plusDays(1) + " 18:00")).get("id").asLong();
            ok(approve(lead, early, 1));
            long shorter = asked(member, record, breakFix(lunch, monday.plusDays(1) + " 12:30", monday.plusDays(1) + " 13:00")).get("id").asLong();
            ok(approve(lead, shorter, 1));
            long earlier = asked(member, record, clock(monday.plusDays(1) + " 08:00", monday.plusDays(1) + " 18:00")).get("id").asLong();
            ok(approve(lead, earlier, 1));
        }
        // No correction at all.
        attendance(worker, first, null, monday.plusDays(3) + " 09:00", monday.plusDays(3) + " 17:00");

        @SuppressWarnings("unchecked")
        var rows = (java.util.List<EmployeeAttendanceComparisonRow>) analyses.executePoc010WeeklyAttendance(java.util.List.of(first))
                .summary().get("employee_rows");
        var byEmployee = new LinkedHashMap<Long, EmployeeAttendanceComparisonRow>();
        rows.forEach(row -> byEmployee.put(row.employeeId(), row));
        assertThat(byEmployee).containsOnlyKeys(staff, worker);

        // Original 09:00-18:00 less a 60-minute lunch; corrected 08:00-18:00 less a 30-minute lunch.
        assertThat(byEmployee.get(staff).rawWorkMinutes()).isEqualTo(480);
        assertThat(byEmployee.get(staff).illustrativeCorrectedMinutes()).isEqualTo(570);
        assertThat(byEmployee.get(staff).correctionDeltaMinutes()).isEqualTo(90);
        assertThat(byEmployee.get(worker).rawWorkMinutes()).isEqualTo(480);
        assertThat(byEmployee.get(worker).correctionDeltaMinutes()).isZero();
    }
}
