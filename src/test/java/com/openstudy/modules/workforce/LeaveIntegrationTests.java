package com.openstudy.modules.workforce;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #108 over HTTP and the database: grants by the origin branch's MANAGER, requests at the requester's active branch,
 * decisions by the request branch's MANAGER but never on one's own request, the M12-D03 shift check, the M12-D06
 * allocation order, one use and one restore per request (M12-D07), the ledger matching every balance, and approved
 * leave reaching the M8 shift check.
 */
class LeaveIntegrationTests extends ShiftIntegrationSupport {
    /** Next year: every day of it is in the future, so approvals can be cancelled and nothing depends on today. */
    static final int YEAR = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear() + 1;

    static String day(String monthDay) { return YEAR + "-" + monthDay; }

    // ---- helpers --------------------------------------------------------------------------------------------

    HttpResponse<String> grant(Session session, long employee, long branch, String from, String through, Object days, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("employeeId", Long.toString(employee)); body.put("originBranchId", Long.toString(branch));
        body.put("grantYear", Integer.parseInt(from.substring(0, 4))); body.put("validFrom", from); body.put("validThrough", through);
        body.put("grantedDays", days); body.put("reason", "연차 부여");
        return http(session.client(), "POST", "/api/leave-grants", session.token(), body, key);
    }

    long granted(Session session, long employee, long branch, String from, String through, Object days) throws Exception {
        return created(grant(session, employee, branch, from, through, days, key())).get("id").asLong();
    }

    HttpResponse<String> ask(Session session, long branch, String startsOn, String endsOn, Object days, String key) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("startsOn", startsOn); body.put("endsOn", endsOn);
        body.put("requestedDays", days); body.put("reason", "개인 사유");
        return http(session.client(), "POST", "/api/leave-requests", session.token(), body, key);
    }

    HttpResponse<String> ask(Session session, long branch, String startsOn, String endsOn, Object days) throws Exception {
        return ask(session, branch, startsOn, endsOn, days, key());
    }

    JsonNode asked(Session session, long branch, String startsOn, String endsOn, Object days) throws Exception {
        return created(ask(session, branch, startsOn, endsOn, days));
    }

    HttpResponse<String> command(Session session, long request, String name, long version) throws Exception {
        return http(session.client(), "POST", "/api/leave-requests/" + request + "/" + name, session.token(), Map.of("version", version), null);
    }

    JsonNode request(Session session, long id) throws Exception { return ok(get(session, "/api/leave-requests/" + id)); }

    BigDecimal remaining(long grant) { return jdbc.queryForObject("SELECT remaining_days FROM leave_grants WHERE id=?", BigDecimal.class, grant); }

    /** Every grant of the person equals the sum of its ledger (M12-D07). */
    void ledgerMatches(long person) {
        var rows = jdbc.queryForList("SELECT g.id, g.remaining_days, (SELECT COALESCE(SUM(e.delta_days),0) FROM leave_balance_events e "
                + "WHERE e.leave_grant_id=g.id) AS total FROM leave_grants g WHERE g.employee_id=?", person);
        assertThat(rows).isNotEmpty();
        for (var row : rows) assertThat((BigDecimal) row.get("remaining_days")).as(row.toString()).isEqualByComparingTo((BigDecimal) row.get("total"));
    }

    long events(long request, String type) {
        return count("SELECT COUNT(*) FROM leave_balance_events WHERE leave_request_id=? AND event_type=?", request, type);
    }

    // ---- grants ---------------------------------------------------------------------------------------------

    @Test void onlyTheOriginBranchManagerGrantsHalfDayStepsWithPeriodAndReasonRecorded() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff)) {
            refused(grant(member, staff, first, day("01-01"), day("12-31"), 15, key()), 403, "FORBIDDEN");
            refused(grant(other, staff, first, day("01-01"), day("12-31"), 15, key()), 403, "FORBIDDEN");
            refused(grant(lead, staff, first, day("01-01"), day("12-31"), 0.3, key()), 422, "LEAVE_GRANT_INVALID");
            refused(grant(lead, staff, first, day("01-01"), day("12-31"), 0, key()), 422, "LEAVE_GRANT_INVALID");
            refused(grant(lead, staff, first, day("12-31"), day("01-01"), 15, key()), 422, "LEAVE_GRANT_INVALID");
            refused(grant(lead, outsider, first, day("01-01"), day("12-31"), 15, key()), 422, "INVALID_LEAVE_EMPLOYEE");
            refused(grant(lead, staff, first, day("01-01"), day("12-31"), "15", key()), 422, "VALIDATION_FAILED");

            String key = key();
            var body = created(grant(lead, staff, first, day("01-01"), day("12-31"), 15.5, key));
            assertThat(body.get("grantedDays").decimalValue()).isEqualByComparingTo("15.5");
            assertThat(body.get("remainingDays").decimalValue()).isEqualByComparingTo("15.5");
            assertThat(body.get("validFrom").asString()).isEqualTo(day("01-01"));
            assertThat(body.get("reason").asString()).isEqualTo("연차 부여");
            // Same key, same input: the first result again and no second grant.
            assertThat(created(grant(lead, staff, first, day("01-01"), day("12-31"), 15.5, key)).get("id").asString()).isEqualTo(body.get("id").asString());
            refused(grant(lead, staff, first, day("01-01"), day("12-31"), 14, key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM leave_grants WHERE employee_id=?", staff)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM leave_balance_events WHERE event_type='GRANT' AND event_key=?", "grant:" + body.get("id").asString())).isEqualTo(1);
            ledgerMatches(staff);

            stop(first);
            refused(grant(lead, staff, first, day("01-01"), day("12-31"), 1, key()), 409, "BRANCH_NOT_ACTIVE");
        }
    }

    @Test void anAdjustmentMovesOnlyTheRemainingDaysAndNeverBelowZero() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long grant = granted(lead, staff, first, day("01-01"), day("12-31"), 5);
            var adjust = new LinkedHashMap<String, Object>(Map.of("version", 1, "deltaDays", -6, "reason", "보정"));
            var path = "/api/leave-grants/" + grant + "/adjust";
            refused(http(member.client(), "POST", path, member.token(), adjust, null), 403, "FORBIDDEN");
            var refused = http(lead.client(), "POST", path, lead.token(), adjust, null);
            refused(refused, 409, "LEAVE_BALANCE_INSUFFICIENT");
            assertThat(node(refused).get("availableDays").decimalValue()).isEqualByComparingTo("5");
            adjust.put("deltaDays", -1.5);
            var body = ok(http(lead.client(), "POST", path, lead.token(), adjust, null));
            assertThat(body.get("remainingDays").decimalValue()).isEqualByComparingTo("3.5");
            assertThat(body.get("grantedDays").decimalValue()).isEqualByComparingTo("5");
            assertThat(body.get("events").get(1).get("type").asString()).isEqualTo("보정");
            refused(http(lead.client(), "POST", path, lead.token(), adjust, null), 409, "VERSION_CONFLICT");
            ledgerMatches(staff);
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------

    @Test void aRequestIsAtTheRequestersActiveBranchInHalfDaysWithinOneYearAndTakesNothingYet() throws Exception {
        try (var lead = login(manager); var member = login(staff); var boss = login(owner)) {
            long grant = granted(lead, staff, first, day("01-01"), day("12-31"), 3);
            refused(ask(member, second, day("03-02"), day("03-02"), 1), 403, "FORBIDDEN");
            refused(ask(boss, first, day("03-02"), day("03-02"), 1), 403, "FORBIDDEN");
            refused(ask(member, first, day("03-02"), day("03-04"), 3.5), 422, "LEAVE_PERIOD_INVALID");
            refused(ask(member, first, day("03-02"), day("03-02"), 0.3), 422, "LEAVE_PERIOD_INVALID");
            refused(ask(member, first, day("03-04"), day("03-02"), 1), 422, "LEAVE_PERIOD_INVALID");
            refused(ask(member, first, day("12-31"), (YEAR + 1) + "-01-01", 2), 422, "LEAVE_PERIOD_INVALID");
            refused(ask(member, first, day("03-02"), day("03-05"), 4), 409, "LEAVE_BALANCE_INSUFFICIENT");

            var half = asked(member, first, day("03-02"), day("03-02"), 0.5);
            assertThat(half.get("status").asString()).isEqualTo("신청");
            assertThat(half.get("requestedDays").decimalValue()).isEqualByComparingTo("0.5");
            asked(member, first, day("03-03"), day("03-05"), 2.5);
            assertThat(remaining(grant)).isEqualByComparingTo("3");
            var overlap = ask(member, first, day("03-05"), day("03-06"), 1);
            refused(overlap, 409, "LEAVE_OVERLAP");
            assertThat(node(overlap).get("overlappingRequestIds").size()).isEqualTo(1);
        }
    }

    @Test void decisionsBelongToTheRequestBranchManagerAndNeverToTheRequester() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var peer = login(colleague);
                var boss = login(owner)) {
            granted(lead, staff, first, day("01-01"), day("12-31"), 5);
            granted(boss, manager, first, day("01-01"), day("12-31"), 5);
            long id = asked(member, first, day("04-01"), day("04-01"), 1).get("id").asLong();
            refused(command(peer, id, "approve", 1), 404, "NOT_FOUND");
            refused(command(other, id, "approve", 1), 404, "NOT_FOUND");
            refused(command(member, id, "approve", 1), 403, "FORBIDDEN");
            assertThat(request(lead, id).get("canApprove").asBoolean()).isTrue();

            long own = asked(lead, first, day("04-02"), day("04-02"), 1).get("id").asLong();
            refused(command(lead, own, "approve", 1), 403, "SELF_DECISION_FORBIDDEN");
            refused(command(lead, own, "reject", 1), 403, "SELF_DECISION_FORBIDDEN");
            assertThat(request(lead, own).get("canApprove").asBoolean()).isFalse();
            assertThat(ok(command(boss, own, "approve", 1)).get("status").asString()).isEqualTo("승인");
            refused(command(lead, own, "cancel-approval", 2), 403, "SELF_DECISION_FORBIDDEN");

            // Lists: the manager sees the branch, a staff member only their own, another branch's manager nothing here.
            assertThat(ids(ok(get(lead, "/api/leave-requests")).get("items"))).contains(Long.toString(id), Long.toString(own));
            assertThat(ids(ok(get(member, "/api/leave-requests")).get("items"))).containsExactly(Long.toString(id));
            assertThat(ids(ok(get(peer, "/api/leave-requests")).get("items"))).isEmpty();
            assertThat(ids(ok(get(other, "/api/leave-requests")).get("items"))).isEmpty();
            assertThat(ids(ok(get(member, "/api/leave-requests?employeeId=" + manager)).get("items"))).isEmpty();
        }
    }

    @Test void aRejectionAndAWithdrawalLeaveTheBalanceAlone() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            long grant = granted(lead, staff, first, day("01-01"), day("12-31"), 2);
            long rejected = asked(member, first, day("05-04"), day("05-04"), 1).get("id").asLong();
            var body = ok(command(lead, rejected, "reject", 1));
            assertThat(body.get("status").asString()).isEqualTo("반려");
            assertThat(body.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
            refused(command(lead, rejected, "approve", 2), 409, "INVALID_STATE");
            long withdrawn = asked(member, first, day("05-04"), day("05-04"), 1).get("id").asLong();
            refused(command(lead, withdrawn, "cancel", 1), 403, "FORBIDDEN");
            body = ok(command(member, withdrawn, "cancel", 1));
            assertThat(body.at("/cancellation/kind").asString()).isEqualTo("신청 취소");
            assertThat(body.get("decision").isNull()).isTrue();
            var again = command(member, withdrawn, "cancel", 2);
            refused(again, 409, "INVALID_STATE");
            assertThat(node(again).get("reloadUrl").asString()).isEqualTo("/api/leave-requests/" + withdrawn);
            assertThat(remaining(grant)).isEqualByComparingTo("2");
            assertThat(count("SELECT COUNT(*) FROM leave_allocations a JOIN leave_requests r ON r.id=a.leave_request_id WHERE r.employee_id=?", staff)).isZero();
        }
    }

    @Test void anApprovalTakesFromTheEarliestExpiringGrantsAndACancellationRestoresThemOnce() throws Exception {
        try (var lead = login(manager); var member = login(staff); var boss = login(owner)) {
            long later = granted(lead, staff, first, day("01-01"), (YEAR + 1) + "-03-31", 5);
            long sooner = granted(boss, staff, first, day("01-01"), day("12-31"), 1);
            long id = asked(member, first, day("06-01"), day("06-02"), 2).get("id").asLong();
            var body = ok(command(lead, id, "approve", 1));
            assertThat(body.get("status").asString()).isEqualTo("승인");
            assertThat(body.get("allocations").get(0).get("grantId").asString()).isEqualTo(Long.toString(sooner));
            assertThat(body.get("allocations").get(0).get("days").decimalValue()).isEqualByComparingTo("1");
            assertThat(body.get("allocations").get(1).get("grantId").asString()).isEqualTo(Long.toString(later));
            assertThat(remaining(sooner)).isEqualByComparingTo("0");
            assertThat(remaining(later)).isEqualByComparingTo("4");
            assertThat(events(id, "USE")).isEqualTo(2);
            refused(command(lead, id, "approve", 2), 409, "INVALID_STATE");
            assertThat(events(id, "USE")).isEqualTo(2);

            var cancelled = ok(command(lead, id, "cancel-approval", 2));
            assertThat(cancelled.get("status").asString()).isEqualTo("취소");
            assertThat(cancelled.at("/cancellation/kind").asString()).isEqualTo("승인 취소");
            assertThat(cancelled.at("/decision/by/id").asString()).isEqualTo(Long.toString(manager));
            assertThat(remaining(sooner)).isEqualByComparingTo("1");
            assertThat(remaining(later)).isEqualByComparingTo("5");
            refused(command(lead, id, "cancel-approval", 3), 409, "INVALID_STATE");
            assertThat(events(id, "RESTORE")).isEqualTo(2);
            ledgerMatches(staff);

            var balance = ok(get(member, "/api/me/leave-balance"));
            assertThat(balance.get("totalRemainingDays").decimalValue()).isEqualByComparingTo("6");
            assertThat(balance.get("grants").get(0).get("grantId").asString()).isEqualTo(Long.toString(sooner));
        }
    }

    @Test void anApprovalIsRefusedForShiftsAnotherApprovedLeaveOrTooFewDaysWithTheTargets() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var person = login(worker)) {
            long grant = granted(lead, worker, first, day("01-01"), day("12-31"), 3);
            long id = asked(person, first, day("07-01"), day("07-02"), 2).get("id").asLong();
            long here = registered(lead, shift(first, worker, day("07-01") + " 09:00", day("07-01") + " 13:00"));
            registered(other, shift(second, worker, day("07-02") + " 14:00", day("07-02") + " 18:00"));
            var conflict = command(lead, id, "approve", 1);
            refused(conflict, 409, "LEAVE_SHIFT_CONFLICT");
            var conflicts = node(conflict).get("conflicts");
            assertThat(conflicts.size()).isEqualTo(2);
            assertThat(conflicts.get(0).at("/shift/id").asString()).isEqualTo(Long.toString(here));
            assertThat(conflicts.get(1).get("visible").asBoolean()).isFalse();
            assertThat(conflicts.get(1).get("label").asString()).isEqualTo("다른 지점 근무");
            assertThat(conflicts.get(1).get("shift").isNull()).isTrue();
            assertThat(remaining(grant)).isEqualByComparingTo("3");

            jdbc.update("UPDATE shifts SET status='CANCELLED' WHERE employee_id=?", worker);
            leave(worker, day("07-02"), day("07-02"), "1.0", "APPROVED");
            refused(command(lead, id, "approve", 1), 409, "LEAVE_OVERLAP");
            jdbc.update("UPDATE leave_requests SET status='CANCELLED' WHERE employee_id=? AND id<>?", worker, id);

            jdbc.update("UPDATE leave_grants SET remaining_days=1.5 WHERE id=?", grant);
            var shortOf = command(lead, id, "approve", 1);
            refused(shortOf, 409, "LEAVE_BALANCE_INSUFFICIENT");
            assertThat(node(shortOf).get("requiredDays").decimalValue()).isEqualByComparingTo("2");
            assertThat(node(shortOf).get("availableDays").decimalValue()).isEqualByComparingTo("1.5");
            assertThat(count("SELECT COUNT(*) FROM leave_allocations WHERE leave_request_id=?", id)).isZero();
        }
    }

    @Test void anApprovedLeaveBlocksAShiftRegistrationOfM8() throws Exception {
        try (var lead = login(manager); var member = login(staff)) {
            granted(lead, staff, first, day("01-01"), day("12-31"), 1);
            long id = asked(member, first, day("08-03"), day("08-03"), 1).get("id").asLong();
            ok(command(lead, id, "approve", 1));
            var refused = register(lead, shift(first, staff, day("08-03") + " 09:00", day("08-03") + " 18:00"));
            refused(refused, 409, "SHIFT_CONFLICT");
            assertThat(node(refused).get("conflicts").get(0).get("kind").asString()).isEqualTo("휴가");
            ok(command(lead, id, "cancel-approval", 2));
            created(register(lead, shift(first, staff, day("08-03") + " 09:00", day("08-03") + " 18:00")));
        }
    }

    @Test void anApprovalIsCancelledOnlyBeforeItsFirstDay() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        try (var lead = login(manager); var member = login(staff)) {
            granted(lead, staff, first, today.getYear() + "-01-01", today.getYear() + "-12-31", 1);
            long id = asked(member, first, today.toString(), today.toString(), 1).get("id").asLong();
            ok(command(lead, id, "approve", 1));
            assertThat(request(lead, id).get("canCancelApproval").asBoolean()).isFalse();
            refused(command(lead, id, "cancel-approval", 2), 409, "LEAVE_ALREADY_STARTED");
            assertThat(events(id, "RESTORE")).isZero();
        }
    }

    @Test void balancesAreReadByTheEmployeeAndTheManagersOfTheirBranchesOnly() throws Exception {
        try (var lead = login(manager); var other = login(secondManager); var member = login(staff); var boss = login(owner)) {
            granted(lead, staff, first, day("01-01"), day("12-31"), 2);
            assertThat(ok(get(lead, "/api/employees/" + staff + "/leave-balance")).get("totalRemainingDays").decimalValue()).isEqualByComparingTo("2");
            assertThat(ok(get(boss, "/api/employees/" + staff + "/leave-balance")).get("grants").size()).isEqualTo(1);
            refused(get(other, "/api/employees/" + staff + "/leave-balance"), 404, "NOT_FOUND");
            refused(get(member, "/api/employees/" + colleague + "/leave-balance"), 404, "NOT_FOUND");
            assertThat(ok(get(member, "/api/me/leave-balance")).at("/employee/id").asString()).isEqualTo(Long.toString(staff));
            assertThat(ids(ok(get(member, "/api/leave-grants")).get("items"))).hasSize(1);
            assertThat(ids(ok(get(other, "/api/leave-grants")).get("items"))).isEmpty();
        }
    }

    // ---- concurrency ----------------------------------------------------------------------------------------

    @Test void simultaneousApprovalsNeverTakeMoreThanTheGrantHolds() throws Exception {
        try (var lead = login(manager); var boss = login(owner); var member = login(staff)) {
            long grant = granted(lead, staff, first, day("01-01"), day("12-31"), 2);
            long one = asked(member, first, day("09-01"), day("09-02"), 2).get("id").asLong();
            long two = asked(member, first, day("09-07"), day("09-08"), 2).get("id").asLong();
            var responses = race(List.<Callable<HttpResponse<String>>>of(() -> command(lead, one, "approve", 1), () -> command(boss, two, "approve", 1)));
            assertThat(statuses(responses)).containsExactlyInAnyOrder(200, 409);
            for (var response : responses) if (response.statusCode() == 409) refused(response, 409, "LEAVE_BALANCE_INSUFFICIENT");
            assertThat(remaining(grant)).isEqualByComparingTo("0");
            ledgerMatches(staff);
        }
    }

    @Test void simultaneousApprovalCancellationsRestoreOnce() throws Exception {
        try (var lead = login(manager); var boss = login(owner); var member = login(staff)) {
            long grant = granted(lead, staff, first, day("01-01"), day("12-31"), 3);
            long id = asked(member, first, day("10-05"), day("10-06"), 2).get("id").asLong();
            ok(command(lead, id, "approve", 1));
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 4; index++) {
                var session = index % 2 == 0 ? lead : boss;
                calls.add(() -> command(session, id, "cancel-approval", 2));
            }
            var responses = race(calls);
            assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
            for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "INVALID_STATE");
            assertThat(remaining(grant)).isEqualByComparingTo("3");
            assertThat(events(id, "RESTORE")).isEqualTo(1);
            ledgerMatches(staff);
        }
    }
}
