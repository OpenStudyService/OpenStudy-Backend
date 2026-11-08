package com.openstudy.modules.workforce;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #109 over HTTP and the database: only the shift's own employee requests a swap and nothing changes until approval;
 * the shift branch's MANAGER decides, never on their own request; an approval checks the shift's version and employee,
 * the replacement's membership and free time, and changes employee and time together with a SWAPPED row; a
 * cancellation restores only an unchanged shift whose original employee is free, once, also under concurrent requests;
 * and a swapped shift is what the M8 conflict check sees.
 */
class SwapIntegrationTests extends ShiftIntegrationSupport {
    /** Ten days ahead in Seoul: the shifts have not started, so every swap command is possible. */
    static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(10);

    static String at(int plusDays, String time) { return DAY.plusDays(plusDays) + " " + time; }

    HttpResponse<String> ask(Session session, long shift, long replacement, String start, String end, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("replacementEmployeeId", Long.toString(replacement)); body.put("proposedStartsAt", at(start));
        body.put("proposedEndsAt", at(end)); body.put("reason", "개인 일정");
        return http(session.client(), "POST", "/api/shifts/" + shift + "/swaps", session.token(), body, key);
    }

    HttpResponse<String> ask(Session session, long shift, long replacement, String start, String end) throws Exception {
        return ask(session, shift, replacement, start, end, key());
    }

    JsonNode asked(Session session, long shift, long replacement, String start, String end) throws Exception {
        return created(ask(session, shift, replacement, start, end));
    }

    HttpResponse<String> command(Session session, long swap, String name, Map<String, Object> body) throws Exception {
        return http(session.client(), "POST", "/api/shift-swaps/" + swap + "/" + name, session.token(), body, null);
    }

    HttpResponse<String> approve(Session session, long swap, long version, long shiftVersion) throws Exception {
        return command(session, swap, "approve", Map.of("version", version, "shiftVersion", shiftVersion));
    }

    long shiftVersion(Session session, long shift) throws Exception { return ok(get(session, "/api/shifts/" + shift)).get("version").asLong(); }

    long employeeOf(long shift) { return count("SELECT employee_id FROM shifts WHERE id=?", shift); }

    long swapped(long shift) { return count("SELECT COUNT(*) FROM shift_events WHERE shift_id=? AND event_type='SWAPPED'", shift); }

    @Test void onlyTheShiftsOwnEmployeeRequestsAndNothingChangesBeforeApproval() throws Exception {
        try (var lead = login(manager); var member = login(staff); var peer = login(colleague)) {
            long shift = registered(lead, shift(first, staff, at(0, "09:00"), at(0, "18:00"), 60));
            refused(ask(lead, shift, colleague, at(0, "09:00"), at(0, "18:00")), 403, "FORBIDDEN");
            refused(ask(peer, shift, colleague, at(0, "09:00"), at(0, "18:00")), 404, "NOT_FOUND");
            refused(ask(member, shift, staff, at(0, "09:00"), at(0, "18:00")), 422, "INVALID_SHIFT_EMPLOYEE");
            refused(ask(member, shift, outsider, at(0, "09:00"), at(0, "18:00")), 422, "INVALID_SHIFT_EMPLOYEE");
            refused(ask(member, shift, colleague, at(0, "09:00"), at(0, "09:30")), 422, "SHIFT_BREAK_INVALID");

            var body = asked(member, shift, colleague, at(0, "10:00"), at(0, "19:00"));
            assertThat(body.get("status").asString()).isEqualTo("신청");
            assertThat(body.get("originalStartsAt").asString()).isEqualTo(seoul(at(0, "09:00")).toString());
            assertThat(body.get("canCancel").asBoolean()).isTrue();
            assertThat(employeeOf(shift)).isEqualTo(staff);
            assertThat(swapped(shift)).isZero();
            var pending = ask(member, shift, colleague, at(0, "09:00"), at(0, "18:00"));
            refused(pending, 409, "SWAP_PENDING");
            assertThat(node(pending).get("pendingSwapId").asString()).isEqualTo(body.get("id").asString());
            // The replacement sees the swap naming them; another staff member of the branch does not.
            assertThat(ok(get(peer, "/api/shift-swaps/" + body.get("id").asString())).at("/replacement/id").asString()).isEqualTo(Long.toString(colleague));

            long started = registered(lead, shift(first, staff, at(-20, "09:00"), at(-20, "18:00")));
            refused(ask(member, started, colleague, at(-20, "09:00"), at(-20, "18:00")), 409, "SHIFT_ALREADY_STARTED");
        }
    }

    @Test void theReplacementsTimeMustBeFreeAtRequestAndApproval() throws Exception {
        try (var lead = login(manager); var member = login(staff); var boss = login(owner)) {
            long shift = registered(lead, shift(first, staff, at(1, "09:00"), at(1, "18:00")));
            long busy = registered(lead, shift(first, colleague, at(1, "08:00"), at(1, "10:00")));
            var conflict = ask(member, shift, colleague, at(1, "09:00"), at(1, "18:00"));
            refused(conflict, 409, "SHIFT_CONFLICT");
            // A staff requester may not see a colleague's shift (M8-D02): only the overlapping time comes back.
            assertThat(node(conflict).get("conflicts").get(0).get("visible").asBoolean()).isFalse();
            assertThat(node(conflict).get("conflicts").get(0).get("shift").isNull()).isTrue();
            assertThat(node(conflict).get("conflicts").get(0).get("overlapEndsAt").asString()).isEqualTo(seoul(at(1, "10:00")).toString());
            assertThat(busy).isPositive();

            long swap = asked(member, shift, colleague, at(1, "10:00"), at(1, "18:00")).get("id").asLong();
            leave(colleague, DAY.plusDays(1).toString(), DAY.plusDays(1).toString(), "1.0", "APPROVED");
            var away = approve(boss, swap, 1, shiftVersion(lead, shift));
            refused(away, 409, "SHIFT_CONFLICT");
            assertThat(node(away).get("conflicts").get(0).get("kind").asString()).isEqualTo("휴가");
            assertThat(employeeOf(shift)).isEqualTo(staff);
        }
    }

    @Test void decisionsBelongToTheShiftBranchManagerAndNeverToTheRequester() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var peer = login(colleague);
                var boss = login(owner)) {
            long shift = registered(lead, shift(first, staff, at(2, "09:00"), at(2, "18:00")));
            long swap = asked(member, shift, colleague, at(2, "09:00"), at(2, "18:00")).get("id").asLong();
            long version = shiftVersion(lead, shift);
            refused(approve(peer, swap, 1, version), 403, "FORBIDDEN");
            refused(approve(member, swap, 1, version), 403, "FORBIDDEN");
            refused(approve(other, swap, 1, version), 404, "NOT_FOUND");
            refused(command(peer, swap, "cancel", Map.of("version", 1)), 403, "FORBIDDEN");

            long own = registered(boss, shift(first, manager, at(3, "09:00"), at(3, "18:00")));
            long mine = asked(lead, own, staff, at(3, "09:00"), at(3, "18:00")).get("id").asLong();
            refused(approve(lead, mine, 1, shiftVersion(lead, own)), 403, "SELF_DECISION_FORBIDDEN");
            refused(command(lead, mine, "reject", Map.of("version", 1)), 403, "SELF_DECISION_FORBIDDEN");
            assertThat(ok(approve(boss, mine, 1, shiftVersion(lead, own))).get("status").asString()).isEqualTo("승인");
            refused(command(lead, mine, "cancel-approval", Map.of("version", 2)), 403, "SELF_DECISION_FORBIDDEN");

            var rejected = ok(command(lead, swap, "reject", Map.of("version", 1)));
            assertThat(rejected.get("status").asString()).isEqualTo("반려");
            assertThat(rejected.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(employeeOf(shift)).isEqualTo(staff);
            assertThat(swapped(shift)).isZero();
            refused(approve(lead, swap, 2, version), 409, "INVALID_STATE");
        }
    }

    @Test void anApprovalRefusesAShiftThatChangedOrAReplacementWhoLeft() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long shift = registered(lead, shift(first, staff, at(4, "09:00"), at(4, "18:00")));
            long swap = asked(member, shift, colleague, at(4, "09:00"), at(4, "18:00")).get("id").asLong();
            long version = shiftVersion(lead, shift);
            var stale = approve(lead, swap, 1, version + 1);
            refused(stale, 409, "SHIFT_VERSION_CONFLICT");
            assertThat(node(stale).get("shiftId").asString()).isEqualTo(Long.toString(shift));
            assertThat(node(stale).get("latestVersion").asLong()).isEqualTo(version);
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/shifts/" + shift);

            // Changed after the request: even the manager's current version does not approve the request made before it.
            ok(edit(lead, shift, change(version, "endsAt", at(at(4, "17:00")))));
            refused(approve(lead, swap, 1, shiftVersion(lead, shift)), 409, "SHIFT_VERSION_CONFLICT");
            ok(command(member, swap, "cancel", Map.of("version", 1)));

            long again = asked(member, shift, colleague, at(4, "09:00"), at(4, "17:00")).get("id").asLong();
            revoke(colleague, first);
            refused(approve(lead, again, 1, shiftVersion(lead, shift)), 422, "INVALID_SHIFT_EMPLOYEE");
            assertThat(employeeOf(shift)).isEqualTo(staff);
        }
    }

    @Test void anApprovalChangesEmployeeAndTimeTogetherAndM8SeesTheSwappedShift() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long shift = registered(lead, shift(first, staff, at(5, "09:00"), at(5, "18:00")));
            long swap = asked(member, shift, colleague, at(5, "10:00"), at(5, "19:00")).get("id").asLong();
            var body = ok(approve(lead, swap, 1, shiftVersion(lead, shift)));
            assertThat(body.get("status").asString()).isEqualTo("승인");
            assertThat(body.get("originalStartsAt").asString()).isEqualTo(seoul(at(5, "09:00")).toString());
            var moved = ok(get(lead, "/api/shifts/" + shift));
            assertThat(moved.at("/employee/id").asString()).isEqualTo(Long.toString(colleague));
            assertThat(moved.get("startsAt").asString()).isEqualTo(seoul(at(5, "10:00")).toString());
            assertThat(swapped(shift)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.swapId')) FROM shift_events WHERE shift_id=? AND event_type='SWAPPED'",
                    String.class, shift)).isEqualTo(Long.toString(swap));
            // M8 now checks the replacement's time and frees the original employee's.
            refused(register(lead, shift(first, colleague, at(5, "18:00"), at(5, "20:00"))), 409, "SHIFT_CONFLICT");
            created(register(lead, shift(first, staff, at(5, "10:00"), at(5, "12:00"))));
        }
    }

    @Test void aCancelledApprovalRestoresOnlyAnUnchangedShiftWhoseEmployeeIsFree() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long shift = registered(lead, shift(first, staff, at(6, "09:00"), at(6, "18:00")));
            long swap = asked(member, shift, colleague, at(6, "09:00"), at(6, "18:00")).get("id").asLong();
            ok(approve(lead, swap, 1, shiftVersion(lead, shift)));
            long approved = shiftVersion(lead, shift);

            long blocking = registered(lead, shift(first, staff, at(6, "08:00"), at(6, "10:00")));
            refused(command(lead, swap, "cancel-approval", Map.of("version", 2)), 409, "SHIFT_CONFLICT");
            ok(cancel(lead, blocking, 1));

            ok(edit(lead, shift, change(approved, "endsAt", at(at(6, "17:00")))));
            var changed = command(lead, swap, "cancel-approval", Map.of("version", 2));
            refused(changed, 409, "SWAP_RESTORE_CONFLICT");
            assertThat(node(changed).get("approvedVersion").asLong()).isEqualTo(approved);
            assertThat(node(changed).get("latestVersion").asLong()).isEqualTo(approved + 1);
            assertThat(employeeOf(shift)).isEqualTo(colleague);
        }
    }

    @Test void simultaneousCancellationsRestoreTheShiftOnce() throws Exception {
        try (var lead = login(manager); var boss = login(owner); var member = login(staff)) {
            long shift = registered(lead, shift(first, staff, at(7, "09:00"), at(7, "18:00")));
            long swap = asked(member, shift, colleague, at(7, "11:00"), at(7, "20:00")).get("id").asLong();
            ok(approve(lead, swap, 1, shiftVersion(lead, shift)));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 4; index++) {
                var session = index % 2 == 0 ? lead : boss;
                calls.add(() -> command(session, swap, "cancel-approval", Map.of("version", 2)));
            }
            var responses = race(calls);
            assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
            for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "INVALID_STATE");
            var restored = ok(get(lead, "/api/shifts/" + shift));
            assertThat(restored.at("/employee/id").asString()).isEqualTo(Long.toString(staff));
            assertThat(restored.get("startsAt").asString()).isEqualTo(seoul(at(7, "09:00")).toString());
            assertThat(swapped(shift)).isEqualTo(2);
            var view = ok(get(lead, "/api/shift-swaps/" + swap));
            assertThat(view.at("/cancellation/kind").asString()).isEqualTo("승인 취소");
            assertThat(view.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
        }
    }

    @Test void listsShowAManagedBranchAndTheSwapsOneIsPartOf() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var peer = login(colleague);
                var person = login(worker)) {
            long shift = registered(lead, shift(first, staff, at(8, "09:00"), at(8, "18:00")));
            String swap = asked(member, shift, colleague, at(8, "09:00"), at(8, "18:00")).get("id").asString();
            assertThat(ids(ok(get(lead, "/api/shift-swaps")).get("items"))).contains(swap);
            assertThat(ids(ok(get(member, "/api/shift-swaps?mine=true")).get("items"))).containsExactly(swap);
            assertThat(ids(ok(get(peer, "/api/shift-swaps")).get("items"))).containsExactly(swap);
            assertThat(ids(ok(get(person, "/api/shift-swaps")).get("items"))).isEmpty();
            assertThat(ids(ok(get(other, "/api/shift-swaps")).get("items"))).isEmpty();
            assertThat(ids(ok(get(lead, "/api/shift-swaps?status=" + java.net.URLEncoder.encode("승인", java.nio.charset.StandardCharsets.UTF_8))).get("items"))).isEmpty();
        }
    }
}
