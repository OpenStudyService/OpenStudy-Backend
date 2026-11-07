package com.openstudy.modules.workforce;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #83 over HTTP and the database: who may plan, the time rules, conflicts, cancellation and who reads what. */
class ShiftIntegrationTests extends ShiftIntegrationSupport {
    static final Set<String> SHIFT_FIELDS = Set.of("id", "version", "status", "branchId", "employee", "startsAt", "endsAt", "workDate",
            "plannedBreakMinutes", "plannedWorkMinutes", "cancellation", "attendance", "createdBy", "createdAt", "updatedAt", "canEdit");

    // ---- who may plan (M8-D01) -------------------------------------------------------------------------------

    @Test void onlyTheOwnerOrTheBranchManagerPlansShifts() throws Exception {
        try (var boss = login(owner); var lead = login(manager); var other = login(secondManager); var plain = login(staff)) {
            refused(register(plain, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00")), 403, "FORBIDDEN");
            refused(register(other, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00")), 403, "FORBIDDEN");
            refused(register(lead, shift(999_999_999L, staff, "2026-11-04 09:00", "2026-11-04 18:00")), 403, "FORBIDDEN");
            created(register(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00")));
            created(register(boss, shift(first, colleague, "2026-11-04 09:00", "2026-11-04 18:00")));
            // A MANAGER plans for himself and for another MANAGER too; M8-D01 does not limit the target's role.
            created(register(lead, shift(first, manager, "2026-11-04 09:00", "2026-11-04 18:00")));
            member(secondManager, first, "MANAGER");
            created(register(lead, shift(first, secondManager, "2026-11-04 09:00", "2026-11-04 18:00")));
            assertThat(shifts(staff) + shifts(colleague) + shifts(manager) + shifts(secondManager)).isEqualTo(4);
        }
    }

    @Test void theEmployeeMustBeAnActiveMemberOfTheShiftBranch() throws Exception {
        try (var lead = login(manager); var boss = login(owner)) {
            var day = List.of("2026-11-04 09:00", "2026-11-04 18:00");
            refused(register(lead, shift(first, outsider, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            refused(register(lead, shift(first, 999_999_999L, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            // The OWNER manages anyone's shifts but is no shift employee without a membership row (fk_shifts_1).
            refused(register(lead, shift(first, owner, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            refused(register(boss, shift(first, owner, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            member(owner, first, "STAFF");
            created(register(boss, shift(first, owner, day.get(0), day.get(1))));
            revoke(colleague, first);
            refused(register(lead, shift(first, colleague, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", staff);
            refused(register(lead, shift(first, staff, day.get(0), day.get(1))), 422, "INVALID_SHIFT_EMPLOYEE");
            assertThat(shifts(outsider) + shifts(colleague) + shifts(staff)).isZero();
        }
    }

    // ---- time rules (M8-D03) ---------------------------------------------------------------------------------

    @Test void theContractsLengthTableHoldsExactly() throws Exception {
        try (var lead = login(manager)) {
            // Each row on its own day, so earlier rows never conflict with later ones.
            var made = created(register(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00", 60)));
            assertThat(made.get("plannedWorkMinutes").asLong()).isEqualTo(480);
            var night = created(register(lead, shift(first, staff, "2026-11-05 22:00", "2026-11-06 06:00", 30)));
            assertThat(night.get("workDate").asString()).as("the day it starts").isEqualTo("2026-11-05");
            assertThat(night.get("plannedWorkMinutes").asLong()).isEqualTo(450);
            created(register(lead, shift(first, staff, "2026-11-07 09:00", "2026-11-08 09:00", 0)));
            refused(register(lead, shift(first, staff, "2026-11-10 09:00", "2026-11-11 09:01", 0)), 422, "SHIFT_TIME_INVALID");
            refused(register(lead, shift(first, staff, "2026-11-10 09:00", "2026-11-10 09:00", 0)), 422, "SHIFT_TIME_INVALID");
            refused(register(lead, shift(first, staff, "2026-11-10 18:00", "2026-11-10 09:00", 0)), 422, "SHIFT_TIME_INVALID");
            refused(register(lead, shift(first, staff, "2026-11-10 09:00:30", "2026-11-10 18:00", 0)), 422, "SHIFT_TIME_INVALID");
            refused(register(lead, with(shift(first, staff, "2026-11-10 09:00", "2026-11-10 18:00"), "endsAt", "2026-11-10T09:00:00.5Z")),
                    422, "SHIFT_TIME_INVALID");
            assertThat(created(register(lead, shift(first, staff, "2026-11-12 09:00", "2026-11-12 17:00", 479))).get("plannedWorkMinutes").asLong())
                    .isEqualTo(1);
            refused(register(lead, shift(first, staff, "2026-11-13 09:00", "2026-11-13 17:00", 480)), 422, "SHIFT_BREAK_INVALID");
            refused(register(lead, shift(first, staff, "2026-11-13 09:00", "2026-11-13 17:00", -1)), 422, "VALIDATION_FAILED");
            refused(register(lead, shift(first, staff, "2026-11-13 09:00", "2026-11-13 17:00", 1.5)), 422, "VALIDATION_FAILED");
            refused(register(lead, shift(first, staff, "2026-11-13 09:00", "2026-11-13 17:00", "30")), 422, "VALIDATION_FAILED");
            assertThat(shifts(staff)).isEqualTo(4);
        }
    }

    @Test void malformedInputIsAFormatErrorAndTimesNeedAnOffset() throws Exception {
        try (var lead = login(manager)) {
            var good = shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00");
            refused(register(lead, with(good, "startsAt", "2026-11-04T09:00:00")), 422, "VALIDATION_FAILED");
            refused(register(lead, with(good, "startsAt", "2026-11-04 09:00")), 422, "VALIDATION_FAILED");
            refused(register(lead, with(good, "startsAt", 1_700_000_000)), 422, "VALIDATION_FAILED");
            refused(register(lead, with(good, "status", "예정")), 422, "VALIDATION_FAILED");
            refused(register(lead, with(good, "createdBy", Long.toString(owner))), 422, "VALIDATION_FAILED");
            refused(register(lead, with(good, "employeeId", "abc")), 422, "VALIDATION_FAILED");
            refused(register(lead, good, null), 422, "VALIDATION_FAILED");
            // "+09:00" and "Z" name the same instant.
            var made = created(register(lead, with(good, "startsAt", "2026-11-04T09:00:00+09:00")));
            assertThat(made.get("startsAt").asString()).isEqualTo("2026-11-04T00:00:00Z");
        }
    }

    @Test void theChecksRunInTheContractsOrder() throws Exception {
        try (var lead = login(manager); var plain = login(staff)) {
            // 403 comes before a malformed body, so a staff member learns nothing from the input rules.
            refused(register(plain, Map.of("branchId", Long.toString(first), "nonsense", true)), 403, "FORBIDDEN");
            refused(register(lead, shift(first, outsider, "2026-11-04 09:00", "2026-11-04 08:00")), 422, "SHIFT_TIME_INVALID");
            stop(first);
            refused(register(lead, shift(first, outsider, "2026-11-04 09:00", "2026-11-04 08:00")), 422, "SHIFT_TIME_INVALID");
            refused(register(lead, shift(first, outsider, "2026-11-04 09:00", "2026-11-04 10:00", 60)), 422, "SHIFT_BREAK_INVALID");
            refused(register(lead, shift(first, outsider, "2026-11-04 09:00", "2026-11-04 18:00")), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);
            refused(register(lead, shift(first, outsider, "2026-11-04 09:00", "2026-11-04 18:00")), 422, "INVALID_SHIFT_EMPLOYEE");
        }
    }

    // ---- conflicts (M8-D05·D06) ------------------------------------------------------------------------------

    @Test void aShiftAtAnotherBranchIsReportedOnlyAsItsOverlap() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var boss = login(owner)) {
            long mine = registered(lead, shift(first, worker, "2026-11-04 08:00", "2026-11-04 10:30"));
            long a = registered(other, shift(second, worker, "2026-11-04 12:00", "2026-11-04 14:00", 15));
            long b = registered(other, shift(second, worker, "2026-11-04 14:00", "2026-11-04 16:00"));
            long c = registered(other, shift(second, worker, "2026-11-04 16:30", "2026-11-04 20:00", 30));
            var response = register(lead, shift(first, worker, "2026-11-04 10:00", "2026-11-04 17:00"));
            refused(response, 409, "SHIFT_CONFLICT");
            var body = node(response);
            assertThat(fields(body)).containsExactlyInAnyOrder("code", "message", "requestId", "conflicts");
            var conflicts = body.get("conflicts");
            assertThat(conflicts.size()).isEqualTo(3);
            var seen = conflicts.get(0);
            assertThat(fields(seen)).containsExactlyInAnyOrder("kind", "visible", "label", "overlapStartsAt", "overlapEndsAt", "shift");
            assertThat(seen.get("visible").asBoolean()).isTrue();
            assertThat(seen.get("label").asString()).isEqualTo("근무");
            assertThat(seen.get("overlapStartsAt").asString()).isEqualTo(at("2026-11-04 10:00"));
            assertThat(seen.get("overlapEndsAt").asString()).isEqualTo(at("2026-11-04 10:30"));
            assertThat(fields(seen.get("shift"))).containsExactlyInAnyOrder("id", "branchId", "startsAt", "endsAt");
            assertThat(seen.at("/shift/id").asString()).isEqualTo(Long.toString(mine));
            assertThat(seen.at("/shift/startsAt").asString()).isEqualTo(at("2026-11-04 08:00"));
            // Two touching shifts there become one stretch, and the third is cut at the requested end.
            var merged = conflicts.get(1);
            assertThat(merged.get("visible").asBoolean()).isFalse();
            assertThat(merged.get("label").asString()).isEqualTo("다른 지점 근무");
            assertThat(merged.get("shift").isNull()).isTrue();
            assertThat(merged.get("overlapStartsAt").asString()).isEqualTo(at("2026-11-04 12:00"));
            assertThat(merged.get("overlapEndsAt").asString()).isEqualTo(at("2026-11-04 16:00"));
            assertThat(conflicts.get(2).get("overlapStartsAt").asString()).isEqualTo(at("2026-11-04 16:30"));
            assertThat(conflicts.get(2).get("overlapEndsAt").asString()).isEqualTo(at("2026-11-04 17:00"));
            String text = response.body();
            for (long hidden : List.of(a, b, c)) assertThat(text).doesNotContain("\"id\":\"" + hidden + "\"");
            assertThat(text).doesNotContain("\"branchId\":\"" + second + "\"", at("2026-11-04 14:00"), at("2026-11-04 20:00"),
                    "plannedBreakMinutes", "version", "근무 둘째 지점", "employee");
            // The OWNER sees every branch, so the same overlaps come named, one per shift.
            var named = node(register(boss, shift(first, worker, "2026-11-04 10:00", "2026-11-04 17:00"))).get("conflicts");
            assertThat(named.size()).isEqualTo(4);
            for (var item : named) assertThat(item.get("visible").asBoolean()).isTrue();
            assertThat(named.get(3).at("/shift/endsAt").asString()).isEqualTo(at("2026-11-04 20:00"));
            assertThat(shifts(worker)).isEqualTo(4);
        }
    }

    @Test void theOtherBranchsManagerSeesTheFirstBranchOnlyAsAnOverlapToo() throws Exception {
        try (var lead = login(manager); var other = login(secondManager)) {
            long mine = registered(lead, shift(first, worker, "2026-11-04 09:00", "2026-11-04 18:00"));
            var response = register(other, shift(second, worker, "2026-11-04 17:00", "2026-11-04 22:00"));
            refused(response, 409, "SHIFT_CONFLICT");
            var conflict = node(response).get("conflicts").get(0);
            assertThat(conflict.get("label").asString()).isEqualTo("다른 지점 근무");
            assertThat(response.body()).doesNotContain("\"id\":\"" + mine + "\"", "\"branchId\":\"" + first + "\"", at("2026-11-04 09:00"));
            // Half-open: ending exactly when the other starts is no overlap.
            created(register(other, shift(second, worker, "2026-11-04 18:00", "2026-11-04 22:00")));
            created(register(other, shift(second, worker, "2026-11-04 05:00", "2026-11-04 09:00")));
        }
    }

    @Test void anApprovedLeaveDayHoldsTheWholeDayAndShowsNothingElse() throws Exception {
        long approved = leave(worker, "2026-11-05", "2026-11-05", "0.5", "APPROVED");
        leave(worker, "2026-11-06", "2026-11-06", "1.0", "APPROVED");
        leave(worker, "2026-11-09", "2026-11-09", "1.0", "REQUESTED");
        leave(worker, "2026-11-10", "2026-11-10", "1.0", "REJECTED");
        leave(worker, "2026-11-11", "2026-11-11", "1.0", "CANCELLED");
        try (var lead = login(manager)) {
            // The contract's example: 22:00 to 06:00 into the leave day overlaps from its midnight.
            var response = register(lead, shift(first, worker, "2026-11-04 22:00", "2026-11-05 06:00"));
            refused(response, 409, "SHIFT_CONFLICT");
            var conflict = node(response).get("conflicts").get(0);
            assertThat(conflict.get("kind").asString()).isEqualTo("휴가");
            assertThat(conflict.get("label").asString()).isEqualTo("승인 휴가");
            assertThat(conflict.get("visible").asBoolean()).isFalse();
            assertThat(conflict.get("shift").isNull()).isTrue();
            assertThat(conflict.get("overlapStartsAt").asString()).isEqualTo("2026-11-04T15:00:00Z");
            assertThat(conflict.get("overlapEndsAt").asString()).isEqualTo("2026-11-04T21:00:00Z");
            assertThat(response.body()).doesNotContain("\"id\":\"" + approved + "\"", "개인 사유", "0.5", "requestedDays");
            // A half day still blocks the whole day, late in the evening too.
            refused(register(lead, shift(first, worker, "2026-11-05 20:00", "2026-11-05 23:00")), 409, "SHIFT_CONFLICT");
            // Two leave days in a row are one stretch.
            var across = node(register(lead, shift(first, worker, "2026-11-05 22:00", "2026-11-06 06:00"))).get("conflicts");
            assertThat(across.size()).isEqualTo(1);
            assertThat(across.get(0).get("overlapStartsAt").asString()).isEqualTo(at("2026-11-05 22:00"));
            assertThat(across.get(0).get("overlapEndsAt").asString()).isEqualTo(at("2026-11-06 06:00"));
            // Ending at the leave day's midnight does not touch it; leave that was not approved does not count.
            created(register(lead, shift(first, worker, "2026-11-04 18:00", "2026-11-05 00:00")));
            created(register(lead, shift(first, worker, "2026-11-07 00:00", "2026-11-07 06:00")));
            for (String day : List.of("2026-11-09", "2026-11-10", "2026-11-11"))
                created(register(lead, shift(first, worker, day + " 09:00", day + " 18:00")));
        }
    }

    @Test void aShiftAndALeaveConflictTogetherInTimeOrder() throws Exception {
        leave(worker, "2026-11-05", "2026-11-05", "1.0", "APPROVED");
        try (var lead = login(manager); var other = login(secondManager)) {
            long mine = registered(lead, shift(first, worker, "2026-11-04 08:00", "2026-11-04 12:00"));
            registered(other, shift(second, worker, "2026-11-04 14:00", "2026-11-04 18:00"));
            // The contract's example request.
            var response = register(lead, shift(first, worker, "2026-11-04 10:00", "2026-11-05 06:00"));
            refused(response, 409, "SHIFT_CONFLICT");
            var conflicts = node(response).get("conflicts");
            assertThat(conflicts.size()).isEqualTo(3);
            assertThat(conflicts.get(0).at("/shift/id").asString()).isEqualTo(Long.toString(mine));
            assertThat(conflicts.get(0).get("overlapStartsAt").asString()).isEqualTo("2026-11-04T01:00:00Z");
            assertThat(conflicts.get(0).get("overlapEndsAt").asString()).isEqualTo("2026-11-04T03:00:00Z");
            assertThat(conflicts.get(1).get("label").asString()).isEqualTo("다른 지점 근무");
            assertThat(conflicts.get(1).get("overlapStartsAt").asString()).isEqualTo("2026-11-04T05:00:00Z");
            assertThat(conflicts.get(1).get("overlapEndsAt").asString()).isEqualTo("2026-11-04T09:00:00Z");
            assertThat(conflicts.get(2).get("label").asString()).isEqualTo("승인 휴가");
            assertThat(conflicts.get(2).get("overlapStartsAt").asString()).isEqualTo("2026-11-04T15:00:00Z");
            assertThat(conflicts.get(2).get("overlapEndsAt").asString()).isEqualTo("2026-11-04T21:00:00Z");
        }
    }

    // ---- changes ---------------------------------------------------------------------------------------------

    @Test void aChangeChecksTheFinalValuesAndLeavesItselfOut() throws Exception {
        try (var lead = login(manager)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00", 60));
            long other = registered(lead, shift(first, staff, "2026-11-04 20:00", "2026-11-04 23:00"));
            // Overlapping its own old time is fine.
            var moved = ok(edit(lead, id, change(1, "startsAt", at("2026-11-04 10:00"), "endsAt", at("2026-11-04 19:00"), "reason", "오픈 조정")));
            assertThat(moved.get("version").asLong()).isEqualTo(2);
            assertThat(moved.get("plannedBreakMinutes").asInt()).isEqualTo(60);
            refused(edit(lead, id, change(2, "endsAt", at("2026-11-05 10:01"))), 422, "SHIFT_TIME_INVALID");
            refused(edit(lead, id, change(2, "plannedBreakMinutes", 540)), 422, "SHIFT_BREAK_INVALID");
            refused(edit(lead, id, change(2, "startsAt", at("2026-11-04 19:00"))), 422, "SHIFT_TIME_INVALID");
            var conflict = edit(lead, id, change(2, "endsAt", at("2026-11-04 21:00")));
            refused(conflict, 409, "SHIFT_CONFLICT");
            assertThat(node(conflict).at("/conflicts/0/shift/id").asString()).isEqualTo(Long.toString(other));
            assertThat(node(conflict).get("conflicts").size()).as("never itself").isEqualTo(1);
            // An identical request changes nothing at all.
            var same = ok(edit(lead, id, change(2, "plannedBreakMinutes", 60)));
            assertThat(same.get("version").asLong()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM shift_events WHERE shift_id=? AND event_type='CHANGED'", id)).isEqualTo(1);
            var history = jdbc.queryForMap("SELECT before_json,after_json,reason,actor_employee_id FROM shift_events WHERE shift_id=? AND event_type='CHANGED'", id);
            assertThat(json.readTree(history.get("before_json").toString()).get("startsAt").asString()).isEqualTo(at("2026-11-04 09:00"));
            assertThat(json.readTree(history.get("after_json").toString()).get("startsAt").asString()).isEqualTo(at("2026-11-04 10:00"));
            assertThat(json.readTree(history.get("after_json").toString()).get("version").asLong()).isEqualTo(2);
            assertThat(history.get("reason")).isEqualTo("오픈 조정");
            assertThat(((Number) history.get("actor_employee_id")).longValue()).isEqualTo(manager);
        }
    }

    @Test void branchEmployeeAndStatusCannotBeEdited() throws Exception {
        try (var lead = login(manager); var plain = login(staff)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            for (String field : List.of("branchId", "employeeId", "status"))
                refused(edit(lead, id, change(1, field, "1")), 422, "IMMUTABLE_FIELD");
            refused(edit(lead, id, change(1)), 422, "VALIDATION_FAILED");
            refused(edit(lead, id, change(1, "reason", "")), 422, "VALIDATION_FAILED");
            refused(edit(lead, id, change(1, "unknown", 1)), 422, "VALIDATION_FAILED");
            // Visibility and the role come first: a staff member's own shift is 403, a colleague's 404, whatever the body.
            refused(edit(plain, id, change(1, "branchId", "1")), 403, "FORBIDDEN");
            assertThat(count("SELECT version FROM shifts WHERE id=?", id)).isZero();
        }
    }

    @Test void aStaleVersionIsAConflictWithoutConflicts() throws Exception {
        try (var lead = login(manager)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            ok(edit(lead, id, change(1, "plannedBreakMinutes", 30)));
            var stale = edit(lead, id, change(1, "plannedBreakMinutes", 45));
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(2);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/shifts/" + id);
            assertThat(node(stale).has("conflicts")).isFalse();
            refused(cancel(lead, id, 1), 409, "VERSION_CONFLICT");
            assertThat(scheduled(staff)).isEqualTo(1);
        }
    }

    @Test void aChangeNeedsTheEmployeeStillAMemberButACancellationDoesNot() throws Exception {
        try (var lead = login(manager)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            revoke(staff, first);
            refused(edit(lead, id, change(1, "plannedBreakMinutes", 30)), 422, "INVALID_SHIFT_EMPLOYEE");
            assertThat(ok(cancel(lead, id, 1)).get("status").asString()).isEqualTo("취소");
        }
    }

    // ---- cancellation (FR-SHIFT-04) --------------------------------------------------------------------------

    @Test void aCancelledShiftLeavesListsAndConflictsButKeepsItsRowHistoryAndAttendance() throws Exception {
        try (var lead = login(manager); var plain = login(staff)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00", 60));
            long record = attendance(staff, first, id, "2026-11-04 09:02:10", "2026-11-04 18:01:40");
            pause(record, "2026-11-04 12:00:00", "2026-11-04 12:30:30");
            var cancelled = ok(cancel(lead, id, Map.of("version", 1, "reason", "매장 점검")));
            assertThat(cancelled.get("status").asString()).isEqualTo("취소");
            assertThat(cancelled.get("version").asLong()).isEqualTo(2);
            assertThat(cancelled.get("canEdit").asBoolean()).isFalse();
            assertThat(cancelled.at("/cancellation/cancelledBy/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(cancelled.at("/cancellation/cancelledBy/name").asString()).isEqualTo("첫 지점 매니저");
            assertThat(cancelled.at("/cancellation/reason").asString()).isEqualTo("매장 점검");
            assertThat(cancelled.at("/cancellation/cancelledAt").asString()).isNotBlank();
            // The linked attendance stays, linked, and is still shown with the shift.
            assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE id=? AND shift_id=?", record, id)).isEqualTo(1);
            assertThat(cancelled.at("/attendance/0/id").asString()).isEqualTo(Long.toString(record));
            assertThat(cancelled.at("/attendance/0/workedMinutes").asLong()).as("32370 s − 1830 s = 30540 s").isEqualTo(509);
            assertThat(count("SELECT COUNT(*) FROM shifts WHERE id=? AND status='CANCELLED'", id)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT event_type FROM shift_events WHERE shift_id=? ORDER BY id", String.class, id))
                    .containsExactly("CREATED", "CANCELLED");
            var history = jdbc.queryForMap("SELECT before_json,after_json,reason FROM shift_events WHERE shift_id=? AND event_type='CANCELLED'", id);
            assertThat(json.readTree(history.get("before_json").toString()).get("status").asString()).isEqualTo("SCHEDULED");
            assertThat(json.readTree(history.get("after_json").toString()).get("status").asString()).isEqualTo("CANCELLED");
            assertThat(history.get("reason")).isEqualTo("매장 점검");
            var created = jdbc.queryForMap("SELECT before_json,after_json,reason FROM shift_events WHERE shift_id=? AND event_type='CREATED'", id);
            assertThat(created.get("before_json")).isNull();
            assertThat(json.readTree(created.get("after_json").toString()).get("version").asLong()).isEqualTo(1);
            // Out of the default list and the week, still in status=취소 and the detail.
            assertThat(ok(get(lead, "/api/shifts?scope=" + first)).at("/meta/total").asLong()).isZero();
            assertThat(ids(ok(get(lead, "/api/shifts?status=%EC%B7%A8%EC%86%8C")).get("items"))).containsExactly(Long.toString(id));
            assertThat(ok(get(lead, "/api/shifts/" + id)).get("status").asString()).isEqualTo("취소");
            var week = ok(get(plain, "/api/shifts/weekly?weekOf=2026-11-04"));
            assertThat(week.get("shifts").size()).isZero();
            assertThat(ids(week.get("attendance"))).containsExactly(Long.toString(record));
            // Again, or edited: the state answers, at the current version.
            refused(cancel(lead, id, 2), 409, "INVALID_STATE");
            refused(edit(lead, id, change(2, "plannedBreakMinutes", 30)), 409, "INVALID_STATE");
            // The time is free again.
            long again = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            assertThat(again).isNotEqualTo(id);
            assertThat(shifts(staff)).isEqualTo(2);
            assertThat(ok(get(lead, "/api/shifts/" + again)).get("cancellation").isNull()).isTrue();
        }
    }

    // ---- stopped branch (M8-D04) -----------------------------------------------------------------------------

    @Test void aStoppedBranchRefusesNewAndChangedShiftsButCancelsAndReads() throws Exception {
        try (var lead = login(manager); var boss = login(owner); var plain = login(staff)) {
            long id = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            stop(first);
            refused(register(lead, shift(first, colleague, "2026-11-04 09:00", "2026-11-04 18:00")), 409, "BRANCH_NOT_ACTIVE");
            refused(register(boss, shift(first, colleague, "2026-11-04 09:00", "2026-11-04 18:00")), 409, "BRANCH_NOT_ACTIVE");
            refused(edit(lead, id, change(1, "plannedBreakMinutes", 30)), 409, "BRANCH_NOT_ACTIVE");
            assertThat(ok(get(plain, "/api/shifts/" + id)).get("canEdit").asBoolean()).isFalse();
            assertThat(ok(get(lead, "/api/shifts?scope=" + first)).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(ok(get(plain, "/api/shifts/weekly?scope=" + first + "&weekOf=2026-11-04")).get("shifts").size()).isEqualTo(1);
            ok(cancel(lead, id, 1));
            assertThat(shifts(colleague)).isZero();
        }
    }

    // ---- request keys ----------------------------------------------------------------------------------------

    @Test void aRetryWithTheSameKeyGetsTheFirstAnswerAndADifferentInputIsAConflict() throws Exception {
        try (var lead = login(manager)) {
            String key = key();
            var body = shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00");
            var once = created(register(lead, body, key));
            var twice = created(register(lead, body, key));
            assertThat(twice.get("id").asString()).isEqualTo(once.get("id").asString());
            assertThat(shifts(staff)).isEqualTo(1);
            refused(register(lead, with(body, "plannedBreakMinutes", 30), key), 409, "IDEMPOTENCY_CONFLICT");
            // Without the key the retry would have collided with itself.
            refused(register(lead, body), 409, "SHIFT_CONFLICT");
            refused(register(lead, body, "short"), 422, "VALIDATION_FAILED");
            assertThat(count("SELECT COUNT(*) FROM shift_events e JOIN shifts s ON s.id=e.shift_id WHERE s.employee_id=?", staff)).isEqualTo(1);
        }
    }

    // ---- who reads what (M8-D02) -----------------------------------------------------------------------------

    @Test void staffSeeOnlyTheirOwnShiftsWhateverTheyFilter() throws Exception {
        long own, theirs;
        try (var lead = login(manager)) {
            own = registered(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
            theirs = registered(lead, shift(first, colleague, "2026-11-04 09:00", "2026-11-04 18:00"));
            registered(lead, shift(first, manager, "2026-11-05 09:00", "2026-11-05 18:00"));
            var all = ok(get(lead, "/api/shifts?scope=" + first));
            assertThat(all.at("/meta/total").asLong()).isEqualTo(3);
            assertThat(ok(get(lead, "/api/shifts?mine=true")).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(ids(ok(get(lead, "/api/shifts?employeeId=" + colleague)).get("items"))).containsExactly(Long.toString(theirs));
        }
        try (var plain = login(staff)) {
            for (String path : List.of("/api/shifts", "/api/shifts?scope=" + first, "/api/shifts?mine=false")) {
                var page = ok(get(plain, path));
                assertThat(ids(page.get("items"))).as(path).containsExactly(Long.toString(own));
                assertThat(page.at("/meta/total").asLong()).as(path).isEqualTo(1);
            }
            var named = ok(get(plain, "/api/shifts?scope=" + first + "&employeeId=" + colleague));
            assertThat(named.get("items").size()).isZero();
            assertThat(named.at("/meta/total").asLong()).isZero();
            var week = ok(get(plain, "/api/shifts/weekly?scope=" + first + "&weekOf=2026-11-04&employeeId=" + colleague));
            assertThat(week.get("shifts").size()).isZero();
            assertThat(ids(ok(get(plain, "/api/shifts/weekly?weekOf=2026-11-04")).get("shifts"))).containsExactly(Long.toString(own));
            refused(get(plain, "/api/shifts/" + theirs), 404, "NOT_FOUND");
            assertThat(ok(get(plain, "/api/shifts/" + own)).get("canEdit").asBoolean()).isFalse();
            refused(edit(plain, theirs, change(1, "plannedBreakMinutes", 30)), 404, "NOT_FOUND");
            refused(edit(plain, own, change(1, "plannedBreakMinutes", 30)), 403, "FORBIDDEN");
            refused(cancel(plain, own, 1), 403, "FORBIDDEN");
            refused(get(plain, "/api/shifts?scope=" + second), 403, "FORBIDDEN");
        }
    }

    @Test void anotherBranchsShiftOfTheSameEmployeeStaysHidden() throws Exception {
        long here, there;
        try (var lead = login(manager); var other = login(secondManager)) {
            here = registered(lead, shift(first, worker, "2026-11-04 09:00", "2026-11-04 13:00"));
            there = registered(other, shift(second, worker, "2026-11-04 14:00", "2026-11-04 18:00"));
            assertThat(ids(ok(get(lead, "/api/shifts?employeeId=" + worker)).get("items"))).containsExactly(Long.toString(here));
            assertThat(ids(ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-04&employeeId=" + worker)).get("shifts"))).containsExactly(Long.toString(here));
            refused(get(lead, "/api/shifts/" + there), 404, "NOT_FOUND");
            refused(edit(lead, there, change(1, "plannedBreakMinutes", 10)), 404, "NOT_FOUND");
            refused(cancel(lead, there, 1), 404, "NOT_FOUND");
            assertThat(ids(ok(get(other, "/api/shifts")).get("items"))).containsExactly(Long.toString(there));
        }
        // A role differs by branch: MANAGER at the first, STAFF at the second.
        member(manager, second, "STAFF");
        try (var lead = login(manager); var boss = login(owner); var own = login(worker)) {
            assertThat(ids(ok(get(lead, "/api/shifts?scope=" + second)).get("items"))).isEmpty();
            assertThat(ids(ok(get(lead, "/api/shifts")).get("items"))).containsExactly(Long.toString(here));
            refused(get(lead, "/api/shifts/" + there), 404, "NOT_FOUND");
            assertThat(ids(ok(get(boss, "/api/shifts?employeeId=" + worker)).get("items"))).containsExactly(Long.toString(here), Long.toString(there));
            assertThat(ids(ok(get(own, "/api/shifts")).get("items"))).containsExactly(Long.toString(here), Long.toString(there));
            assertThat(ids(ok(get(own, "/api/shifts?scope=" + second)).get("items"))).containsExactly(Long.toString(there));
            // A revoked membership closes that branch's records, even the employee's own.
            revoke(worker, second);
            assertThat(ids(ok(get(own, "/api/shifts")).get("items"))).containsExactly(Long.toString(here));
            refused(get(own, "/api/shifts/" + there), 404, "NOT_FOUND");
        }
    }

    // ---- the weekly schedule and the shape -------------------------------------------------------------------

    @Test void aWeekRunsFromMondayInSeoulAndAShiftBelongsToTheDayItStarts() throws Exception {
        try (var lead = login(manager)) {
            long sunday = registered(lead, shift(first, staff, "2026-11-08 22:00", "2026-11-09 06:00"));
            long monday = registered(lead, shift(first, colleague, "2026-11-09 08:00", "2026-11-09 17:00"));
            long record = attendance(colleague, first, monday, "2026-11-09 08:00", null);
            long loose = attendance(staff, first, null, "2026-11-09 07:30", "2026-11-09 08:00");
            var before = ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-08"));
            assertThat(before.get("weekStart").asString()).isEqualTo("2026-11-02");
            assertThat(before.get("weekEnd").asString()).isEqualTo("2026-11-08");
            assertThat(before.get("timeZone").asString()).isEqualTo("Asia/Seoul");
            assertThat(fields(before)).containsExactlyInAnyOrder("weekStart", "weekEnd", "timeZone", "shifts", "attendance", "asOf");
            assertThat(ids(before.get("shifts"))).containsExactly(Long.toString(sunday));
            assertThat(before.get("attendance").size()).isZero();
            for (String day : List.of("2026-11-09", "2026-11-12", "2026-11-15")) {
                var week = ok(get(lead, "/api/shifts/weekly?weekOf=" + day));
                assertThat(week.get("weekStart").asString()).isEqualTo("2026-11-09");
                assertThat(week.get("weekEnd").asString()).isEqualTo("2026-11-15");
                assertThat(ids(week.get("shifts"))).as("starts 11-08 23:00 UTC, Monday in Seoul").containsExactly(Long.toString(monday));
                assertThat(ids(week.get("attendance"))).containsExactly(Long.toString(loose), Long.toString(record));
            }
            var week = ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-09"));
            assertThat(week.at("/shifts/0/workDate").asString()).isEqualTo("2026-11-09");
            var working = week.get("attendance").get(1);
            assertThat(fields(working)).containsExactlyInAnyOrder("id", "version", "status", "branchId", "employee", "shiftId", "clockInAt",
                    "clockOutAt", "workDate", "breaks", "workedMinutes");
            assertThat(working.get("status").asString()).isEqualTo("근무 중");
            assertThat(working.get("shiftId").asString()).isEqualTo(Long.toString(monday));
            assertThat(working.get("workedMinutes").isNull()).isTrue();
            assertThat(week.at("/attendance/0/shiftId").isNull()).isTrue();
            assertThat(week.at("/attendance/0/workedMinutes").asLong()).isEqualTo(30);
            assertThat(week.at("/attendance/0/workDate").asString()).isEqualTo("2026-11-09");
            pause(record, "2026-11-09 12:00", null);
            assertThat(ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-09")).at("/attendance/1/status").asString()).isEqualTo("휴게 중");
            var today = ok(get(lead, "/api/shifts/weekly"));
            assertThat(java.time.LocalDate.parse(today.get("weekStart").asString()).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
            refused(get(lead, "/api/shifts/weekly?weekOf=2026-13-01"), 422, "VALIDATION_FAILED");
            refused(get(lead, "/api/shifts/weekly?weekOf=20261109"), 422, "VALIDATION_FAILED");
        }
    }

    @Test void theListFiltersByWorkDateInSeoulAndKeepsItsOrder() throws Exception {
        try (var lead = login(manager)) {
            long late = registered(lead, shift(first, staff, "2026-11-05 09:00", "2026-11-05 18:00"));
            long early = registered(lead, shift(first, colleague, "2026-11-05 00:30", "2026-11-05 08:00"));
            long night = registered(lead, shift(first, staff, "2026-11-04 22:00", "2026-11-05 06:00"));
            long same = registered(lead, shift(first, manager, "2026-11-05 09:00", "2026-11-05 12:00"));
            assertThat(ids(ok(get(lead, "/api/shifts?from=2026-11-05&to=2026-11-05")).get("items")))
                    .containsExactly(Long.toString(early), Long.toString(late), Long.toString(same));
            assertThat(ids(ok(get(lead, "/api/shifts?from=2026-11-04&to=2026-11-04")).get("items"))).containsExactly(Long.toString(night));
            assertThat(ids(ok(get(lead, "/api/shifts?to=2026-11-04")).get("items"))).containsExactly(Long.toString(night));
            var page = ok(get(lead, "/api/shifts?page=2&pageSize=2"));
            assertThat(ids(page.get("items"))).containsExactly(Long.toString(late), Long.toString(same));
            assertThat(page.at("/meta/total").asLong()).isEqualTo(4);
            refused(get(lead, "/api/shifts?from=2026-11-05&to=2026-11-04"), 422, "VALIDATION_FAILED");
            refused(get(lead, "/api/shifts?status=SCHEDULED"), 422, "VALIDATION_FAILED");
            refused(get(lead, "/api/shifts?mine=yes"), 422, "VALIDATION_FAILED");
            refused(get(lead, "/api/shifts?employeeId=0"), 422, "VALIDATION_FAILED");
            refused(get(lead, "/api/shifts?pageSize=101"), 422, "VALIDATION_FAILED");
        }
    }

    @Test void aShiftHasExactlyTheContractsFields() throws Exception {
        try (var lead = login(manager)) {
            var made = created(register(lead, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00", 60)));
            assertThat(fields(made)).isEqualTo(SHIFT_FIELDS);
            assertThat(made.get("status").asString()).isEqualTo("예정");
            assertThat(made.get("version").asLong()).isEqualTo(1);
            assertThat(made.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(made.at("/employee/id").asString()).isEqualTo(Long.toString(staff));
            assertThat(made.at("/employee/name").asString()).isEqualTo("첫 지점 직원");
            assertThat(made.at("/createdBy/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(made.get("startsAt").asString()).isEqualTo("2026-11-04T00:00:00Z");
            assertThat(made.get("endsAt").asString()).isEqualTo("2026-11-04T09:00:00Z");
            assertThat(made.get("workDate").asString()).isEqualTo("2026-11-04");
            assertThat(made.get("cancellation").isNull()).isTrue();
            assertThat(made.get("attendance").size()).isZero();
            assertThat(made.get("canEdit").asBoolean()).isTrue();
            long id = made.get("id").asLong();
            assertThat(ok(get(lead, "/api/shifts/" + id))).isEqualTo(made);
            assertThat(ok(get(lead, "/api/shifts")).get("items").get(0)).isEqualTo(made);
            assertThat(ok(get(lead, "/api/shifts/weekly?weekOf=2026-11-04")).get("shifts").get(0)).isEqualTo(made);
            refused(get(lead, "/api/shifts/999999999"), 404, "NOT_FOUND");
        }
    }
}
