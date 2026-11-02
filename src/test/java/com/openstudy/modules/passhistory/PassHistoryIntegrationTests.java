package com.openstudy.modules.passhistory;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #64: the stay list and the customer's purchase and usage history (seat-usage-api.md), and who sees what. */
@MySqlIntegrationTest
class PassHistoryIntegrationTests extends PassUsageFixture {
    /** Handler and reason of a stay at the second branch; neither may reach a reader of the first branch. */
    private static final String FOREIGN_REASON = "둘째 지점 내부 사유";

    private String history(long customer, String query) { return "/api/customers/" + customer + "/pass-history" + query; }

    private static List<JsonNode> events(JsonNode history, String type) {
        var found = new ArrayList<JsonNode>();
        history.get("events").forEach(event -> { if (type.equals(event.get("type").asString())) found.add(event); });
        return found;
    }

    private static JsonNode ledgerOf(JsonNode history, long pass) {
        for (var check : history.get("passes")) if (check.get("passId").asLong() == pass) return check;
        throw new AssertionError("No ledger check for pass " + pass + " in " + history);
    }

    private static List<Long> ids(JsonNode page) {
        var result = new ArrayList<Long>();
        page.get("items").forEach(item -> result.add(item.get("id").asLong()));
        return result;
    }

    // ---- stay history ---------------------------------------------------------------------------------------

    @Test void aStayShowsBothBranchesAndOnlyMinimalCustomerData() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long pass = pass(customer, second, time(100));
        long seat = seat(first);
        try (var s = login(clerk)) {
            long session = entered(s, seat, customer, pass);
            // Another branch's balance shows while the stay runs, in both reads, and not after it closed.
            assertThat(ok(s, "/api/seat-sessions/" + session).get("passRemainingMinutes").asLong()).isEqualTo(100);
            assertThat(ok(s, "/api/seat-sessions").get("items").get(0).get("passRemainingMinutes").asLong()).isEqualTo(100);
            clock.fixed = TEN.plusSeconds(50 * 60 + 20);
            left(s, session, null);
            var response = get(s, "/api/seat-sessions");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var item = node(response).get("items").get(0);
            assertThat(item.get("id").asLong()).isEqualTo(session);
            assertThat(item.get("branchId").asLong()).isEqualTo(first);
            assertThat(item.get("pass").get("purchaseBranchId").asLong()).isEqualTo(second);
            assertThat(item.get("pass").get("kind").asString()).isEqualTo("시간권");
            assertThat(item.get("seat").get("id").asLong()).isEqualTo(seat);
            assertThat(item.get("seat").get("seatNo").asString()).startsWith("H-");
            assertThat(item.get("status").asString()).isEqualTo("종료");
            assertThat(item.get("usedMinutes").asLong()).isEqualTo(51);
            assertThat(item.get("deductedMinutes").asLong()).isEqualTo(51);
            assertThat(item.get("overageMinutes").asLong()).isZero();
            assertThat(item.get("checkedInBy").get("id").asLong()).isEqualTo(clerk);
            assertThat(item.get("moves")).isEmpty();
            var names = new ArrayList<String>();
            item.get("customer").propertyNames().forEach(names::add);
            assertThat(names).containsExactlyInAnyOrder("id", "memberNo", "name");
            assertThat(response.body()).doesNotContain("secret@customer.test").doesNotContain("내부 메모")
                    .doesNotContain(text("SELECT phone FROM customers WHERE id=?", customer));
            // The list and the detail agree apart from the moves.
            var detail = ok(s, "/api/seat-sessions/" + session);
            assertThat(detail.get("usedMinutes")).isEqualTo(item.get("usedMinutes"));
            assertThat(item.get("passRemainingMinutes").isNull()).isTrue();
            assertThat(item.get("passRemainingCreditKrw").isNull()).isTrue();
            assertThat(detail.get("passRemainingMinutes").isNull()).isTrue();
            assertThat(detail.get("passRemainingCreditKrw").isNull()).isTrue();
        }
    }

    @Test void aListItemHasEveryFieldOfTheSchema() throws Exception {
        long customer = customer(first, "스키마");
        long pass = pass(customer, first, stored(10_000, 1_000));
        try (var s = login(clerk)) {
            long session = entered(s, seat(first), customer, pass);
            var names = new ArrayList<String>();
            ok(s, "/api/seat-sessions").get("items").get(0).propertyNames().forEach(names::add);
            assertThat(names).containsExactlyInAnyOrder("id", "version", "status", "branchId", "seat", "customer", "pass", "checkedInAt",
                    "checkedOutAt", "usedMinutes", "deductedMinutes", "deductedKrw", "overageMinutes", "overageKrw", "passRemainingMinutes",
                    "passRemainingCreditKrw", "checkedInBy", "checkInReason", "moves");
            assertThat(ok(s, "/api/seat-sessions?status=이용 중".replace(" ", "%20")).get("items").get(0).get("id").asLong()).isEqualTo(session);
        }
    }

    @Test void onlyStaysAtBranchesInScopeAreReturned() throws Exception {
        long inFirst = customer(first, "첫 지점");
        long inSecond = customer(second, "둘째 지점");
        long here, there;
        try (var s = login(dual)) {
            here = entered(s, seat(first), inFirst, pass(inFirst, first, time(100)));
            there = entered(s, seat(second), inSecond, pass(inSecond, second, time(100)));
            assertThat(ids(ok(s, "/api/seat-sessions"))).containsExactlyInAnyOrder(here, there);
            assertThat(ids(ok(s, "/api/seat-sessions?scope=" + first))).containsExactly(here);
            assertThat(ids(ok(s, "/api/seat-sessions?scope=" + second))).containsExactly(there);
        }
        try (var s = login(clerk)) {
            var page = ok(s, "/api/seat-sessions");
            assertThat(ids(page)).containsExactly(here);
            assertThat(page.get("meta").get("total").asLong()).isEqualTo(1);
            refused(get(s, "/api/seat-sessions?scope=" + second), 403, "FORBIDDEN");
            // Filters never reach beyond the branch: the other branch's seat or customer is just an empty result.
            long otherSeat = count("SELECT seat_id FROM seat_sessions WHERE id=?", there);
            assertThat(ids(ok(s, "/api/seat-sessions?seatId=" + otherSeat))).isEmpty();
            assertThat(ids(ok(s, "/api/seat-sessions?customerId=" + inSecond))).isEmpty();
        }
        try (var s = login(owner)) { assertThat(ids(ok(s, "/api/seat-sessions"))).containsExactlyInAnyOrder(here, there); }
    }

    @Test void aStayIsSeenByItsBranchEvenWhenTheCustomerIsOutsideTheLookupScope() throws Exception {
        long customer = customer(second, "범위 밖 고객");
        long pass = pass(customer, second, time(100));
        try (var s = login(clerk)) {
            long session = entered(s, seat(first), customer, pass);
            assertThat(ids(ok(s, "/api/seat-sessions?customerId=" + customer))).containsExactly(session);
            refused(get(s, "/api/customers/" + customer), 404, "NOT_FOUND");
        }
    }

    @Test void filtersSortingAndPagingWorkTogether() throws Exception {
        long a = customer(first, "가");
        long b = customer(first, "나");
        long seatA = seat(first);
        long seatB = seat(first);
        long passA = pass(a, first, time(600));
        long passB = pass(b, first, time(600));
        try (var s = login(clerk)) {
            long one = entered(s, seatA, a, passA);
            clock.fixed = TEN.plusSeconds(600);
            left(s, one, null);
            long two = entered(s, seatB, b, passB);
            clock.fixed = TEN.plusSeconds(1200);
            left(s, two, null);
            long three = entered(s, seatA, a, passA);
            assertThat(ids(ok(s, "/api/seat-sessions"))).containsExactly(three, two, one);
            assertThat(ids(ok(s, "/api/seat-sessions?direction=asc&sort=checkedInAt"))).containsExactly(one, two, three);
            assertThat(ids(ok(s, "/api/seat-sessions?status=%EC%A2%85%EB%A3%8C"))).containsExactly(two, one);
            assertThat(ids(ok(s, "/api/seat-sessions?seatId=" + seatA))).containsExactly(three, one);
            assertThat(ids(ok(s, "/api/seat-sessions?customerId=" + b))).containsExactly(two);
            // [from, to): check-in time, the lower bound included and the upper one not.
            String from = TEN.plusSeconds(600).toString(), to = TEN.plusSeconds(1200).toString();
            assertThat(ids(ok(s, "/api/seat-sessions?from=" + from))).containsExactly(three, two);
            assertThat(ids(ok(s, "/api/seat-sessions?to=" + from))).containsExactly(one);
            assertThat(ids(ok(s, "/api/seat-sessions?from=" + from + "&to=" + to))).containsExactly(two);
            assertThat(ids(ok(s, "/api/seat-sessions?from=" + TEN + "&to=" + to + "&seatId=" + seatA))).containsExactly(one);
            var page = ok(s, "/api/seat-sessions?pageSize=2&page=2");
            assertThat(ids(page)).containsExactly(one);
            assertThat(page.get("meta").get("total").asLong()).isEqualTo(3);
            var farAway = ok(s, "/api/seat-sessions?page=9007199254740991&pageSize=100");
            assertThat(ids(farAway)).isEmpty();
            assertThat(farAway.get("meta").get("total").asLong()).isEqualTo(3);
        }
    }

    @Test void equalCheckInTimesFollowTheId() throws Exception {
        long a = customer(first, "동시 가");
        long b = customer(first, "동시 나");
        long passA = pass(a, first, time(600));
        long passB = pass(b, first, time(600));
        try (var s = login(clerk)) {
            long one = entered(s, seat(first), a, passA);
            long two = entered(s, seat(first), b, passB);
            assertThat(ids(ok(s, "/api/seat-sessions"))).containsExactly(two, one);
            assertThat(ids(ok(s, "/api/seat-sessions?direction=asc"))).containsExactly(one, two);
        }
    }

    @Test void badStayFiltersAreRefused() throws Exception {
        try (var s = login(clerk)) {
            for (var query : List.of("status=none", "from=yesterday", "to=2026", "seatId=abc", "customerId=0", "sort=checkedOutAt",
                    "direction=sideways", "page=0", "pageSize=101", "from=" + TEN + "&to=" + TEN, "from=" + TEN.plusSeconds(1) + "&to=" + TEN,
                    "from=%2B12026-01-01T00:00:00Z"))
                refused(get(s, "/api/seat-sessions?" + query), 422, "VALIDATION_FAILED");
        }
    }

    @Test void anEmployeeWithoutABranchSeesAnEmptyList() throws Exception {
        long lonely = person("EMPLOYEE", "소속 없음");
        try (var s = login(lonely)) {
            var page = ok(s, "/api/seat-sessions");
            assertThat(ids(page)).isEmpty();
            assertThat(page.get("meta").get("total").asLong()).isZero();
        }
    }

    @Test void aStoppedBranchStillAnswersItsHistories() throws Exception {
        long customer = customer(first, "중지 지점");
        long pass = pass(customer, first, time(600));
        try (var s = login(clerk)) {
            long session = entered(s, seat(first), customer, pass);
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            assertThat(ids(ok(s, "/api/seat-sessions"))).containsExactly(session);
            assertThat(ok(s, history(customer, "")).get("passes")).hasSize(1);
        }
    }

    // ---- customer history: contents -------------------------------------------------------------------------

    @Test void purchaseUseAdjustmentAndRefundAreReturnedInOrderAndReconcile() throws Exception {
        long customer = customer(first, "내역 고객");
        long used = pass(customer, first, time(600));
        long gone = pass(customer, first, stored(10_000, 1_000));
        long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", gone);
        try (var s = login(payer)) {
            clock.fixed = TEN.plusSeconds(60);
            adjusted(s, used, change(used, "remainingMinutes", 660L));
            var out = stay(s, first, customer, used, 45, "퇴실 사유");
            clock.fixed = clock.fixed.plusSeconds(60);
            refund(gone);
            var history = ok(s, history(customer, ""));
            assertThat(history.get("customer").get("id").asLong()).isEqualTo(customer);
            assertThat(history.get("from").isNull()).isTrue();
            assertThat(history.get("to").isNull()).isTrue();
            var types = new ArrayList<String>();
            history.get("events").forEach(event -> types.add(event.get("type").asString()));
            assertThat(types).containsExactly("구매", "구매", "조정", "이용", "환불");

            var purchase = events(history, "구매").get(0);
            assertThat(purchase.get("passId").asLong()).isEqualTo(used);
            assertThat(purchase.get("branchId").asLong()).isEqualTo(first);
            assertThat(purchase.get("paymentId").asLong()).isEqualTo(count("SELECT purchase_payment_id FROM passes WHERE id=?", used));
            assertThat(purchase.get("actor").get("id").asLong()).isEqualTo(owner);
            assertThat(purchase.get("deltaMinutes").asLong()).isZero();
            assertThat(purchase.get("productName").asString()).endsWith("시간권");

            var adjustment = events(history, "조정").get(0);
            assertThat(adjustment.get("deltaMinutes").asLong()).isEqualTo(60);
            assertThat(adjustment.get("reason").asString()).isEqualTo("현장 보정");
            assertThat(adjustment.get("actor").get("name").asString()).isEqualTo("결제 담당");
            assertThat(adjustment.get("branchId").asLong()).isEqualTo(first);
            assertThat(adjustment.get("usedMinutes").isNull()).isTrue();

            var use = events(history, "이용").get(0);
            assertThat(use.get("deltaMinutes").asLong()).isEqualTo(-45);
            assertThat(use.get("deltaKrw").asLong()).isZero();
            assertThat(use.get("usedMinutes").asLong()).isEqualTo(45);
            assertThat(use.get("seatSessionId").asLong()).isEqualTo(out.get("id").asLong());
            assertThat(use.get("branchId").asLong()).isEqualTo(first);
            assertThat(use.get("reason").asString()).isEqualTo("퇴실 사유");
            assertThat(use.get("actor").get("id").asLong()).isEqualTo(payer);

            var refund = events(history, "환불").get(0);
            assertThat(refund.get("passId").asLong()).isEqualTo(gone);
            assertThat(refund.get("paymentId").asLong()).isEqualTo(payment);
            assertThat(refund.get("reason").asString()).isEqualTo("고객 요청 환불");
            assertThat(refund.get("actor").get("id").asLong()).isEqualTo(owner);
            assertThat(refund.get("deltaKrw").asLong()).isZero();

            var check = ledgerOf(history, used);
            assertThat(check.get("issuedMinutes").asLong()).isEqualTo(600);
            assertThat(check.get("ledgerMinutes").asLong()).isEqualTo(15);
            assertThat(check.get("remainingMinutes").asLong()).isEqualTo(615);
            assertThat(check.get("status").asString()).isEqualTo("활성");
            assertThat(check.get("issuedCreditKrw").isNull()).isTrue();
            assertThat(ledgerOf(history, gone).get("status").asString()).isEqualTo("환불");
        }
    }

    @Test void noPaymentAmountLeavesTheHistory() throws Exception {
        long customer = customer(first, "금액 고객");
        pass(customer, first, time(600));
        try (var s = login(clerk)) {
            var response = get(s, history(customer, ""));
            assertThat(response.body()).doesNotContain("15437").doesNotContain("amountKrw").doesNotContain("priceKrw")
                    .doesNotContain("secret@customer.test").doesNotContain("내부 메모");
        }
    }

    @Test void theLedgerReconcilesForEveryKind() throws Exception {
        long customer = customer(first, "대조 고객");
        long time = pass(customer, first, time(600));
        long credit = pass(customer, first, stored(10_000, 1_500));
        long period = pass(customer, first, period(30));
        try (var s = login(payer)) {
            stay(s, first, customer, time, 50, null);
            adjusted(s, time, change(time, "remainingMinutes", 10L));
            stay(s, first, customer, time, 40, null); // 10 deducted, 30 overage, exhausted
            adjusted(s, time, change(time, "remainingMinutes", 77L));
            stay(s, first, customer, credit, 50, null);
            adjusted(s, credit, change(credit, "remainingCreditKrw", 20_000L));
            stay(s, first, customer, credit, 1, null);
            stay(s, first, customer, period, 30, null);
            adjusted(s, period, change(period, "expiresAt", TEN.plus(90, ChronoUnit.DAYS).toString()));
            var history = ok(s, history(customer, ""));
            for (var check : history.get("passes")) {
                if (check.get("kind").asString().equals("기간권")) {
                    assertThat(check.get("issuedMinutes").isNull()).isTrue();
                    assertThat(check.get("remainingMinutes").isNull()).isTrue();
                    assertThat(check.get("ledgerMinutes").asLong()).isZero();
                    assertThat(check.get("ledgerKrw").asLong()).isZero();
                    continue;
                }
                boolean minutes = check.get("kind").asString().equals("시간권");
                long issued = check.get(minutes ? "issuedMinutes" : "issuedCreditKrw").asLong();
                long ledger = check.get(minutes ? "ledgerMinutes" : "ledgerKrw").asLong();
                long remaining = check.get(minutes ? "remainingMinutes" : "remainingCreditKrw").asLong();
                assertThat(issued + ledger).as(check.toString()).isEqualTo(remaining);
                // The events explain the ledger to the last unit, and the balance is the stored one.
                long passId = check.get("passId").asLong();
                long sum = 0;
                for (var event : history.get("events")) if (event.get("passId").asLong() == passId) sum += event.get(minutes ? "deltaMinutes" : "deltaKrw").asLong();
                assertThat(sum).isEqualTo(ledger);
                assertThat(remaining).isEqualTo(count("SELECT COALESCE(remaining_minutes, remaining_credit_krw) FROM passes WHERE id=?", passId));
            }
            assertThat(ledgerOf(history, time).get("remainingMinutes").asLong()).isEqualTo(77);
            assertThat(ledgerOf(history, credit).get("remainingCreditKrw").asLong()).isEqualTo(19_975);
        }
    }

    @Test void aPeriodPassStayShowsItsMinutesAndHandlerWithoutALedgerRow() throws Exception {
        long customer = customer(first, "기간 이용");
        long pass = pass(customer, first, period(30));
        try (var s = login(clerk)) {
            var out = stay(s, first, customer, pass, 45, "기간권 퇴실");
            assertThat(count("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=?", pass)).isZero();
            var use = events(ok(s, history(customer, "")), "이용").get(0);
            assertThat(use.get("deltaMinutes").asLong()).isZero();
            assertThat(use.get("deltaKrw").asLong()).isZero();
            assertThat(use.get("usedMinutes").asLong()).isEqualTo(45);
            assertThat(use.get("seatSessionId").asLong()).isEqualTo(out.get("id").asLong());
            assertThat(use.get("reason").asString()).isEqualTo("기간권 퇴실");
            assertThat(use.get("actor").get("id").asLong()).isEqualTo(clerk);
        }
    }

    @Test void aStayInProgressIsNotAnEventYet() throws Exception {
        long customer = customer(first, "입실 중");
        long pass = pass(customer, first, time(600));
        try (var s = login(clerk)) {
            entered(s, seat(first), customer, pass);
            assertThat(events(ok(s, history(customer, "")), "이용")).isEmpty();
        }
    }

    @Test void aPeriodFiltersEventsButNotTheReconciliation() throws Exception {
        long customer = customer(first, "기간 조회");
        long pass = pass(customer, first, time(600));
        try (var s = login(payer)) {
            clock.fixed = TEN.plusSeconds(1800);
            Instant firstAdjust = clock.fixed;
            adjusted(s, pass, change(pass, "remainingMinutes", 500L));
            clock.fixed = TEN.plusSeconds(3600);
            Instant secondAdjust = clock.fixed;
            adjusted(s, pass, change(pass, "remainingMinutes", 400L));

            var history = ok(s, history(customer, "?from=" + firstAdjust + "&to=" + secondAdjust));
            assertThat(history.get("events")).hasSize(1);
            assertThat(history.get("events").get(0).get("deltaMinutes").asLong()).isEqualTo(-100);
            assertThat(Instant.parse(history.get("from").asString())).isEqualTo(firstAdjust);
            assertThat(Instant.parse(history.get("to").asString())).isEqualTo(secondAdjust);
            assertThat(ledgerOf(history, pass).get("ledgerMinutes").asLong()).isEqualTo(-200);
            assertThat(ledgerOf(history, pass).get("remainingMinutes").asLong()).isEqualTo(400);
            // [from, to): the event at "to" belongs to the next period, and to it alone.
            assertThat(ok(s, history(customer, "?from=" + secondAdjust)).get("events")).hasSize(1);
            assertThat(ok(s, history(customer, "?to=" + firstAdjust)).get("events")).hasSize(1); // the purchase
        }
    }

    @Test void badHistoryPeriodsAreRefused() throws Exception {
        long customer = customer(first, "기간 오류");
        try (var s = login(clerk)) {
            for (var query : List.of("from=x", "to=x", "from=" + TEN + "&to=" + TEN, "from=" + TEN.plusSeconds(1) + "&to=" + TEN))
                refused(get(s, history(customer, "?" + query)), 422, "VALIDATION_FAILED");
        }
    }

    // ---- customer history: who sees what ---------------------------------------------------------------------

    @Test void aStayAtAnotherBranchShowsBranchTimeAndDeltasOnly() throws Exception {
        long customer = customer(first, "다른 지점 이용");
        long time = pass(customer, first, time(600));
        long period = pass(customer, first, period(30));
        String foreignName = "둘째지점 결제 담당";
        long foreignSeat = seat(second);
        String foreignSeatNo = text("SELECT seat_no FROM seats WHERE id=?", foreignSeat);
        long stay, stay2;
        try (var other = login(foreign)) {
            stay = entered(other, foreignSeat, customer, time);
            clock.fixed = TEN.plusSeconds(30 * 60);
            left(other, stay, FOREIGN_REASON);
            stay2 = entered(other, foreignSeat, customer, period);
            clock.fixed = clock.fixed.plusSeconds(45 * 60);
            left(other, stay2, FOREIGN_REASON);
        }
        for (long reader : List.of(payer, clerk)) {
            try (var s = login(reader)) {
                var response = get(s, history(customer, ""));
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.body()).doesNotContain(FOREIGN_REASON).doesNotContain(foreignName).doesNotContain(foreignSeatNo);
                var uses = events(node(response), "이용");
                assertThat(uses).hasSize(2);
                var names = new ArrayList<String>();
                uses.get(0).propertyNames().forEach(names::add);
                assertThat(names).hasSize(12); // every field stays, as the schema requires
                for (var use : uses) {
                    assertThat(use.get("branchId").asLong()).isEqualTo(second);
                    assertThat(use.get("at").asString()).isNotBlank();
                    assertThat(use.get("reason").isNull()).isTrue();
                    assertThat(use.get("actor").isNull()).isTrue();
                    assertThat(use.get("seatSessionId").isNull()).isTrue();
                    assertThat(use.get("usedMinutes").isNull()).isTrue();
                    assertThat(use.get("paymentId").isNull()).isTrue();
                }
                assertThat(uses.get(0).get("deltaMinutes").asLong()).isEqualTo(-30);
                assertThat(uses.get(1).get("deltaMinutes").asLong()).isZero();
                // The stay itself stays out of reach, and the ledger still adds up with it.
                refused(get(s, "/api/seat-sessions/" + stay), 404, "NOT_FOUND");
                var check = ledgerOf(node(response), time);
                assertThat(check.get("issuedMinutes").asLong() + check.get("ledgerMinutes").asLong()).isEqualTo(check.get("remainingMinutes").asLong());
                assertThat(check.get("remainingMinutes").asLong()).isEqualTo(570);
            }
        }
    }

    @Test void theRequestScopeDecidesWhatIsForeign() throws Exception {
        long customer = customer(first, "범위 전환");
        long pass = pass(customer, first, time(600));
        try (var s = login(dual)) {
            stay(s, second, customer, pass, 30, "두 지점 직원 사유");
            var everywhere = events(ok(s, history(customer, "")), "이용").get(0);
            assertThat(everywhere.get("reason").asString()).isEqualTo("두 지점 직원 사유");
            assertThat(everywhere.get("usedMinutes").asLong()).isEqualTo(30);
            var narrowed = get(s, history(customer, "?scope=" + first));
            assertThat(narrowed.body()).doesNotContain("두 지점 직원 사유");
            assertThat(events(node(narrowed), "이용").get(0).get("actor").isNull()).isTrue();
            assertThat(events(node(narrowed), "이용").get(0).get("branchId").asLong()).isEqualTo(second);
        }
    }

    @Test void theOwnerSeesEveryStayInFull() throws Exception {
        long customer = customer(first, "소유자 조회");
        long pass = pass(customer, first, time(600));
        try (var other = login(foreign)) { stay(other, second, customer, pass, 30, FOREIGN_REASON); }
        try (var s = login(owner)) {
            var use = events(ok(s, history(customer, "")), "이용").get(0);
            assertThat(use.get("reason").asString()).isEqualTo(FOREIGN_REASON);
            assertThat(use.get("actor").get("id").asLong()).isEqualTo(foreign);
        }
    }

    @Test void aCustomerOutsideTheLookupScopeIsNotFoundEvenAfterAnExactMatchAndAStay() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long pass = pass(customer, second, time(600));
        String phone = text("SELECT phone FROM customers WHERE id=?", customer);
        try (var s = login(clerk)) {
            var lookup = http(s.client(), "POST", "/api/customer-lookups/check-in", s.token(), Map.of("branchId", Long.toString(first), "phone", phone), null);
            assertThat(lookup.statusCode()).as(lookup.body()).isEqualTo(200);
            assertThat(node(lookup).get("items")).hasSize(1);
            refused(get(s, history(customer, "")), 404, "NOT_FOUND");
            stay(s, first, customer, pass, 30, null);
            refused(get(s, history(customer, "")), 404, "NOT_FOUND");
            refused(get(s, history(999_999_999L, "")), 404, "NOT_FOUND");
        }
        try (var s = login(foreign)) { assertThat(get(s, history(customer, "")).statusCode()).isEqualTo(200); }
        try (var s = login(owner)) { assertThat(get(s, history(customer, "")).statusCode()).isEqualTo(200); }
    }

    @Test void aPaymentAtTheBranchPutsTheCustomerInScopeButOnlyWithThatBranchsPasses() throws Exception {
        long customer = customer(first, "양쪽 구매");
        long atFirst = pass(customer, first, time(600));
        long atSecond = pass(customer, second, time(300));
        try (var s = login(foreign)) {
            var history = ok(s, history(customer, ""));
            assertThat(history.get("passes")).hasSize(1);
            assertThat(history.get("passes").get(0).get("passId").asLong()).isEqualTo(atSecond);
            assertThat(history.get("events")).hasSize(1);
            assertThat(history.get("events").get(0).get("passId").asLong()).isEqualTo(atSecond);
        }
        try (var s = login(clerk)) {
            var history = ok(s, history(customer, ""));
            assertThat(history.get("passes")).hasSize(1);
            assertThat(history.get("passes").get(0).get("passId").asLong()).isEqualTo(atFirst);
        }
    }

    @Test void aStayHereOnAPassBoughtElsewhereIsNotInTheHistoryOfThisBranch() throws Exception {
        long customer = customer(first, "다른 지점 이용권");
        long own = pass(customer, first, time(600));
        long elsewhere = pass(customer, second, time(300));
        try (var s = login(clerk)) {
            stay(s, first, customer, elsewhere, 30, "이 지점 퇴실");
            var history = ok(s, history(customer, ""));
            assertThat(history.get("passes")).hasSize(1);
            assertThat(ledgerOf(history, own).get("remainingMinutes").asLong()).isEqualTo(600);
            assertThat(events(history, "이용")).isEmpty();
        }
    }

    @Test void theScopeParameterNarrowsTheCustomerAndItsPasses() throws Exception {
        long customer = customer(first, "범위 조회");
        pass(customer, first, time(600));
        try (var s = login(dual)) {
            refused(get(s, history(customer, "?scope=" + second)), 404, "NOT_FOUND");
            assertThat(ok(s, history(customer, "?scope=" + first)).get("passes")).hasSize(1);
        }
        try (var s = login(clerk)) { refused(get(s, history(customer, "?scope=" + second)), 403, "FORBIDDEN"); }
        try (var s = login(clerk)) { refused(get(s, history(customer, "?scope=abc")), 422, "VALIDATION_FAILED"); }
    }

    @Test void aCustomerWithNoPassesInScopeHasAnEmptyHistory() throws Exception {
        long customer = customer(first, "구매 없음");
        try (var s = login(clerk)) {
            var history = ok(s, history(customer, ""));
            assertThat(history.get("events")).isEmpty();
            assertThat(history.get("passes")).isEmpty();
        }
    }

    // ---- real concurrency -----------------------------------------------------------------------------------

    @Test void everyHistoryReadDuringAdjustmentsAndCheckOutsReconciles() throws Exception {
        long customer = customer(first, "읽기 경합");
        long pass = pass(customer, first, time(100_000));
        var done = new AtomicBoolean();
        try (var desk = login(payer); var reader = login(clerk)) {
            var calls = new ArrayList<Callable<Integer>>();
            calls.add(() -> {
                try {
                    for (int index = 0; index < 8; index++) {
                        adjusted(desk, pass, change(pass, "remainingMinutes", 100_000L - index));
                        long session = entered(desk, seat(first), customer, pass);
                        clock.fixed = clock.fixed.plusSeconds(600);
                        left(desk, session, null);
                    }
                    return 0;
                } finally {
                    done.set(true);
                }
            });
            for (int thread = 0; thread < 3; thread++) calls.add(() -> {
                int reads = 0;
                while (!done.get()) {
                    var check = ok(reader, history(customer, "")).get("passes").get(0);
                    assertThat(check.get("issuedMinutes").asLong() + check.get("ledgerMinutes").asLong())
                            .as(check.toString()).isEqualTo(check.get("remainingMinutes").asLong());
                    reads++;
                }
                return reads;
            });
            var reads = race(calls);
            assertThat(reads.stream().mapToInt(Integer::intValue).sum()).isPositive();
        }
    }
}
