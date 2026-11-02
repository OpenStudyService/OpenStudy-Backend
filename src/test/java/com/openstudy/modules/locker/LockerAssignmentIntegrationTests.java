package com.openstudy.modules.locker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.locker.repository.LockerAssignmentChargeRepository;
import com.openstudy.modules.locker.repository.LockerAssignmentRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Issue #71: paid assignment, extension and return over HTTP and MySQL, without concurrency (space-operations-api.md). */
class LockerAssignmentIntegrationTests extends LockerIntegrationSupport {
    static final Set<String> ASSIGNMENT_FIELDS = Set.of("id", "version", "status", "branchId", "locker", "customer", "startDate", "endDate", "overdue",
            "returnedAt", "assignedBy", "lastChangedBy", "reason", "charges", "createdAt", "updatedAt");
    static final Set<String> CHARGE_FIELDS = Set.of("id", "kind", "startDate", "endDate", "terms", "ratePer4WeeksKrw", "amountKrw", "status", "paymentId",
            "createdAt", "appliedAt");

    @MockitoSpyBean LockerAssignmentChargeRepository chargeRepository;
    @MockitoSpyBean LockerAssignmentRepository assignmentRepository;
    @MockitoSpyBean EmployeeAccountService employees;

    @org.junit.jupiter.api.AfterEach void resetSpies() { Mockito.reset(chargeRepository, assignmentRepository, employees); }

    JsonNode lastCharge(JsonNode assignment) {
        var charges = assignment.get("charges");
        return charges.get(charges.size() - 1);
    }

    // ---- who may do what ------------------------------------------------------------------------------------

    @Test void assigningNeedsPaymentsWriteAtTheBranch() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        var body = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier); var book = login(reader); var staff = login(plain); var ops = login(operator);
                var other = login(outsider); var boss = login(owner)) {
            refused(assign(book, body), 403, "FORBIDDEN");
            refused(assign(staff, body), 403, "FORBIDDEN");
            refused(assign(ops, body), 403, "FORBIDDEN");
            refused(assign(other, body), 403, "FORBIDDEN");
            assertThat(payments(customer)).isZero();
            created(assign(cash, body));
            long next = locker(first, 12000);
            created(assign(boss, assignment(first, next, customer, "2026-11-01", "2026-11-29")));
        }
    }

    @Test void extendingNeedsPaymentsWriteAtTheBranchOfTheLocker() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var book = login(reader); var staff = login(plain); var ops = login(operator); var other = login(outsider)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            refused(extend(book, assignment, 1, "2026-12-27"), 403, "FORBIDDEN");
            refused(extend(staff, assignment, 1, "2026-12-27"), 403, "FORBIDDEN");
            refused(extend(ops, assignment, 1, "2026-12-27"), 403, "FORBIDDEN");
            assertThat(extend(other, assignment, 1, "2026-12-27").statusCode()).as("outside the branch it does not exist").isEqualTo(404);
            assertThat(extend(cash, 999_999_999L, 1, "2026-12-27").statusCode()).isEqualTo(404);
            assertThat(payments(customer)).isEqualTo(1);
            ok(extend(cash, assignment, 1, "2026-12-27"));
        }
    }

    @Test void anyEmployeeOfTheBranchReturns() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain); var other = login(outsider)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            assertThat(giveBack(other, assignment, 1).statusCode()).isEqualTo(404);
            assertThat(giveBack(staff, 999_999_999L, 1).statusCode()).isEqualTo(404);
            assertThat(ok(giveBack(staff, assignment, 1)).get("status").asString()).isEqualTo("반납");
        }
    }

    // ---- the checks, in the contract's order ----------------------------------------------------------------

    @Test void theChecksRunInTheOrderOfTheContract() throws Exception {
        long id = locker(first, 12000), elsewhere = locker(second, 12000);
        long customer = customer(first), hidden = customer(second);
        var good = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        var wrongLength = with(good, "endDate", "2026-12-01");
        try (var cash = login(cashier); var both = login(twoBranches); var book = login(reader); var ops = login(operator)) {
            refused(assign(book, with(good, "lockerId", "999999999")), 403, "FORBIDDEN");
            assertThat(assign(cash, with(good, "lockerId", "999999999")).statusCode()).isEqualTo(404);
            assertThat(assign(cash, with(good, "lockerId", Long.toString(elsewhere))).statusCode()).as("a locker out of reach").isEqualTo(404);
            refused(assign(both, with(good, "lockerId", Long.toString(elsewhere))), 422, "LOCKER_BRANCH_MISMATCH");
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            jdbc.update("UPDATE lockers SET status='BLOCKED' WHERE id=?", id);
            refused(assign(cash, wrongLength), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE branches SET status='ACTIVE' WHERE id=?", first);
            refused(assign(cash, wrongLength), 422, "LOCKER_NOT_AVAILABLE");
            jdbc.update("UPDATE lockers SET status='AVAILABLE' WHERE id=?", id);
            refused(assign(cash, with(wrongLength, "customerId", "999999999")), 422, "LOCKER_TERM_INVALID");
            refused(assign(cash, with(with(good, "startDate", "2026-10-31"), "endDate", "2026-11-28")), 422, "LOCKER_TERM_INVALID");
            assertThat(assign(cash, with(good, "customerId", "999999999")).statusCode()).isEqualTo(404);
            assertThat(assign(cash, with(good, "customerId", Long.toString(hidden))).statusCode()).as("outside the lookup scope").isEqualTo(404);
            jdbc.update("UPDATE customers SET status='RESTRICTED' WHERE id=?", customer);
            refused(assign(cash, good), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='INACTIVE' WHERE id=?", customer);
            refused(assign(cash, good), 409, "CUSTOMER_NOT_ACTIVE");
            jdbc.update("UPDATE customers SET status='NORMAL' WHERE id=?", customer);
            assertThat(payments(customer)).as("no refusal leaves a payment").isZero();
            created(assign(cash, good));
        }
    }

    @Test void anOverdueAssignmentIsReportedBeforeAnOverlap() throws Exception {
        long id = locker(first, 12000);
        long one = customer(first), two = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long late = assigned(cash, assignment(first, id, one, "2026-11-01", "2026-11-29"));
            at("2026-12-01");
            refused(assign(cash, assignment(first, id, two, "2026-12-01", "2026-12-29")), 409, "LOCKER_NOT_RETURNED");
            refused(assign(cash, assignment(first, id, two, "2027-06-01", "2027-06-29")), 409, "LOCKER_NOT_RETURNED");
            assertThat(payments(two)).isZero();
            ok(giveBack(staff, late, 1));
            created(assign(cash, assignment(first, id, two, "2026-12-01", "2026-12-29")));
        }
    }

    @Test void aLockerPastItsEndDateCannotBeAssignedFromThatVeryDay() throws Exception {
        long id = locker(first, 12000);
        long one = customer(first), two = customer(first);
        try (var cash = login(cashier)) {
            assigned(cash, assignment(first, id, one, "2026-11-01", "2026-11-29"));
            at("2026-11-29");
            refused(assign(cash, assignment(first, id, two, "2026-11-29", "2026-12-27")), 409, "LOCKER_NOT_RETURNED");
        }
    }

    @Test void theDayAnAssignmentEndsIsFreeForTheNextOne() throws Exception {
        long id = locker(first, 12000);
        long one = customer(first), two = customer(first), three = customer(first);
        try (var cash = login(cashier)) {
            assigned(cash, assignment(first, id, one, "2026-11-01", "2026-11-29"));
            refused(assign(cash, assignment(first, id, two, "2026-11-28", "2026-12-26")), 409, "LOCKER_PERIOD_CONFLICT");
            refused(assign(cash, assignment(first, id, two, "2026-11-01", "2026-11-29")), 409, "LOCKER_PERIOD_CONFLICT");
            assigned(cash, assignment(first, id, two, "2026-11-29", "2026-12-27"));
            at("2026-11-28");
            refused(assign(cash, assignment(first, id, three, "2026-11-28", "2026-12-26")), 409, "LOCKER_PERIOD_CONFLICT");
            assertThat(payments(three)).isZero();
            long before = locker(first, 12000);
            assigned(cash, assignment(first, before, three, "2026-12-10", "2027-01-07"));
            refused(assign(cash, assignment(first, before, three, "2026-11-28", "2026-12-26")), 409, "LOCKER_PERIOD_CONFLICT");
            assigned(cash, assignment(first, before, three, "2027-01-07", "2027-02-04"));
        }
    }

    @Test void theTermIsAMultipleOf28Days() throws Exception {
        long customer = customer(first);
        try (var cash = login(cashier)) {
            for (String end : List.of("2026-11-28", "2026-11-30", "2026-12-26", "2026-12-28", "2026-11-01", "2026-10-04")) {
                refused(assign(cash, assignment(first, locker(first, 12000), customer, "2026-11-01", end)), 422, "LOCKER_TERM_INVALID");
            }
            assertThat(payments(customer)).isZero();
            var four = created(assign(cash, assignment(first, locker(first, 12000), customer, "2026-11-01", "2026-11-29")));
            assertThat(four.get("charges").get(0).get("terms").asInt()).as("28 days").isEqualTo(1);
            var eight = created(assign(cash, assignment(first, locker(first, 12000), customer, "2026-11-01", "2026-12-27")));
            assertThat(eight.get("charges").get(0).get("terms").asInt()).as("56 days").isEqualTo(2);
        }
    }

    @Test void theStartIsTodayInKoreaOrLater() throws Exception {
        long customer = customer(first);
        try (var cash = login(cashier)) {
            created(assign(cash, assignment(first, locker(first, 12000), customer, "2026-11-01", "2026-11-29")));
            refused(assign(cash, assignment(first, locker(first, 12000), customer, "2026-10-31", "2026-11-28")), 422, "LOCKER_TERM_INVALID");
            atInstant("2026-10-31T14:59:59.999999Z");
            created(assign(cash, assignment(first, locker(first, 12000), customer, "2026-10-31", "2026-11-28")));
            atInstant("2026-10-31T15:00:00Z");
            refused(assign(cash, assignment(first, locker(first, 12000), customer, "2026-10-31", "2026-11-28")), 422, "LOCKER_TERM_INVALID");
            created(assign(cash, assignment(first, locker(first, 12000), customer, "2026-11-01", "2026-11-29")));
        }
    }

    @Test void malformedInputIsRefusedBeforeAnything() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        var good = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier)) {
            refused(assign(cash, with(good, "startDate", "2026-02-30")), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "startDate", "2026/11/01")), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "endDate", "+12026-11-29")), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "endDate", 20261129)), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "method", "수표")), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "mockOutcome", "MAYBE")), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "amountKrw", 1)), 422, "VALIDATION_FAILED");
            refused(assign(cash, with(good, "terms", 1)), 422, "VALIDATION_FAILED");
            refused(assign(cash, good, null), 422, "VALIDATION_FAILED");
            refused(assign(cash, good, "short"), 422, "VALIDATION_FAILED");
            assertThat(payments(customer)).isZero();
        }
    }

    @Test void anAmountBeyondTheSafeRangeIsRefusedNotOverflowed() throws Exception {
        long id = locker(first, 9_007_199_254_740_991L);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            refused(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-12-27")), 422, "VALIDATION_FAILED");
            String farAway = java.time.LocalDate.parse("2026-11-01").plusDays(28L * 103_000).toString();
            refused(assign(cash, assignment(first, id, customer, "2026-11-01", farAway)), 422, "VALIDATION_FAILED");
            assertThat(payments(customer)).isZero();
            created(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
        }
    }

    // ---- charging -----------------------------------------------------------------------------------------------

    void chargesExactly(long rate, String start, String end, int days, int terms, long amount) throws Exception {
        long id = locker(first, rate);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            var made = created(assign(cash, assignment(first, id, customer, start, end)));
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(start), java.time.LocalDate.parse(end))).isEqualTo(days);
            var charge = made.get("charges").get(0);
            assertThat(charge.get("terms").asInt()).isEqualTo(terms);
            assertThat(charge.get("ratePer4WeeksKrw").asLong()).isEqualTo(rate);
            assertThat(charge.get("amountKrw").asLong()).isEqualTo(amount);
            long assignment = made.get("id").asLong();
            var row = jdbc.queryForMap("SELECT c.charged_4week_terms t,c.rate_per_4weeks_snapshot_krw r,c.amount_krw a,c.charge_kind k,c.status s,"
                    + "DATE_FORMAT(c.start_date,'%Y-%m-%d') f,DATE_FORMAT(c.end_date,'%Y-%m-%d') e,p.amount_krw pa,p.purpose pu,p.status ps,p.product_id pr,p.method m "
                    + "FROM locker_assignment_charges c JOIN payments p ON p.id=c.payment_id WHERE c.assignment_id=?", assignment);
            assertThat(((Number) row.get("t")).intValue()).isEqualTo(terms);
            assertThat(((Number) row.get("r")).longValue()).isEqualTo(rate);
            assertThat(((Number) row.get("a")).longValue()).isEqualTo(amount);
            assertThat(((Number) row.get("pa")).longValue()).as("the payment is the charge").isEqualTo(amount);
            assertThat(row.get("k")).isEqualTo("INITIAL");
            assertThat(row.get("s")).isEqualTo("APPLIED");
            assertThat(row.get("f")).isEqualTo(start);
            assertThat(row.get("e")).isEqualTo(end);
            assertThat(row.get("pu")).isEqualTo("LOCKER");
            assertThat(row.get("ps")).isEqualTo("APPROVED");
            assertThat(row.get("pr")).isNull();
            assertThat(row.get("m")).isEqualTo("CARD");
            assertThat(text("SELECT DATE_FORMAT(start_date,'%Y-%m-%d') FROM locker_assignments WHERE id=?", assignment)).isEqualTo(start);
            assertThat(endDate(assignment)).isEqualTo(end);
        }
    }

    @Test void theContractsFirstExampleCharges12000() throws Exception { chargesExactly(12_000, "2026-11-01", "2026-11-29", 28, 1, 12_000); }

    @Test void theContractsSecondExampleCharges24000() throws Exception { chargesExactly(12_000, "2026-11-01", "2026-12-27", 56, 2, 24_000); }

    @Test void theContractsThirdExampleCharges45000() throws Exception { chargesExactly(15_000, "2026-11-01", "2027-01-24", 84, 3, 45_000); }

    @Test void theContractsFourthExampleIsRefused() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            refused(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-12-01")), 422, "LOCKER_TERM_INVALID");
            assertThat(payments(customer)).isZero();
        }
    }

    @Test void theContractsExtensionExampleCharges12000() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            var made = created(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            long assignment = made.get("id").asLong();
            var extended = ok(extend(cash, assignment, 1, "2026-12-27"));
            assertThat(extended.get("endDate").asString()).isEqualTo("2026-12-27");
            assertThat(extended.get("startDate").asString()).as("the start stays").isEqualTo("2026-11-01");
            assertThat(extended.get("version").asLong()).isEqualTo(2);
            assertThat(extended.get("lastChangedBy").get("id").asLong()).isEqualTo(cashier);
            assertThat(extended.get("charges")).hasSize(2);
            var charge = lastCharge(extended);
            assertThat(charge.get("kind").asString()).isEqualTo("연장");
            assertThat(charge.get("status").asString()).isEqualTo("적용");
            assertThat(charge.get("startDate").asString()).as("from the current end date").isEqualTo("2026-11-29");
            assertThat(charge.get("endDate").asString()).isEqualTo("2026-12-27");
            assertThat(charge.get("terms").asInt()).isEqualTo(1);
            assertThat(charge.get("ratePer4WeeksKrw").asLong()).isEqualTo(12_000);
            assertThat(charge.get("amountKrw").asLong()).isEqualTo(12_000);
            assertThat(endDate(assignment)).isEqualTo("2026-12-27");
            assertThat(count("SELECT COUNT(*) FROM locker_assignment_charges WHERE assignment_id=? AND charge_kind='EXTENSION' AND status='APPLIED' "
                    + "AND start_date='2026-11-29' AND end_date='2026-12-27' AND applied_at IS NOT NULL", assignment)).isEqualTo(1);
            assertThat(count("SELECT SUM(amount_krw) FROM payments WHERE customer_id=? AND status='APPROVED'", customer)).isEqualTo(24_000);
        }
    }

    @Test void anExtensionUsesTheRateOfThatMomentAndKeepsEarlierSnapshots() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var ops = login(operator)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            ok(patch(ops, "/api/lockers/" + id, Map.of("version", 1, "ratePer4WeeksKrw", 15_000)));
            var extended = ok(extend(cash, assignment, 1, "2027-01-24"));
            assertThat(extended.get("charges").get(0).get("ratePer4WeeksKrw").asLong()).as("the first charge's snapshot").isEqualTo(12_000);
            assertThat(extended.get("charges").get(0).get("amountKrw").asLong()).isEqualTo(12_000);
            var second = lastCharge(extended);
            assertThat(second.get("terms").asInt()).isEqualTo(2);
            assertThat(second.get("ratePer4WeeksKrw").asLong()).isEqualTo(15_000);
            assertThat(second.get("amountKrw").asLong()).isEqualTo(30_000);
            ok(applyRate(ops, first, 20_000, key()));
            var after = ok(get(cash, "/api/locker-assignments/" + assignment));
            assertThat(after.get("charges").get(1).get("ratePer4WeeksKrw").asLong()).as("the rate moves, the record does not").isEqualTo(15_000);
            assertThat(count("SELECT SUM(amount_krw) FROM payments WHERE customer_id=?", customer)).isEqualTo(42_000);
        }
    }

    @Test void anApprovedAssignmentRecordsEverythingInOneGo() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            var made = created(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            assertThat(fields(made)).isEqualTo(ASSIGNMENT_FIELDS);
            assertThat(fields(made.get("customer"))).containsExactlyInAnyOrder("id", "memberNo", "name");
            assertThat(fields(made.get("locker"))).containsExactlyInAnyOrder("id", "lockerNo");
            assertThat(fields(made.get("charges").get(0))).isEqualTo(CHARGE_FIELDS);
            assertThat(made.get("status").asString()).isEqualTo("배정");
            assertThat(made.get("branchId").asLong()).isEqualTo(first);
            assertThat(made.get("overdue").asBoolean()).isFalse();
            assertThat(made.get("returnedAt").isNull()).isTrue();
            assertThat(made.get("lastChangedBy").isNull()).isTrue();
            assertThat(made.get("assignedBy").get("name").asString()).isEqualTo("계산 직원");
            var charge = made.get("charges").get(0);
            assertThat(charge.get("kind").asString()).isEqualTo("최초");
            assertThat(charge.get("status").asString()).isEqualTo("적용");
            assertThat(charge.get("appliedAt").asString()).isEqualTo(clock.fixed.toString());
            long assignment = made.get("id").asLong();
            assertThat(status(assignment)).isEqualTo("ACTIVE");
            assertThat(audits("locker-assignments", assignment, "ACTIVE")).isEqualTo(1);
            assertThat(events(first, "locker-assignments")).isEqualTo(1);
            assertThat(events(first, "payments")).isEqualTo(1);
            assertThat(lockerVersion(id)).as("assigning does not edit the ledger").isZero();
            assertThat(ok(get(cash, "/api/locker-assignments/" + assignment))).isEqualTo(made);
        }
    }

    @Test void aLockerPaymentNamesItsAssignmentAndIsNotRefundable() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var book = login(reader); var boss = login(owner)) {
            var made = created(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            long assignment = made.get("id").asLong();
            var extended = ok(extend(cash, assignment, 1, "2026-12-27"));
            for (var charge : extended.get("charges")) {
                long payment = charge.get("paymentId").asLong();
                var detail = ok(get(book, "/api/payments/" + payment));
                assertThat(detail.get("purpose").asString()).isEqualTo("사물함");
                assertThat(detail.get("lockerAssignmentId").asLong()).isEqualTo(assignment);
                assertThat(detail.get("roomReservationId").isNull()).isTrue();
                assertThat(detail.get("productId").isNull()).isTrue();
                assertThat(detail.get("productName").isNull()).isTrue();
                assertThat(detail.get("passId").isNull()).isTrue();
                assertThat(detail.get("canRefund").asBoolean()).isFalse();
                assertThat(ok(get(boss, "/api/payments/" + payment)).get("canRefund").asBoolean()).isFalse();
                refused(post(boss, "/api/payments/" + payment + "/refund", Map.of("reason", "고객 요청"), key()), 409, "PAYMENT_NOT_REFUNDABLE");
            }
            assertThat(refunds(customer)).isZero();
            assertThat(ok(get(book, "/api/payments?customerId=" + customer)).get("meta").get("total").asLong()).isEqualTo(2);
        }
    }

    @Test void aLockerPaymentCannotBeMadeThroughThePaymentApi() throws Exception {
        long customer = customer(first);
        try (var cash = login(cashier)) {
            var sale = new java.util.LinkedHashMap<String, Object>();
            sale.put("customerId", Long.toString(customer)); sale.put("branchId", Long.toString(first)); sale.put("method", "카드");
            refused(post(cash, "/api/payments", with(sale, "purpose", "사물함"), key()), 422, "VALIDATION_FAILED");
            assertThat(payments(customer)).isZero();
        }
    }

    // ---- failed payments ----------------------------------------------------------------------------------------

    @Test void aFailedPaymentLeavesARecordAndHoldsNoPeriod() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first), next = customer(first);
        var body = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier); var staff = login(plain)) {
            var made = created(assign(cash, failed(body)));
            long assignment = made.get("id").asLong();
            assertThat(made.get("status").asString()).isEqualTo("결제 실패");
            var charge = made.get("charges").get(0);
            assertThat(charge.get("status").asString()).isEqualTo("실패");
            assertThat(charge.get("appliedAt").isNull()).isTrue();
            assertThat(charge.get("amountKrw").asLong()).isEqualTo(12_000);
            assertThat(status(assignment)).isEqualTo("PAYMENT_FAILED");
            assertThat(text("SELECT status FROM payments WHERE customer_id=?", customer)).isEqualTo("FAILED");
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=? AND failed_at IS NOT NULL AND approved_at IS NULL", customer)).isEqualTo(1);
            assertThat(ok(get(staff, "/api/lockers/" + id)).get("assignmentState").asString()).isEqualTo("사용 가능");
            created(assign(cash, assignment(first, id, next, "2026-11-01", "2026-11-29")));
        }
    }

    @Test void aFailedPaymentIsRetriedWithANewKey() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        var body = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier)) {
            String key = key();
            var one = assign(cash, failed(body), key);
            created(one);
            var again = assign(cash, failed(body), key);
            assertThat(again.statusCode()).isEqualTo(201);
            assertThat(again.body()).as("the failure is the stored result").isEqualTo(one.body());
            assertThat(payments(customer)).isEqualTo(1);
            refused(assign(cash, body, key), 409, "IDEMPOTENCY_CONFLICT");
            var retried = created(assign(cash, body));
            assertThat(retried.get("status").asString()).isEqualTo("배정");
            assertThat(retried.get("id").asLong()).isNotEqualTo(node(one).get("id").asLong());
            assertThat(payments(customer)).isEqualTo(2);
            assertThat(assignments(customer)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM locker_assignments WHERE customer_id=? AND status='ACTIVE'", customer)).isEqualTo(1);
        }
    }

    @Test void aConflictIsDecidedBeforeTheFailedOutcomeToo() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first), other = customer(first);
        try (var cash = login(cashier)) {
            assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            refused(assign(cash, failed(assignment(first, id, other, "2026-11-01", "2026-11-29"))), 409, "LOCKER_PERIOD_CONFLICT");
            assertThat(payments(other)).isZero();
            assertThat(assignments(other)).isZero();
        }
    }

    @Test void aFailedExtensionKeepsTheEndDateAndCanBeRetried() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            String key = key();
            var failedOne = extend(cash, assignment, with(extension(1, "2026-12-27"), "mockOutcome", "FAILED"), key);
            var body = ok(failedOne);
            assertThat(body.get("status").asString()).isEqualTo("배정");
            assertThat(body.get("endDate").asString()).isEqualTo("2026-11-29");
            assertThat(body.get("version").asLong()).as("nothing on the assignment changed").isEqualTo(1);
            assertThat(lastCharge(body).get("status").asString()).isEqualTo("실패");
            assertThat(lastCharge(body).get("kind").asString()).isEqualTo("연장");
            assertThat(lastCharge(body).get("appliedAt").isNull()).isTrue();
            assertThat(endDate(assignment)).isEqualTo("2026-11-29");
            assertThat(charges(assignment)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM payments WHERE customer_id=? AND status='FAILED'", customer)).isEqualTo(1);
            var replayed = extend(cash, assignment, with(extension(1, "2026-12-27"), "mockOutcome", "FAILED"), key);
            assertThat(replayed.body()).isEqualTo(failedOne.body());
            assertThat(charges(assignment)).isEqualTo(2);
            var retried = ok(extend(cash, assignment, 1, "2026-12-27"));
            assertThat(retried.get("endDate").asString()).isEqualTo("2026-12-27");
            assertThat(charges(assignment)).isEqualTo(3);
        }
    }

    // ---- request keys --------------------------------------------------------------------------------------------

    @Test void aRetriedAssignmentAnswersTheFirstResultAndPaysOnce() throws Exception {
        long id = locker(first, 12_000), otherLocker = locker(first, 12_000);
        long customer = customer(first);
        var body = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier)) {
            String key = key();
            var one = assign(cash, body, key);
            created(one);
            var two = assign(cash, body, key);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(two.body()).isEqualTo(one.body());
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(assignments(customer)).isEqualTo(1);
            assertThat(charges(node(one).get("id").asLong())).isEqualTo(1);
            refused(assign(cash, with(body, "endDate", "2026-12-27"), key), 409, "IDEMPOTENCY_CONFLICT");
            refused(assign(cash, with(body, "method", "현금"), key), 409, "IDEMPOTENCY_CONFLICT");
            refused(assign(cash, with(body, "lockerId", Long.toString(otherLocker)), key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(payments(customer)).isEqualTo(1);
        }
    }

    @Test void aRefusalIsNotStoredAndAnotherEmployeesKeyIsItsOwn() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first), other = customer(first);
        try (var cash = login(cashier); var both = login(twoBranches)) {
            String key = key();
            long held = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            var body = assignment(first, id, other, "2026-11-01", "2026-11-29");
            refused(assign(cash, body, key), 409, "LOCKER_PERIOD_CONFLICT");
            ok(giveBack(cash, held, 1));
            created(assign(cash, body, key));
            refused(assign(both, body, key), 409, "LOCKER_PERIOD_CONFLICT");
            assertThat(payments(other)).isEqualTo(1);
        }
    }

    @Test void aKeyReusedAfterItsRecordExpiredIsAConflictAndChargesNothing() throws Exception {
        long id = locker(first, 12_000), freed = locker(first, 12_000);
        long customer = customer(first);
        var body = assignment(first, freed, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier)) {
            String key = key();
            long held = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            long first1 = created(assign(cash, body, key)).get("id").asLong();
            ok(giveBack(cash, first1, 1));
            clock.fixed = clock.fixed.plus(Duration.ofHours(25));
            refused(assign(cash, with(body, "startDate", "2026-11-02"), key), 422, "LOCKER_TERM_INVALID");
            refused(assign(cash, with(with(body, "startDate", "2026-11-03"), "endDate", "2026-12-01"), key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(payments(customer)).isEqualTo(2);
            assertThat(status(held)).isEqualTo("ACTIVE");
        }
    }

    @Test void aRetriedExtensionAnswersTheFirstResultEvenWithItsOldVersion() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            String key = key();
            var one = extend(cash, assignment, extension(1, "2026-12-27"), key);
            ok(one);
            var two = extend(cash, assignment, extension(1, "2026-12-27"), key);
            assertThat(two.statusCode()).isEqualTo(200);
            assertThat(two.body()).isEqualTo(one.body());
            assertThat(payments(customer)).isEqualTo(2);
            assertThat(charges(assignment)).isEqualTo(2);
            assertThat(endDate(assignment)).isEqualTo("2026-12-27");
            refused(extend(cash, assignment, extension(1, "2027-01-24"), key), 409, "IDEMPOTENCY_CONFLICT");
            refused(extend(cash, assignment, with(extension(1, "2026-12-27"), "method", "현금"), key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(payments(customer)).isEqualTo(2);
            refused(extend(cash, assignment, extension(1, "2026-12-27"), null), 422, "VALIDATION_FAILED");
        }
    }

    // ---- extensions -----------------------------------------------------------------------------------------------

    @Test void anExtensionIsAMultipleOf28DaysFromTheCurrentEnd() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            for (String end : List.of("2026-12-26", "2026-12-28", "2026-11-29", "2026-11-01", "2026-11-28")) {
                refused(extend(cash, assignment, 1, end), 422, "LOCKER_TERM_INVALID");
            }
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(lastCharge(ok(extend(cash, assignment, 1, "2027-01-24"))).get("terms").asInt()).isEqualTo(2);
        }
    }

    @Test void onlyAnActiveAssignmentIsExtended() throws Exception {
        long id = locker(first, 12_000), other = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            ok(giveBack(staff, assignment, 1));
            refused(extend(cash, assignment, 2, "2026-12-27"), 409, "INVALID_STATE");
            long failedOne = created(assign(cash, failed(assignment(first, other, customer, "2026-11-01", "2026-11-29")))).get("id").asLong();
            refused(extend(cash, failedOne, 1, "2026-12-27"), 409, "INVALID_STATE");
            var stale = extend(cash, assignment, 1, "2026-12-27");
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/locker-assignments/" + assignment);
            assertThat(payments(customer)).isEqualTo(2);
        }
    }

    @Test void anExtensionOverlappingAnotherAssignmentIsRefusedWithoutPayment() throws Exception {
        long id = locker(first, 12_000);
        long one = customer(first), two = customer(first);
        try (var cash = login(cashier)) {
            long early = assigned(cash, assignment(first, id, one, "2026-11-01", "2026-11-29"));
            long late = assigned(cash, assignment(first, id, two, "2027-01-24", "2027-02-21"));
            refused(extend(cash, early, 1, "2027-02-21"), 409, "LOCKER_PERIOD_CONFLICT");
            refused(extend(cash, early, with(extension(1, "2027-02-21"), "mockOutcome", "FAILED"), key()), 409, "LOCKER_PERIOD_CONFLICT");
            assertThat(payments(one)).isEqualTo(1);
            assertThat(charges(early)).isEqualTo(1);
            assertThat(endDate(early)).isEqualTo("2026-11-29");
            var fits = ok(extend(cash, early, 1, "2027-01-24"));
            assertThat(fits.get("endDate").asString()).as("ending the day the next one starts is fine").isEqualTo("2027-01-24");
            assertThat(status(late)).isEqualTo("ACTIVE");
        }
    }

    @Test void aPassedAssignmentIsExtendedFromItsOldEndAndPaysForThePast() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            at("2026-12-10");
            assertThat(ok(get(cash, "/api/locker-assignments/" + assignment)).get("overdue").asBoolean()).isTrue();
            var extended = ok(extend(cash, assignment, 1, "2026-12-27"));
            assertThat(lastCharge(extended).get("startDate").asString()).isEqualTo("2026-11-29");
            assertThat(lastCharge(extended).get("amountKrw").asLong()).isEqualTo(12_000);
            assertThat(extended.get("overdue").asBoolean()).isFalse();
            at("2027-02-01");
            var stillLate = ok(extend(cash, assignment, 2, "2027-01-24"));
            assertThat(stillLate.get("overdue").asBoolean()).as("a new end date in the past is still overdue").isTrue();
        }
    }

    // ---- stopped lockers and branches ----------------------------------------------------------------------------

    @Test void aBlockedLockerRefusesAssigningAndExtendingButTakesAReturn() throws Exception {
        long id = locker(first, 12_000), other = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            jdbc.update("UPDATE lockers SET status='BLOCKED' WHERE id IN (?,?)", id, other);
            refused(assign(cash, assignment(first, other, customer, "2026-11-01", "2026-11-29")), 422, "LOCKER_NOT_AVAILABLE");
            refused(extend(cash, assignment, 1, "2026-12-27"), 422, "LOCKER_NOT_AVAILABLE");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(ok(giveBack(staff, assignment, 1)).get("status").asString()).isEqualTo("반납");
        }
    }

    @Test void aStoppedBranchRefusesAssigningAndExtendingButTakesAReturn() throws Exception {
        long id = locker(first, 12_000), other = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
            refused(assign(cash, assignment(first, other, customer, "2026-11-01", "2026-11-29")), 409, "BRANCH_NOT_ACTIVE");
            refused(extend(cash, assignment, 1, "2026-12-27"), 409, "BRANCH_NOT_ACTIVE");
            jdbc.update("UPDATE lockers SET status='BLOCKED' WHERE id=?", id);
            refused(extend(cash, assignment, 1, "2026-12-27"), 409, "BRANCH_NOT_ACTIVE");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(ok(giveBack(staff, assignment, 1)).get("status").asString()).isEqualTo("반납");
        }
    }

    // ---- returning ----------------------------------------------------------------------------------------------

    @Test void anEarlyReturnFreesTheLockerAtOnceAndRefundsNothing() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first), next = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            at("2026-11-10");
            var body = with(Map.of("version", 1L), "reason", "이사");
            var back = ok(post(staff, "/api/locker-assignments/" + assignment + "/return", body, null));
            assertThat(back.get("status").asString()).isEqualTo("반납");
            assertThat(back.get("returnedAt").asString()).isEqualTo(clock.fixed.toString());
            assertThat(back.get("lastChangedBy").get("id").asLong()).isEqualTo(plain);
            assertThat(back.get("lastChangedBy").get("name").asString()).isEqualTo("일반 직원");
            assertThat(back.get("reason").asString()).isEqualTo("이사");
            assertThat(back.get("endDate").asString()).as("the paid period stays").isEqualTo("2026-11-29");
            assertThat(back.get("version").asLong()).isEqualTo(2);
            assertThat(back.get("overdue").asBoolean()).isFalse();
            assertThat(status(assignment)).isEqualTo("RETURNED");
            assertThat(endDate(assignment)).isEqualTo("2026-11-29");
            assertThat(count("SELECT last_changed_by FROM locker_assignments WHERE id=?", assignment)).isEqualTo(plain);
            assertThat(count("SELECT COUNT(*) FROM locker_assignments WHERE id=? AND returned_at IS NOT NULL", assignment)).isEqualTo(1);
            assertThat(refunds(customer)).isZero();
            assertThat(text("SELECT status FROM payments WHERE customer_id=?", customer)).isEqualTo("APPROVED");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(audits("locker-assignments", assignment, "RETURNED")).isEqualTo(1);
            assertThat(text("SELECT reason FROM audit_events WHERE entity_type='locker-assignments' AND entity_id=? AND action='RETURNED'", assignment)).isEqualTo("이사");
            assertThat(ok(get(staff, "/api/lockers/" + id)).get("assignmentState").asString()).isEqualTo("사용 가능");
            var again = created(assign(cash, assignment(first, id, next, "2026-11-10", "2026-12-08")));
            assertThat(again.get("status").asString()).as("the same locker, the same days").isEqualTo("배정");
        }
    }

    @Test void aReturnChecksVersionAndState() throws Exception {
        long id = locker(first, 12_000), other = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            refused(post(staff, "/api/locker-assignments/" + assignment + "/return", Map.of(), null), 422, "VALIDATION_FAILED");
            refused(post(staff, "/api/locker-assignments/" + assignment + "/return", Map.of("version", 1, "reason", " "), null), 422, "VALIDATION_FAILED");
            refused(post(staff, "/api/locker-assignments/" + assignment + "/return", Map.of("version", 1, "endDate", "2026-11-10"), null), 422, "VALIDATION_FAILED");
            var stale = giveBack(staff, assignment, 5);
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo("/api/locker-assignments/" + assignment);
            ok(giveBack(staff, assignment, 1));
            refused(giveBack(staff, assignment, 2), 409, "INVALID_STATE");
            long failedOne = created(assign(cash, failed(assignment(first, other, customer, "2026-11-01", "2026-11-29")))).get("id").asLong();
            refused(giveBack(staff, failedOne, 1), 409, "INVALID_STATE");
        }
    }

    @Test void aPlannedAssignmentCanBeClosedTheSameWay() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long planned = assigned(cash, assignment(first, id, customer, "2026-12-01", "2026-12-29"));
            assertThat(ok(giveBack(staff, planned, 1)).get("status").asString()).isEqualTo("반납");
            assertThat(refunds(customer)).isZero();
            created(assign(cash, assignment(first, id, customer, "2026-12-01", "2026-12-29")));
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    @Test void chargesFollowThePaymentPermissionsOfTheBranch() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var book = login(reader); var staff = login(plain); var ops = login(operator); var boss = login(owner)) {
            var made = created(assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            String path = "/api/locker-assignments/" + made.get("id").asLong();
            assertThat(ok(get(book, path)).get("charges")).hasSize(1);
            assertThat(ok(get(cash, path)).get("charges")).hasSize(1);
            assertThat(ok(get(boss, path)).get("charges")).hasSize(1);
            assertThat(ok(get(staff, path)).get("charges").isNull()).isTrue();
            assertThat(ok(get(ops, path)).get("charges").isNull()).isTrue();
            assertThat(ok(get(staff, "/api/locker-assignments")).get("items").get(0).get("charges").isNull()).isTrue();
            assertThat(ok(get(book, "/api/locker-assignments")).get("items").get(0).get("charges")).hasSize(1);
            assertThat(fields(ok(get(staff, path)))).as("the field stays, with no value").isEqualTo(ASSIGNMENT_FIELDS);
            assertThat(ok(get(staff, path)).get("customer").get("name").asText()).isEqualTo("사물함 고객");
        }
    }

    @Test void chargesAreDecidedRowByRowInAMixedScope() throws Exception {
        long mixed = person("EMPLOYEE", "혼합 직원");
        member(mixed, first, "payments:read");
        member(mixed, second);
        long here = locker(first, 12_000), there = locker(second, 12_000);
        long customer = customer(first), far = customer(second);
        try (var cash = login(cashier); var other = login(outsider); var both = login(mixed)) {
            assigned(cash, assignment(first, here, customer, "2026-11-01", "2026-11-29"));
            assigned(other, assignment(second, there, far, "2026-11-01", "2026-11-29"));
            var items = ok(get(both, "/api/locker-assignments")).get("items");
            assertThat(items).hasSize(2);
            for (var item : items) {
                if (item.get("branchId").asLong() == first) assertThat(item.get("charges")).hasSize(1);
                else assertThat(item.get("charges").isNull()).isTrue();
            }
        }
    }

    @Test void assignmentsOfAnotherBranchAreNotExposed() throws Exception {
        long here = locker(first, 12_000), there = locker(second, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var other = login(outsider); var staff = login(plain); var boss = login(owner)) {
            long theirs = assigned(boss, assignment(second, there, customer, "2026-11-01", "2026-11-29"));
            assertThat(get(staff, "/api/locker-assignments/" + theirs).statusCode()).as("a customer of ours at another branch").isEqualTo(404);
            assertThat(ok(get(staff, "/api/locker-assignments?customerId=" + customer)).get("meta").get("total").asLong()).isZero();
            assertThat(ok(get(staff, "/api/locker-assignments?lockerId=" + there)).get("meta").get("total").asLong()).isZero();
            refused(get(staff, "/api/locker-assignments?scope=" + second), 403, "FORBIDDEN");
            assertThat(get(staff, "/api/locker-assignments/999999999").statusCode()).isEqualTo(404);
            long ours = assigned(cash, assignment(first, here, customer, "2026-11-01", "2026-11-29"));
            assertThat(get(other, "/api/locker-assignments/" + ours).statusCode()).isEqualTo(404);
            assertThat(ok(get(other, "/api/locker-assignments")).get("meta").get("total").asLong()).isEqualTo(1);
        }
    }

    @Test void theListFiltersAndOrdersLatestFirst() throws Exception {
        long one = locker(first, 12_000), two = locker(first, 12_000);
        long customer = customer(first), other = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long a = assigned(cash, assignment(first, one, customer, "2026-11-01", "2026-11-29"));
            long b = created(assign(cash, failed(assignment(first, two, other, "2026-11-01", "2026-11-29")))).get("id").asLong();
            long c = assigned(cash, assignment(first, two, other, "2026-11-01", "2026-11-29"));
            ok(giveBack(staff, c, 1));
            var all = ok(get(staff, "/api/locker-assignments")).get("items");
            assertThat(all).hasSize(3);
            assertThat(all.get(0).get("id").asLong()).isEqualTo(c);
            assertThat(all.get(2).get("id").asLong()).isEqualTo(a);
            assertThat(ok(get(staff, "/api/locker-assignments?lockerId=" + one)).get("meta").get("total").asLong()).isEqualTo(1);
            assertThat(ok(get(staff, "/api/locker-assignments?customerId=" + other)).get("meta").get("total").asLong()).isEqualTo(2);
            for (var expected : Map.of("결제 실패", b, "배정", a, "반납", c).entrySet()) {
                var page = ok(get(staff, "/api/locker-assignments?status=" + java.net.URLEncoder.encode(expected.getKey(), "UTF-8")));
                assertThat(page.get("meta").get("total").asLong()).isEqualTo(1);
                assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(expected.getValue());
            }
            refused(get(staff, "/api/locker-assignments?status=ACTIVE"), 422, "VALIDATION_FAILED");
            refused(get(staff, "/api/locker-assignments?lockerId=abc"), 422, "VALIDATION_FAILED");
            refused(get(staff, "/api/locker-assignments?customerId=0"), 422, "VALIDATION_FAILED");
        }
    }

    @Test void theListLooksUpNamesOnce() throws Exception {
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            for (int index = 0; index < 4; index++) assigned(cash, assignment(first, locker(first, 12_000), customer, "2026-11-01", "2026-11-29"));
            Mockito.clearInvocations(employees, chargeRepository);
            assertThat(ok(get(cash, "/api/locker-assignments")).get("items")).hasSize(4);
            verify(employees, times(1)).names(any());
            verify(chargeRepository, times(1)).findByAssignmentIdInOrderByIdAsc(any());
            Mockito.clearInvocations(employees, chargeRepository);
            ok(get(staff, "/api/locker-assignments"));
            verify(chargeRepository, never()).findByAssignmentIdInOrderByIdAsc(any());
        }
    }

    // ---- all or nothing ---------------------------------------------------------------------------------------------

    @Test void aFailureHalfwayThroughAnAssignmentLeavesNothing() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        var body = assignment(first, id, customer, "2026-11-01", "2026-11-29");
        try (var cash = login(cashier)) {
            String key = key();
            doThrow(new IllegalStateException("charge row")).when(chargeRepository).saveAndFlush(any());
            assertThat(assign(cash, body, key).statusCode()).isEqualTo(500);
            assertThat(payments(customer)).isZero();
            assertThat(assignments(customer)).isZero();
            assertThat(events(first, "payments")).isZero();
            Mockito.reset(chargeRepository);
            created(assign(cash, body, key));
            assertThat(payments(customer)).isEqualTo(1);
        }
    }

    @Test void aFailureHalfwayThroughAnExtensionLeavesTheEndDate() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            doThrow(new IllegalStateException("assignment row")).when(assignmentRepository).saveAndFlush(any());
            assertThat(extend(cash, assignment, 1, "2026-12-27").statusCode()).isEqualTo(500);
            assertThat(endDate(assignment)).isEqualTo("2026-11-29");
            assertThat(payments(customer)).isEqualTo(1);
            assertThat(charges(assignment)).isEqualTo(1);
        }
    }

    // ---- real concurrency -----------------------------------------------------------------------------------------

    /** One side tends to win an even start, so every other round gives the second call a head start. Answers stay (one, two). */
    List<java.net.http.HttpResponse<String>> raceInTurns(int round, Callable<java.net.http.HttpResponse<String>> one,
            Callable<java.net.http.HttpResponse<String>> two) throws Exception {
        if (round % 2 == 0) return race(List.of(one, two));
        return race(List.of(() -> { Thread.sleep(40); return one.call(); }, two));
    }

    @Test void onlyOneOfManySimultaneousOverlappingAssignmentsWins() throws Exception {
        try (var one = login(cashier); var two = login(twoBranches)) {
            for (int round = 0; round < 3; round++) {
                long id = locker(first, 12_000);
                var calls = new ArrayList<Callable<java.net.http.HttpResponse<String>>>();
                var buyers = new ArrayList<Long>();
                for (int index = 0; index < 8; index++) {
                    long customer = customer(first);
                    buyers.add(customer);
                    var session = index % 2 == 0 ? one : two;
                    // Shifted starts, so the periods overlap without being the same request.
                    var start = java.time.LocalDate.parse("2026-11-01").plusDays(index % 3);
                    calls.add(() -> assign(session, assignment(first, id, customer, start.toString(), start.plusDays(28).toString())));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(code -> code == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 201) refused(response, 409, "LOCKER_PERIOD_CONFLICT");
                assertThat(count("SELECT COUNT(*) FROM locker_assignments WHERE locker_id=? AND status='ACTIVE'", id)).isEqualTo(1);
                assertThat(buyers.stream().mapToLong(this::payments).sum()).as("the losers leave no payment").isEqualTo(1);
                assertThat(buyers.stream().mapToLong(this::assignments).sum()).isEqualTo(1);
            }
        }
    }

    @Test void simultaneousPeriodsThatDoNotOverlapAllWin() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var both = login(twoBranches)) {
            var responses = race(List.<Callable<java.net.http.HttpResponse<String>>>of(
                    () -> assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")),
                    () -> assign(both, assignment(first, id, customer, "2026-11-29", "2026-12-27")),
                    () -> assign(cash, assignment(first, id, customer, "2026-12-27", "2027-01-24"))));
            assertThat(statuses(responses)).containsOnly(201);
        }
    }

    @Test void anAssignmentAndAnExtensionForTheSameDaysCannotBothWin() throws Exception {
        try (var one = login(cashier); var two = login(twoBranches)) {
            var outcomes = new ArrayList<String>();
            for (int round = 0; round < 8; round++) {
                long id = locker(first, 12_000);
                long holder = customer(first), next = customer(first);
                long held = assigned(one, assignment(first, id, holder, "2026-11-01", "2026-11-29"));
                var responses = raceInTurns(round, () -> extend(one, held, 1, "2026-12-27"),
                        () -> assign(two, assignment(first, id, next, "2026-11-29", "2026-12-27")));
                var extended = responses.get(0);
                var assigned = responses.get(1);
                assertThat(extended.statusCode() == 200).as(statuses(responses).toString()).isNotEqualTo(assigned.statusCode() == 201);
                if (extended.statusCode() == 200) {
                    refused(assigned, 409, "LOCKER_PERIOD_CONFLICT");
                    assertThat(endDate(held)).isEqualTo("2026-12-27");
                    assertThat(payments(holder)).isEqualTo(2);
                    assertThat(payments(next)).isZero();
                    outcomes.add("extended");
                } else {
                    refused(extended, 409, "LOCKER_PERIOD_CONFLICT");
                    assertThat(endDate(held)).isEqualTo("2026-11-29");
                    assertThat(payments(holder)).isEqualTo(1);
                    assertThat(payments(next)).isEqualTo(1);
                    outcomes.add("assigned");
                }
            }
            System.out.println("[locker-race] assignment vs extension: " + outcomes);
        }
    }

    @Test void simultaneousRetriesOfOneKeyPayOnce() throws Exception {
        try (var cash = login(cashier)) {
            for (int round = 0; round < 3; round++) {
                long id = locker(first, 12_000);
                long customer = customer(first);
                String key = key();
                var calls = new ArrayList<Callable<java.net.http.HttpResponse<String>>>();
                for (int index = 0; index < 8; index++)
                    calls.add(() -> assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"), key));
                var responses = race(calls);
                assertThat(statuses(responses)).containsOnly(201);
                assertThat(responses.stream().map(java.net.http.HttpResponse::body).distinct().count()).isEqualTo(1);
                assertThat(payments(customer)).isEqualTo(1);
                assertThat(assignments(customer)).isEqualTo(1);
            }
        }
    }

    @Test void simultaneousExtensionsOfOneVersionExtendOnce() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier); var both = login(twoBranches)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            var calls = new ArrayList<Callable<java.net.http.HttpResponse<String>>>();
            for (int index = 0; index < 6; index++) {
                var session = index % 2 == 0 ? cash : both;
                calls.add(() -> extend(session, assignment, 1, "2026-12-27"));
            }
            var responses = race(calls);
            assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
            for (var response : responses) if (response.statusCode() != 200) refused(response, 409, "VERSION_CONFLICT");
            assertThat(endDate(assignment)).isEqualTo("2026-12-27");
            assertThat(payments(customer)).isEqualTo(2);
        }
    }

    @Test void aReturnAndAnExtensionCannotBothHappen() throws Exception {
        try (var cash = login(cashier); var staff = login(plain)) {
            var outcomes = new ArrayList<String>();
            for (int round = 0; round < 10; round++) {
                long id = locker(first, 12_000);
                long customer = customer(first);
                long assignment = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
                var responses = raceInTurns(round, () -> extend(cash, assignment, 1, "2026-12-27"), () -> giveBack(staff, assignment, 1));
                var extended = responses.get(0);
                var back = responses.get(1);
                if (extended.statusCode() == 200) {
                    refused(back, 409, "VERSION_CONFLICT");
                    assertThat(status(assignment)).isEqualTo("ACTIVE");
                    assertThat(endDate(assignment)).isEqualTo("2026-12-27");
                    assertThat(payments(customer)).isEqualTo(2);
                    outcomes.add("extended");
                } else {
                    assertThat(back.statusCode()).as(statuses(responses).toString()).isEqualTo(200);
                    refused(extended, 409, "VERSION_CONFLICT");
                    assertThat(status(assignment)).isEqualTo("RETURNED");
                    assertThat(endDate(assignment)).isEqualTo("2026-11-29");
                    assertThat(payments(customer)).as("no money for an extension that did not happen").isEqualTo(1);
                    outcomes.add("returned");
                }
            }
            System.out.println("[locker-race] return vs extension: " + outcomes);
        }
    }

    @Test void aBranchRateAndAnAssignmentChargeWholeOldOrWholeNew() throws Exception {
        try (var ops = login(operator); var cash = login(cashier)) {
            var outcomes = new ArrayList<Long>();
            long id = locker(first, 10_000);
            long customer = customer(first);
            long rate = 10_000;
            for (int round = 0; round < 8; round++) {
                long version = lockerVersion(id);
                long next = rate + 1_000;
                String start = java.time.LocalDate.parse("2026-11-01").plusDays(28L * round).toString();
                String end = java.time.LocalDate.parse("2026-11-01").plusDays(28L * round + 56).toString();
                long previous = rate;
                var responses = raceInTurns(round, () -> applyRate(ops, first, next, key()),
                        () -> assign(cash, assignment(first, id, customer, start, end)));
                ok(responses.get(0));
                var charge = created(responses.get(1)).get("charges").get(0);
                long snapshot = charge.get("ratePer4WeeksKrw").asLong();
                assertThat(snapshot).isIn(previous, next);
                assertThat(charge.get("amountKrw").asLong()).as("terms × the rate it locked").isEqualTo(snapshot * 2);
                assertThat(rate(id)).isEqualTo(next);
                assertThat(lockerVersion(id)).as("the rate is the only change to the row").isEqualTo(version + 1);
                outcomes.add(snapshot);
                long assignment = created(responses.get(1)).get("id").asLong();
                ok(giveBack(cash, assignment, 1));
                rate = next;
            }
            System.out.println("[locker-race] snapshots under the branch rate: " + outcomes);
        }
    }

    @Test void anAssignmentWaitingForTheLockerSeesWhatCommittedMeanwhile() throws Exception {
        long id = locker(first, 12_000), other = locker(first, 12_000);
        long customer = customer(first), writerCustomer = customer(first);
        try (var cash = login(cashier)) {
            var inside = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            // A writer that holds the locker row, as an assignment does, and commits only once the request is waiting.
            var writer = java.util.concurrent.Executors.newSingleThreadExecutor();
            var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
            var wrote = writer.submit(() -> template.execute(status -> {
                jdbc.queryForList("SELECT id FROM lockers WHERE id=? FOR UPDATE", id);
                jdbc.update("INSERT INTO locker_assignments(locker_id,customer_id,start_date,end_date,status,assigned_by) "
                        + "VALUES(?,?,'2026-11-01','2026-11-29','ACTIVE',?)", id, writerCustomer, cashier);
                jdbc.update("UPDATE lockers SET rate_per_4weeks_krw=15000 WHERE id=?", other);
                inside.countDown();
                try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return 1;
            }));
            assertThat(inside.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var request = java.util.concurrent.Executors.newSingleThreadExecutor()
                    .submit(() -> assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            waitForLockWait();
            release.countDown();
            wrote.get(30, java.util.concurrent.TimeUnit.SECONDS);
            refused(request.get(60, java.util.concurrent.TimeUnit.SECONDS), 409, "LOCKER_PERIOD_CONFLICT");
            assertThat(payments(customer)).isZero();
            writer.shutdown();
        }
    }

    @Test void anAssignmentWaitingForTheLockerChargesTheRateCommittedMeanwhile() throws Exception {
        long id = locker(first, 12_000);
        long customer = customer(first);
        try (var cash = login(cashier)) {
            var inside = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var writer = java.util.concurrent.Executors.newSingleThreadExecutor();
            var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
            var wrote = writer.submit(() -> template.execute(status -> {
                jdbc.update("UPDATE lockers SET rate_per_4weeks_krw=15000 WHERE id=?", id);
                inside.countDown();
                try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return 1;
            }));
            assertThat(inside.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var request = java.util.concurrent.Executors.newSingleThreadExecutor()
                    .submit(() -> assign(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29")));
            waitForLockWait();
            release.countDown();
            wrote.get(30, java.util.concurrent.TimeUnit.SECONDS);
            var charge = created(request.get(60, java.util.concurrent.TimeUnit.SECONDS)).get("charges").get(0);
            assertThat(charge.get("ratePer4WeeksKrw").asLong()).isEqualTo(15000);
            assertThat(charge.get("amountKrw").asLong()).isEqualTo(15000);
            writer.shutdown();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    /** Until the request is really blocked on the row. Without the privilege to see that, a pause does the same job. */
    void waitForLockWait() throws Exception {
        Instant limit = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(limit)) {
            try {
                if (count("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'") > 0) return;
            } catch (RuntimeException exception) {
                Thread.sleep(1500);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The request never waited for the locker row");
    }
}
