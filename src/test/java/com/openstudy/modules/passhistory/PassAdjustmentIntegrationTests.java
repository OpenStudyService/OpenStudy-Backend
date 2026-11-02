package com.openstudy.modules.passhistory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #64: HTTP and MySQL behaviour of the manual pass adjustment (seat-usage-api.md "이용권 수동 조정"). */
@MySqlIntegrationTest
class PassAdjustmentIntegrationTests extends PassUsageFixture {

    // ---- what one adjustment leaves behind ------------------------------------------------------------------

    @Test void aTimePassAdjustmentLeavesTheLedgerRowAuditAndEventAtThePurchaseBranch() throws Exception {
        long customer = customer(first, "조정 고객");
        long pass = pass(customer, first, time(600));
        long before = version(pass);
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingMinutes", 480L));
            assertThat(result.get("remainingMinutes").asLong()).isEqualTo(480);
            assertThat(result.get("version").asLong()).isEqualTo(before + 1);
            assertThat(result.get("status").asString()).isEqualTo("활성");
            assertThat(result.get("used").asBoolean()).isFalse();
            // The answer is the same pass a later read returns.
            assertThat(ok(s, "/api/passes/" + pass).get("version").asLong()).isEqualTo(before + 1);
        }
        var row = jdbc.queryForMap("SELECT * FROM pass_balance_events WHERE pass_id=?", pass);
        assertThat(row.get("kind")).isEqualTo("ADJUST");
        assertThat(((Number) row.get("delta_minutes")).longValue()).isEqualTo(-120);
        assertThat(((Number) row.get("delta_krw")).longValue()).isZero();
        assertThat(((Number) row.get("actor_employee_id")).longValue()).isEqualTo(payer);
        assertThat(row.get("reason")).isEqualTo("현장 보정");
        assertThat((String) row.get("event_key")).startsWith("ADJ-").hasSize(68);
        assertThat(row.get("seat_session_id")).isNull();
        var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE entity_type='passes' AND entity_id=? AND action='ADJUSTED'", pass);
        assertThat(((Number) audit.get("actor_employee_id")).longValue()).isEqualTo(payer);
        assertThat(((Number) audit.get("branch_id")).longValue()).isEqualTo(first);
        assertThat(audit.get("reason")).isEqualTo("현장 보정");
        var was = json.readTree(String.valueOf(audit.get("before_json")));
        var is = json.readTree(String.valueOf(audit.get("after_json")));
        assertThat(was.get("remainingMinutes").asLong()).isEqualTo(600);
        assertThat(is.get("remainingMinutes").asLong()).isEqualTo(480);
        assertThat(was.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(audit.get("occurred_at")).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='passes' AND entity_id=?", first, pass))
                .isEqualTo(2); // issued, adjusted
    }

    @Test void aStoredValuePassAdjustsItsCreditAndLeavesMinutesAlone() throws Exception {
        long pass = pass(customer(first, "정액 고객"), first, stored(10_000, 1_000));
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingCreditKrw", 12_500L));
            assertThat(result.get("remainingCreditKrw").asLong()).isEqualTo(12_500);
            assertThat(result.get("remainingMinutes").isNull()).isTrue();
        }
        var row = jdbc.queryForMap("SELECT delta_minutes, delta_krw FROM pass_balance_events WHERE pass_id=?", pass);
        assertThat(((Number) row.get("delta_krw")).longValue()).isEqualTo(2_500);
        assertThat(((Number) row.get("delta_minutes")).longValue()).isZero();
    }

    @Test void aPeriodPassExtendsItsExpiryWithAZeroDeltaRowAndKeepsTheOldValueInTheAudit() throws Exception {
        long pass = pass(customer(first, "기간 고객"), first, period(30));
        Instant old = Instant.parse(text("SELECT DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%sZ') FROM passes WHERE id=?", pass));
        Instant later = old.plus(10, ChronoUnit.DAYS);
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "expiresAt", later.toString()));
            assertThat(Instant.parse(result.get("expiresAt").asString())).isEqualTo(later);
            assertThat(result.get("remainingMinutes").isNull()).isTrue();
            assertThat(result.get("status").asString()).isEqualTo("활성");
        }
        var row = jdbc.queryForMap("SELECT delta_minutes, delta_krw FROM pass_balance_events WHERE pass_id=?", pass);
        assertThat(((Number) row.get("delta_krw")).longValue()).isZero();
        assertThat(((Number) row.get("delta_minutes")).longValue()).isZero();
        var audit = jdbc.queryForMap("SELECT before_json, after_json FROM audit_events WHERE entity_type='passes' AND entity_id=? AND action='ADJUSTED'", pass);
        assertThat(Instant.parse(json.readTree(String.valueOf(audit.get("before_json"))).get("expiresAt").asString())).isEqualTo(old);
        assertThat(Instant.parse(json.readTree(String.valueOf(audit.get("after_json"))).get("expiresAt").asString())).isEqualTo(later);
    }

    @Test void balanceAndExpiryChangeTogetherInOneRow() throws Exception {
        long pass = pass(customer(first, "동시 조정"), first, time(100, 10L));
        Instant expiry = TEN.plus(20, ChronoUnit.DAYS);
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingMinutes", 150L, "expiresAt", expiry.toString()));
            assertThat(result.get("remainingMinutes").asLong()).isEqualTo(150);
            assertThat(Instant.parse(result.get("expiresAt").asString())).isEqualTo(expiry);
        }
        assertThat(adjustments(pass)).isEqualTo(1);
        assertThat(adjustAudits(pass)).isEqualTo(1);
    }

    // ---- who may adjust -------------------------------------------------------------------------------------

    @Test void onlyThePurchaseBranchsPaymentsWriteCounts() throws Exception {
        long customer = customer(first, "권한 고객");
        long pass = pass(customer, first, time(600));
        // Seeing the pass is not enough.
        try (var s = login(clerk)) { refused(adjust(s, pass, change(pass, "remainingMinutes", 500L), key()), 403, "FORBIDDEN"); }
        // payments:write elsewhere is nothing here: the pass does not exist for that employee.
        try (var s = login(foreign)) { refused(adjust(s, pass, change(pass, "remainingMinutes", 500L), key()), 404, "NOT_FOUND"); }
        // A manager's role does not replace the grant.
        jdbc.update("UPDATE branch_memberships SET role='MANAGER' WHERE employee_id=? AND branch_id=?", clerk, first);
        try (var s = login(clerk)) { refused(adjust(s, pass, change(pass, "remainingMinutes", 500L), key()), 403, "FORBIDDEN"); }
        assertThat(minutes(pass)).isEqualTo(600);
        assertThat(adjustments(pass)).isZero();
        assertThat(adjustAudits(pass)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE operation='passes:adjust' AND scope_key=?", Long.toString(pass))).isZero();
        try (var s = login(owner)) { adjusted(s, pass, change(pass, "remainingMinutes", 500L)); }
        try (var s = login(payer)) { adjusted(s, pass, change(pass, "remainingMinutes", 450L)); }
        assertThat(adjustments(pass)).isEqualTo(2);
    }

    @Test void theBranchWhereThePassIsBeingUsedHasNoSay() throws Exception {
        long customer = customer(first, "타 지점 이용");
        long pass = pass(customer, first, time(600));
        try (var other = login(foreign)) {
            entered(other, seat(second), customer, pass);
            refused(adjust(other, pass, change(pass, "remainingMinutes", 10L), key()), 404, "NOT_FOUND");
        }
        assertThat(minutes(pass)).isEqualTo(600);
    }

    @Test void aStoppedPurchaseBranchCanStillCorrectItsPasses() throws Exception {
        long pass = pass(customer(first, "중지 지점 조정"), first, time(600));
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var s = login(payer)) { adjusted(s, pass, change(pass, "remainingMinutes", 500L)); }
        assertThat(minutes(pass)).isEqualTo(500);
    }

    @Test void aMissingPassAndARevokedMembershipAreTheSameNotFound() throws Exception {
        long pass = pass(customer(first, "회수 고객"), first, time(600));
        try (var s = login(payer)) {
            refused(adjust(s, 999_999_999L, Map.of("version", 1, "reason", "없음", "remainingMinutes", 1), key()), 404, "NOT_FOUND");
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", payer, first);
            refused(adjust(s, pass, change(pass, "remainingMinutes", 500L), key()), 404, "NOT_FOUND");
        }
    }

    @Test void theRequestScopeNarrowsThePurchaseBranch() throws Exception {
        long pass = pass(customer(first, "범위 고객"), first, time(600));
        grant(dual, first, "payments:write");
        try (var s = login(dual)) {
            var wrongBranch = http(s.client(), "POST", "/api/passes/" + pass + "/adjustments?scope=" + second, s.token(), change(pass, "remainingMinutes", 5L), key());
            refused(wrongBranch, 404, "NOT_FOUND");
            long unrelated = branch("상관없는 지점");
            var inaccessible = http(s.client(), "POST", "/api/passes/" + pass + "/adjustments?scope=" + unrelated, s.token(), change(pass, "remainingMinutes", 5L), key());
            refused(inaccessible, 403, "FORBIDDEN");
            var right = http(s.client(), "POST", "/api/passes/" + pass + "/adjustments?scope=" + first, s.token(), change(pass, "remainingMinutes", 5L), key());
            assertThat(right.statusCode()).as(right.body()).isEqualTo(200);
        }
    }

    @Test void writesNeedTheCsrfToken() throws Exception {
        long pass = pass(customer(first, "CSRF"), first, time(600));
        try (var s = login(payer)) {
            var response = http(s.client(), "POST", "/api/passes/" + pass + "/adjustments", null, change(pass, "remainingMinutes", 500L), key());
            assertThat(response.statusCode()).isEqualTo(403);
        }
        assertThat(adjustments(pass)).isZero();
    }

    // ---- input ----------------------------------------------------------------------------------------------

    @Test void malformedRequestsAreRefusedAndLeaveNothingBehind() throws Exception {
        long time = pass(customer(first, "입력 시간"), first, time(600));
        long storedValue = pass(customer(first, "입력 정액"), first, stored(10_000, 1_000));
        long period = pass(customer(first, "입력 기간"), first, period(30));
        var startedAt = Instant.parse(ok2(time, "startedAt"));
        try (var s = login(payer)) {
            var nullMinutes = new HashMap<String, Object>(change(time)); nullMinutes.put("remainingMinutes", null);
            var noReason = new HashMap<String, Object>(change(time, "remainingMinutes", 1L)); noReason.remove("reason");
            var noVersion = new HashMap<String, Object>(change(time, "remainingMinutes", 1L)); noVersion.remove("version");
            var cases = new ArrayList<Object[]>(List.<Object[]>of(
                    new Object[] {time, change(time, "remainingMinutes", -1L), "remainingMinutes"},
                    new Object[] {time, change(time, "remainingMinutes", 4_294_967_296L), "remainingMinutes"},
                    new Object[] {time, change(time, "remainingMinutes", 1.5), "remainingMinutes"},
                    new Object[] {time, change(time, "remainingMinutes", "10"), "remainingMinutes"},
                    new Object[] {time, nullMinutes, "remainingMinutes"},
                    new Object[] {time, change(time), "body"},
                    new Object[] {time, noReason, "reason"},
                    new Object[] {time, change(time, "remainingMinutes", 1L, "reason", "   "), "reason"},
                    new Object[] {time, change(time, "remainingMinutes", 1L, "reason", "가".repeat(501)), "reason"},
                    new Object[] {time, noVersion, "version"},
                    new Object[] {time, change(time, "remainingMinutes", 1L, "deltaMinutes", 5), "deltaMinutes"},
                    new Object[] {time, change(time, "expiresAt", "내일"), "expiresAt"},
                    new Object[] {time, change(time, "expiresAt", 123), "expiresAt"},
                    new Object[] {time, change(time, "expiresAt", "+12026-01-01T00:00:00Z"), "expiresAt"},
                    new Object[] {storedValue, change(storedValue, "remainingCreditKrw", 9_007_199_254_740_992L), "remainingCreditKrw"},
                    // kind mismatches, decided after the row is read
                    new Object[] {time, change(time, "remainingCreditKrw", 1L), "remainingCreditKrw"},
                    new Object[] {storedValue, change(storedValue, "remainingMinutes", 1L), "remainingMinutes"},
                    new Object[] {period, change(period, "remainingMinutes", 1L), "remainingMinutes"},
                    new Object[] {period, change(period, "remainingCreditKrw", 1L), "remainingCreditKrw"},
                    new Object[] {period, change(period, "expiresAt", null), "expiresAt"},
                    new Object[] {time, change(time, "expiresAt", startedAt.toString()), "expiresAt"},
                    new Object[] {period, change(period, "expiresAt", startedAt.minusSeconds(60).toString()), "expiresAt"}));
            for (var entry : cases) {
                var response = adjust(s, (long) entry[0], entry[1], key());
                refused(response, 422, "VALIDATION_FAILED");
                assertThat(node(response).get("fieldErrors").has((String) entry[2])).as(response.body()).isTrue();
            }
            refused(adjust(s, time, change(time, "remainingMinutes", 1L), null), 422, "VALIDATION_FAILED");
            refused(adjust(s, time, change(time, "remainingMinutes", 1L), "short"), 422, "VALIDATION_FAILED");
        }
        assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id IN (?,?,?)", time, storedValue, period)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE operation='passes:adjust' AND actor_employee_id=?", payer)).isZero();
        assertThat(minutes(time)).isEqualTo(600);
    }

    private String ok2(long pass, String field) throws Exception {
        try (var s = login(owner)) { return ok(s, "/api/passes/" + pass).get(field).asString(); }
    }

    @Test void theLargestAllowedValuesAreStored() throws Exception {
        long time = pass(customer(first, "최대 시간"), first, time(600));
        long storedValue = pass(customer(first, "최대 정액"), first, stored(10_000, 1_000));
        try (var s = login(payer)) {
            assertThat(adjusted(s, time, change(time, "remainingMinutes", 4_294_967_295L)).get("remainingMinutes").asLong()).isEqualTo(4_294_967_295L);
            assertThat(adjusted(s, storedValue, change(storedValue, "remainingCreditKrw", 9_007_199_254_740_991L)).get("remainingCreditKrw").asLong())
                    .isEqualTo(9_007_199_254_740_991L);
        }
    }

    // ---- status transitions ---------------------------------------------------------------------------------

    @Test void aRefundedPassCannotComeBack() throws Exception {
        long pass = pass(customer(first, "환불 고객"), first, time(600));
        refund(pass);
        try (var s = login(payer)) {
            refused(adjust(s, pass, change(pass, "remainingMinutes", 900L), key()), 409, "PASS_REFUNDED");
            refused(adjust(s, pass, change(pass, "expiresAt", TEN.plus(90, ChronoUnit.DAYS).toString()), key()), 409, "PASS_REFUNDED");
            // Refused before the version is even looked at, so a client cannot mistake it for a stale read.
            var stale = new LinkedHashMap<>(change(pass, "remainingMinutes", 900L)); stale.put("version", 1);
            refused(adjust(s, pass, stale, key()), 409, "PASS_REFUNDED");
            assertThat(ok(s, "/api/passes/" + pass).get("status").asString()).isEqualTo("환불");
        }
        assertThat(stored(pass)).isEqualTo("REFUNDED");
        assertThat(minutes(pass)).isEqualTo(600);
        assertThat(adjustments(pass)).isZero();
    }

    @Test void anExhaustedPassReturnsToActiveWhenTheBalanceGrowsAndCanBeUsedAgain() throws Exception {
        long customer = customer(first, "소진 고객");
        long pass = pass(customer, first, time(30));
        try (var s = login(payer)) {
            stay(s, first, customer, pass, 45, null);
            assertThat(stored(pass)).isEqualTo("EXHAUSTED");
            assertThat(ok(s, "/api/passes/" + pass).get("status").asString()).isEqualTo("소진");
            refused(checkIn(s, seat(first), customer, pass), 409, "PASS_EXHAUSTED");
            var result = adjusted(s, pass, change(pass, "remainingMinutes", 60L));
            assertThat(result.get("status").asString()).isEqualTo("활성");
            assertThat(stored(pass)).isEqualTo("ACTIVE");
            var back = stay(s, first, customer, pass, 20, null);
            assertThat(back.get("deductedMinutes").asLong()).isEqualTo(20);
            assertThat(minutes(pass)).isEqualTo(40);
        }
    }

    @Test void aBalanceAdjustedToZeroIsExhausted() throws Exception {
        long customer = customer(first, "영으로");
        long pass = pass(customer, first, stored(5_000, 1_000));
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingCreditKrw", 0L));
            assertThat(result.get("status").asString()).isEqualTo("소진");
            assertThat(stored(pass)).isEqualTo("EXHAUSTED");
            refused(checkIn(s, seat(first), customer, pass), 409, "PASS_EXHAUSTED");
        }
    }

    @Test void anExpiredPassReturnsToActiveWhenItsExpiryMovesToTheFuture() throws Exception {
        long customer = customer(first, "만료 고객");
        long time = pass(customer, first, time(600, 10L));
        long period = pass(customer(first, "만료 기간"), first, period(5));
        clock.fixed = TEN.plus(40, ChronoUnit.DAYS);
        Instant future = clock.fixed.plus(30, ChronoUnit.DAYS);
        try (var s = login(payer)) {
            assertThat(ok(s, "/api/passes/" + time).get("status").asString()).isEqualTo("만료");
            refused(checkIn(s, seat(first), customer, time), 409, "PASS_EXPIRED");
            // Raising only the balance of an expired pass leaves it expired: expiry is read from the clock.
            assertThat(adjusted(s, time, change(time, "remainingMinutes", 700L)).get("status").asString()).isEqualTo("만료");
            assertThat(adjusted(s, time, change(time, "expiresAt", future.toString())).get("status").asString()).isEqualTo("활성");
            assertThat(adjusted(s, period, change(period, "expiresAt", future.toString())).get("status").asString()).isEqualTo("활성");
            assertThat(checkIn(s, seat(first), customer, time).statusCode()).isEqualTo(201);
        }
    }

    @Test void aRowStoredAsExpiredLeavesThatStateOnlyWhenItsExpiryIsLifted() throws Exception {
        long pass = pass(customer(first, "저장 만료"), first, time(600, 10L));
        clock.fixed = TEN.plus(20, ChronoUnit.DAYS);
        jdbc.update("UPDATE passes SET status='EXPIRED' WHERE id=?", pass);
        try (var s = login(payer)) {
            assertThat(adjusted(s, pass, change(pass, "remainingMinutes", 710L)).get("status").asString()).isEqualTo("만료");
            assertThat(stored(pass)).isEqualTo("EXPIRED");
            assertThat(adjusted(s, pass, change(pass, "expiresAt", null)).get("status").asString()).isEqualTo("활성");
            assertThat(stored(pass)).isEqualTo("ACTIVE");
        }
    }

    @Test void anExpiryMayBeCorrectedIntoThePast() throws Exception {
        long customer = customer(first, "과거 만료");
        long pass = pass(customer, first, time(600, 10L));
        clock.fixed = TEN.plus(2, ChronoUnit.DAYS);
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "expiresAt", TEN.plus(1, ChronoUnit.DAYS).toString()));
            assertThat(result.get("status").asString()).isEqualTo("만료");
            assertThat(stored(pass)).isEqualTo("ACTIVE");
            refused(checkIn(s, seat(first), customer, pass), 409, "PASS_EXPIRED");
        }
    }

    @Test void aPassWithoutExpiryKeepsItOrTakesOneExplicitly() throws Exception {
        long open = pass(customer(first, "무기한"), first, time(600));
        long dated = pass(customer(first, "기한 있음"), first, stored(10_000, 1_000));
        jdbc.update("UPDATE passes SET expires_at=? WHERE id=?", java.sql.Timestamp.from(TEN.plus(10, ChronoUnit.DAYS)), dated);
        Instant expiry = TEN.plus(30, ChronoUnit.DAYS);
        try (var s = login(payer)) {
            assertThat(adjusted(s, open, change(open, "remainingMinutes", 500L)).get("expiresAt").isNull()).isTrue();
            assertThat(Instant.parse(adjusted(s, open, change(open, "expiresAt", expiry.toString())).get("expiresAt").asString())).isEqualTo(expiry);
            assertThat(adjusted(s, open, change(open, "expiresAt", null)).get("expiresAt").isNull()).isTrue();
            assertThat(adjusted(s, dated, change(dated, "expiresAt", null)).get("expiresAt").isNull()).isTrue();
        }
        assertThat(count("SELECT COUNT(*) FROM passes WHERE id IN (?,?) AND expires_at IS NULL", open, dated)).isEqualTo(2);
    }

    @Test void aLegacyNotStartedPassIsRefused() throws Exception {
        long pass = pass(customer(first, "미개시"), first, period(30));
        jdbc.update("UPDATE passes SET status='NOT_STARTED', started_at=NULL, expires_at=NULL WHERE id=?", pass);
        try (var s = login(payer)) {
            refused(adjust(s, pass, change(pass, "expiresAt", TEN.plus(30, ChronoUnit.DAYS).toString()), key()), 409, "INVALID_STATE");
        }
        assertThat(adjustments(pass)).isZero();
    }

    @Test void anAdjustmentIsNotUsageSoTheRefundStaysOpen() throws Exception {
        long pass = pass(customer(first, "미사용 조정"), first, time(600));
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingMinutes", 100L));
            assertThat(result.get("used").asBoolean()).isFalse();
            assertThat(result.get("canRefund").asBoolean()).isTrue();
        }
        refund(pass);
        assertThat(stored(pass)).isEqualTo("REFUNDED");
    }

    // ---- no-ops, versions, idempotency ----------------------------------------------------------------------

    @Test void sameValuesChangeNothingAndWriteNothing() throws Exception {
        long pass = pass(customer(first, "무변경"), first, time(600, 10L));
        long version = version(pass);
        String expiry;
        try (var s = login(payer)) { expiry = ok(s, "/api/passes/" + pass).get("expiresAt").asString(); }
        try (var s = login(payer)) {
            var result = adjusted(s, pass, change(pass, "remainingMinutes", 600L, "expiresAt", expiry));
            assertThat(result.get("version").asLong()).isEqualTo(version);
            assertThat(result.get("remainingMinutes").asLong()).isEqualTo(600);
        }
        assertThat(version(pass)).isEqualTo(version);
        assertThat(adjustments(pass)).isZero();
        assertThat(adjustAudits(pass)).isZero();
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_type='passes' AND entity_id=?", pass)).isEqualTo(1);
    }

    @Test void aStaleVersionIsAConflictPointingAtThePass() throws Exception {
        long pass = pass(customer(first, "버전"), first, time(600));
        try (var s = login(payer)) {
            var stale = change(pass, "remainingMinutes", 500L);
            adjusted(s, pass, change(pass, "remainingMinutes", 550L));
            var response = adjust(s, pass, stale, key());
            refused(response, 409, "VERSION_CONFLICT");
            assertThat(node(response).get("reloadUrl").asString()).isEqualTo("/api/passes/" + pass);
            assertThat(node(response).get("latestVersion").asLong()).isEqualTo(version(pass));
        }
        assertThat(minutes(pass)).isEqualTo(550);
        assertThat(adjustments(pass)).isEqualTo(1);
    }

    @Test void theSameKeyAnswersTheFirstResultForEveryKindAndADifferentInputConflicts() throws Exception {
        long time = pass(customer(first, "멱등 시간"), first, time(600));
        long storedValue = pass(customer(first, "멱등 정액"), first, stored(10_000, 1_000));
        long period = pass(customer(first, "멱등 기간"), first, period(30));
        var bodies = Map.of(time, change(time, "remainingMinutes", 500L), storedValue, change(storedValue, "remainingCreditKrw", 500L),
                period, change(period, "expiresAt", TEN.plus(90, ChronoUnit.DAYS).toString()));
        try (var s = login(payer)) {
            for (var entry : bodies.entrySet()) {
                String requestKey = key();
                var firstTry = adjust(s, entry.getKey(), entry.getValue(), requestKey);
                var again = adjust(s, entry.getKey(), entry.getValue(), requestKey);
                assertThat(firstTry.statusCode()).as(firstTry.body()).isEqualTo(200);
                assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
                assertThat(node(again)).isEqualTo(node(firstTry));
                assertThat(adjustments(entry.getKey())).isEqualTo(1);
                assertThat(adjustAudits(entry.getKey())).isEqualTo(1);
                var other = new LinkedHashMap<>(entry.getValue()); other.put("reason", "다른 사유");
                refused(adjust(s, entry.getKey(), other, requestKey), 409, "IDEMPOTENCY_CONFLICT");
            }
            // An explicit null expiry is a different input from no expiry at all.
            long open = pass(customer(first, "멱등 null"), first, time(600));
            String requestKey = key();
            adjust(s, open, change(open, "remainingMinutes", 500L), requestKey);
            var withNull = new LinkedHashMap<>(change(open, "remainingMinutes", 500L)); withNull.put("version", 1); withNull.put("expiresAt", null);
            refused(adjust(s, open, withNull, requestKey), 409, "IDEMPOTENCY_CONFLICT");
        }
        // The key belongs to one requester: another one's same key is a request of its own.
        long shared = pass(customer(first, "멱등 공유"), first, time(600));
        String requestKey = key();
        try (var s = login(payer)) { adjust(s, shared, change(shared, "remainingMinutes", 500L), requestKey); }
        try (var s = login(owner)) { assertThat(adjust(s, shared, change(shared, "remainingMinutes", 400L), requestKey).statusCode()).isEqualTo(200); }
        assertThat(minutes(shared)).isEqualTo(400);
    }

    @Test void aNoOpIsRememberedLikeAnyResult() throws Exception {
        long pass = pass(customer(first, "무변경 멱등"), first, time(600));
        var body = change(pass, "remainingMinutes", 600L);
        String requestKey = key();
        try (var s = login(payer)) {
            var once = adjust(s, pass, body, requestKey);
            adjusted(s, pass, change(pass, "remainingMinutes", 300L));
            var twice = adjust(s, pass, body, requestKey);
            assertThat(twice.statusCode()).isEqualTo(200);
            assertThat(node(twice)).isEqualTo(node(once));
        }
    }

    @Test void aRefusalIsNotRememberedSoTheKeyCanBeRetried() throws Exception {
        long pass = pass(customer(first, "거절 재시도"), first, time(600));
        String requestKey = key();
        try (var s = login(payer)) {
            var stale = new LinkedHashMap<>(change(pass, "remainingMinutes", 500L)); stale.put("version", 9);
            refused(adjust(s, pass, stale, requestKey), 409, "VERSION_CONFLICT");
            assertThat(adjust(s, pass, change(pass, "remainingMinutes", 500L), requestKey).statusCode()).isEqualTo(200);
        }
        assertThat(adjustments(pass)).isEqualTo(1);
    }

    @Test void aKeyReusedAfterItsRecordExpiredIsRefusedByTheLedgerKey() throws Exception {
        long pass = pass(customer(first, "만료 키"), first, time(600));
        String requestKey = key();
        try (var s = login(payer)) {
            assertThat(adjust(s, pass, change(pass, "remainingMinutes", 500L), requestKey).statusCode()).isEqualTo(200);
            jdbc.update("UPDATE idempotency_records SET expires_at=? WHERE operation='passes:adjust' AND scope_key=?",
                    java.sql.Timestamp.from(TEN.minusSeconds(1)), Long.toString(pass));
            refused(adjust(s, pass, change(pass, "remainingMinutes", 400L), requestKey), 409, "IDEMPOTENCY_CONFLICT");
        }
        assertThat(minutes(pass)).isEqualTo(500);
        assertThat(adjustments(pass)).isEqualTo(1);
    }

    // ---- with a stay in progress ----------------------------------------------------------------------------

    @Test void aLoweredBalanceIsWhatTheCheckOutChargesAndTheRestIsOverage() throws Exception {
        long customer = customer(first, "입실 중 감소");
        long pass = pass(customer, first, time(120));
        try (var s = login(payer)) {
            long session = entered(s, seat(first), customer, pass);
            adjusted(s, pass, change(pass, "remainingMinutes", 30L));
            clock.fixed = TEN.plusSeconds(50 * 60 - 40);
            var out = left(s, session, null);
            assertThat(out.get("usedMinutes").asLong()).isEqualTo(50);
            assertThat(out.get("deductedMinutes").asLong()).isEqualTo(30);
            assertThat(out.get("overageMinutes").asLong()).isEqualTo(20);
            assertThat(out.get("passRemainingMinutes").asLong()).isZero();
        }
        assertThat(stored(pass)).isEqualTo("EXHAUSTED");
        assertThat(120 + ledgerSum(pass)).isEqualTo(minutes(pass));
    }

    @Test void aRaisedBalanceCoversTheStay() throws Exception {
        long customer = customer(first, "입실 중 증가");
        long pass = pass(customer, first, time(10));
        try (var s = login(payer)) {
            long session = entered(s, seat(first), customer, pass);
            adjusted(s, pass, change(pass, "remainingMinutes", 100L));
            clock.fixed = TEN.plusSeconds(50 * 60);
            var out = left(s, session, null);
            assertThat(out.get("deductedMinutes").asLong()).isEqualTo(50);
            assertThat(out.get("overageMinutes").asLong()).isZero();
            assertThat(out.get("passRemainingMinutes").asLong()).isEqualTo(50);
        }
        assertThat(stored(pass)).isEqualTo("ACTIVE");
    }

    @Test void aBalanceSetToZeroDuringAStayChargesNothingAndClosesTheStay() throws Exception {
        long customer = customer(first, "입실 중 영");
        long pass = pass(customer, first, stored(10_000, 1_000));
        try (var s = login(payer)) {
            long session = entered(s, seat(first), customer, pass);
            adjusted(s, pass, change(pass, "remainingCreditKrw", 0L));
            clock.fixed = TEN.plusSeconds(30 * 60);
            var out = left(s, session, null);
            assertThat(out.get("deductedKrw").asLong()).isZero();
            assertThat(out.get("overageKrw").asLong()).isEqualTo(500);
        }
        assertThat(stored(pass)).isEqualTo("EXHAUSTED");
        assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=? AND kind='USE'", pass)).isZero();
    }

    @Test void anExpiryAdjustedDuringAStaySplitsItAtTheNewExpiry() throws Exception {
        long customer = customer(first, "입실 중 만료");
        long pass = pass(customer, first, time(600));
        try (var s = login(payer)) {
            long session = entered(s, seat(first), customer, pass);
            adjusted(s, pass, change(pass, "expiresAt", TEN.plusSeconds(930).toString()));
            clock.fixed = TEN.plusSeconds(1800);
            var out = left(s, session, null);
            assertThat(out.get("usedMinutes").asLong()).isEqualTo(30);
            assertThat(out.get("deductedMinutes").asLong()).isEqualTo(16);
            assertThat(out.get("overageMinutes").asLong()).isEqualTo(14);
        }
    }

    @Test void anExpiryMovedBeforeCheckInTurnsTheWholeStayIntoOverage() throws Exception {
        long customer = customer(first, "입실 전 만료");
        long time = pass(customer, first, time(600));
        long storedValue = pass(customer, first, stored(10_000, 1_000));
        clock.fixed = TEN.plusSeconds(3600);
        Instant in = clock.fixed;
        try (var s = login(payer)) {
            long session = entered(s, seat(first), customer, time);
            adjusted(s, time, change(time, "expiresAt", in.minusSeconds(600).toString()));
            clock.fixed = in.plusSeconds(1800);
            var out = left(s, session, null);
            assertThat(out.get("usedMinutes").asLong()).isEqualTo(30);
            assertThat(out.get("deductedMinutes").asLong()).isZero();
            assertThat(out.get("overageMinutes").asLong()).isEqualTo(30);
            assertThat(minutes(time)).isEqualTo(600);

            in = clock.fixed;
            session = entered(s, seat(first), customer, storedValue);
            adjusted(s, storedValue, change(storedValue, "expiresAt", in.minusSeconds(60).toString()));
            clock.fixed = in.plusSeconds(1800);
            var settled = left(s, session, null);
            assertThat(settled.get("deductedKrw").asLong()).isZero();
            assertThat(settled.get("overageMinutes").asLong()).isEqualTo(30);
            assertThat(settled.get("overageKrw").asLong()).isZero();
        }
    }

    // ---- real concurrency -----------------------------------------------------------------------------------

    private static final int ROUNDS = 12;

    @Test void anAdjustmentAndACheckOutNeverLoseEachOthersChange() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clock.fixed = TEN;
            try (var desk = login(payer); var counter = login(clerk)) {
                long customer = customer(first, "경합 고객 " + round);
                long pass = pass(customer, first, time(600));
                long session = entered(counter, seat(first), customer, pass);
                clock.fixed = TEN.plusSeconds(1800);
                var body = change(pass, "remainingMinutes", 500L);
                int delay = round % 3 * 5;
                var responses = race(List.<Callable<HttpResponse<String>>>of(() -> checkOut(counter, session, null), () -> {
                    Thread.sleep(delay);
                    return adjust(desk, pass, body, key());
                }));
                var out = responses.get(0);
                var adjustment = responses.get(1);
                assertThat(out.statusCode()).as(out.body()).isEqualTo(200);
                if (adjustment.statusCode() == 200) {
                    // Adjustment first: the 30 minutes come off the new balance.
                    assertThat(minutes(pass)).isEqualTo(470);
                } else {
                    // Check-out first: the version moved, so the correction of a value nobody has seen is refused.
                    refused(adjustment, 409, "VERSION_CONFLICT");
                    assertThat(minutes(pass)).isEqualTo(570);
                }
                assertThat(600 + ledgerSum(pass)).isEqualTo(minutes(pass));
                assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=? AND kind='USE'", pass)).isEqualTo(1);
            }
        }
    }

    @Test void onlyOneOfManyAdjustmentsOfTheSameVersionWins() throws Exception {
        try (var s = login(payer)) {
            for (int round = 0; round < 3; round++) {
                long pass = pass(customer(first, "동시 조정 " + round), first, time(600));
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    var body = change(pass, "remainingMinutes", 100L + index);
                    calls.add(() -> adjust(s, pass, body, key()));
                }
                var responses = race(calls);
                var winners = responses.stream().filter(response -> response.statusCode() == 200).toList();
                assertThat(winners).hasSize(1);
                for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "VERSION_CONFLICT");
                assertThat(minutes(pass)).isEqualTo(node(winners.getFirst()).get("remainingMinutes").asLong());
                assertThat(adjustments(pass)).isEqualTo(1);
                assertThat(adjustAudits(pass)).isEqualTo(1);
                assertThat(600 + ledgerSum(pass)).isEqualTo(minutes(pass));
            }
        }
    }

    @Test void aRetriedAdjustmentRacingItselfAppliesOnce() throws Exception {
        long pass = pass(customer(first, "같은 키 경합"), first, time(600));
        var body = change(pass, "remainingMinutes", 500L);
        String requestKey = key();
        try (var s = login(payer)) {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) calls.add(() -> adjust(s, pass, body, requestKey));
            var responses = race(calls);
            JsonNode expected = node(responses.getFirst());
            for (var response : responses) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(node(response)).isEqualTo(expected);
            }
        }
        assertThat(adjustments(pass)).isEqualTo(1);
        assertThat(adjustAudits(pass)).isEqualTo(1);
    }

    @Test void anAdjustmentNeverRevivesARefundThatRacedIt() throws Exception {
        try (var s = login(payer)) {
            for (int round = 0; round < ROUNDS; round++) {
                long pass = pass(customer(first, "환불 경합 " + round), first, time(600));
                long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", pass);
                var body = change(pass, "remainingMinutes", 500L);
                int delay = round % 3 * 15;
                var responses = race(List.<Callable<HttpResponse<String>>>of(
                        () -> http(s.client(), "POST", "/api/payments/" + payment + "/refund", s.token(), Map.of("reason", "경합 환불"), key()),
                        () -> { Thread.sleep(delay); return adjust(s, pass, body, key()); }));
                assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(200);
                assertThat(stored(pass)).isEqualTo("REFUNDED");
                if (responses.get(1).statusCode() != 200) refused(responses.get(1), 409, "PASS_REFUNDED");
                assertThat(600 + ledgerSum(pass)).isEqualTo(minutes(pass));
            }
        }
    }
}
