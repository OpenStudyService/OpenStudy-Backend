package com.openstudy.modules.locker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #71: the locker ledger, the computed assignment state, the occupancy counts and the branch rate (space-operations-api.md). */
class LockerLedgerIntegrationTests extends LockerIntegrationSupport {
    static final Set<String> LOCKER_FIELDS = Set.of("id", "version", "branchId", "lockerNo", "ratePer4WeeksKrw", "status", "assignmentState", "activeAssignment");

    Map<String, Object> newLocker(long branch, String number, long rate) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("lockerNo", number); body.put("ratePer4WeeksKrw", rate);
        return body;
    }

    Map<String, Object> stop(long version, String status, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version); body.put("status", status); body.put("reason", reason);
        return body;
    }

    HttpMaker http(Session session) { return new HttpMaker(session); }

    /** The four views of one locker, read the way a screen reads them. */
    class HttpMaker {
        final Session session;
        HttpMaker(Session session) { this.session = session; }

        JsonNode detail(long locker) throws Exception { return ok(get(session, "/api/lockers/" + locker)); }

        List<Long> filtered(String state, long branch) throws Exception {
            var page = ok(get(session, "/api/lockers?scope=" + branch + "&pageSize=100&assignmentState=" + java.net.URLEncoder.encode(state, "UTF-8")));
            var ids = new ArrayList<Long>();
            page.get("items").forEach(item -> {
                assertThat(item.get("assignmentState").asString()).as("the filter and the row agree").isEqualTo(state);
                ids.add(item.get("id").asLong());
            });
            assertThat(page.get("meta").get("total").asLong()).isEqualTo(ids.size());
            return ids;
        }

        JsonNode counts(long branch) throws Exception {
            return ok(get(session, "/api/lockers/occupancy?scope=" + branch)).get("items").get(0);
        }
    }

    // ---- registering ---------------------------------------------------------------------------------------

    @Test void registeringNeedsOperationsWriteAtTheBranch() throws Exception {
        try (var ops = login(operator); var cash = login(cashier); var staff = login(plain); var other = login(outsider); var boss = login(owner)) {
            refused(post(cash, "/api/lockers", newLocker(first, "A-1", 12000), key()), 403, "FORBIDDEN");
            refused(post(staff, "/api/lockers", newLocker(first, "A-1", 12000), key()), 403, "FORBIDDEN");
            refused(post(other, "/api/lockers", newLocker(first, "A-1", 12000), key()), 403, "FORBIDDEN");
            var made = created(post(ops, "/api/lockers", newLocker(first, "A-1", 12000), key()));
            assertThat(fields(made)).isEqualTo(LOCKER_FIELDS);
            assertThat(made.get("version").asLong()).isEqualTo(1);
            assertThat(made.get("status").asString()).isEqualTo("사용 가능");
            assertThat(made.get("assignmentState").asString()).isEqualTo("사용 가능");
            assertThat(made.get("activeAssignment").isNull()).isTrue();
            assertThat(made.get("ratePer4WeeksKrw").asLong()).isEqualTo(12000);
            created(post(boss, "/api/lockers", newLocker(first, "A-2", 12000), key()));
            long id = made.get("id").asLong();
            assertThat(audits("lockers", id, "CREATED")).isEqualTo(1);
            assertThat(events(first, "lockers")).isEqualTo(2);
        }
    }

    @Test void aDuplicateNumberInTheBranchIsAConflictAndAnotherBranchMayUseIt() throws Exception {
        try (var ops = login(operator); var other = login(outsider)) {
            created(post(ops, "/api/lockers", newLocker(first, "B-1", 12000), key()));
            refused(post(ops, "/api/lockers", newLocker(first, "B-1", 15000), key()), 409, "DUPLICATE_LOCKER_NO");
            created(post(other, "/api/lockers", newLocker(second, "B-1", 15000), key()));
            long renamed = created(post(ops, "/api/lockers", newLocker(first, "B-2", 12000), key())).get("id").asLong();
            refused(patch(ops, "/api/lockers/" + renamed, Map.of("version", 1, "lockerNo", "B-1")), 409, "DUPLICATE_LOCKER_NO");
        }
    }

    @Test void registeringIsIdempotent() throws Exception {
        try (var ops = login(operator)) {
            String key = key();
            var one = post(ops, "/api/lockers", newLocker(first, "C-1", 12000), key);
            created(one);
            var two = post(ops, "/api/lockers", newLocker(first, "C-1", 12000), key);
            assertThat(two.statusCode()).isEqualTo(201);
            assertThat(two.body()).isEqualTo(one.body());
            refused(post(ops, "/api/lockers", newLocker(first, "C-9", 12000), key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(count("SELECT COUNT(*) FROM lockers WHERE branch_id=?", first)).isEqualTo(1);
            refused(post(ops, "/api/lockers", newLocker(first, "C-2", 12000), null), 422, "VALIDATION_FAILED");
        }
    }

    @Test void registeringRefusesBadValues() throws Exception {
        try (var ops = login(operator)) {
            refused(post(ops, "/api/lockers", newLocker(first, "D-1", 0), key()), 422, "VALIDATION_FAILED");
            refused(post(ops, "/api/lockers", newLocker(first, "D-1", 9_007_199_254_740_992L), key()), 422, "VALIDATION_FAILED");
            refused(post(ops, "/api/lockers", newLocker(first, "x".repeat(41), 1000), key()), 422, "VALIDATION_FAILED");
            refused(post(ops, "/api/lockers", with(newLocker(first, "D-1", 1000), "name", "사물함"), key()), 422, "VALIDATION_FAILED");
            refused(post(ops, "/api/lockers", with(newLocker(first, "D-1", 1000), "status", "사용 중"), key()), 422, "VALIDATION_FAILED");
            assertThat(created(post(ops, "/api/lockers", with(newLocker(first, "D-2", 1000), "status", "운영 중단"), key())).get("assignmentState").asString())
                    .isEqualTo("운영 중단");
        }
    }

    // ---- editing --------------------------------------------------------------------------------------------

    @Test void editingFollowsTheCommonRules() throws Exception {
        long id = locker(first, 12000);
        try (var ops = login(operator); var cash = login(cashier); var other = login(outsider)) {
            String path = "/api/lockers/" + id;
            refused(patch(cash, path, Map.of("version", 1, "ratePer4WeeksKrw", 15000)), 403, "FORBIDDEN");
            assertThat(patch(other, path, Map.of("version", 1, "ratePer4WeeksKrw", 15000)).statusCode()).as("outside the branch it does not exist").isEqualTo(404);
            refused(patch(ops, path, Map.of("version", 1)), 422, "VALIDATION_FAILED");
            refused(patch(ops, path, Map.of("version", 1, "status", "운영 중단")), 422, "IMMUTABLE_FIELD");
            refused(patch(ops, path, Map.of("version", 1, "branchId", Long.toString(second))), 422, "IMMUTABLE_FIELD");
            refused(patch(ops, path, Map.of("version", 1, "ratePer4WeeksKrw", 0)), 422, "VALIDATION_FAILED");
            var changed = ok(patch(ops, path, Map.of("version", 1, "ratePer4WeeksKrw", 15000)));
            assertThat(changed.get("version").asLong()).isEqualTo(2);
            assertThat(changed.get("ratePer4WeeksKrw").asLong()).isEqualTo(15000);
            var stale = patch(ops, path, Map.of("version", 1, "ratePer4WeeksKrw", 16000));
            refused(stale, 409, "VERSION_CONFLICT");
            assertThat(node(stale).get("reloadUrl").asString()).isEqualTo(path);
            assertThat(audits("lockers", id, "UPDATED")).isEqualTo(1);
        }
    }

    @Test void anIdenticalEditIsNotAChange() throws Exception {
        long id = locker(first, 12000);
        try (var ops = login(operator)) {
            assertThat(ok(patch(ops, "/api/lockers/" + id, Map.of("version", 1, "ratePer4WeeksKrw", 12000))).get("version").asLong()).isEqualTo(1);
            assertThat(audits("lockers", id, "UPDATED")).isZero();
        }
    }

    @Test void thereIsNoDeleteApi() throws Exception {
        long id = locker(first, 12000);
        try (var boss = login(owner)) {
            assertThat(http(boss.client(), "DELETE", "/api/lockers/" + id, boss.token(), null, null).statusCode()).isIn(404, 405);
            assertThat(rate(id)).isEqualTo(12000);
        }
    }

    @Test void stoppingNeedsAReasonAndAuditsBothValues() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var ops = login(operator); var cash = login(cashier); var staff = login(plain)) {
            String path = "/api/lockers/" + id + "/status";
            long active = assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            refused(post(staff, path, stop(1, "운영 중단", "고장"), null), 403, "FORBIDDEN");
            refused(post(ops, path, Map.of("version", 1, "status", "운영 중단"), null), 422, "VALIDATION_FAILED");
            refused(post(ops, path, stop(1, "사용 중", "고장"), null), 422, "VALIDATION_FAILED");
            var stopped = ok(post(ops, path, stop(1, "운영 중단", "고장"), null));
            assertThat(stopped.get("status").asString()).isEqualTo("운영 중단");
            assertThat(stopped.get("assignmentState").asString()).isEqualTo("운영 중단");
            assertThat(status(active)).as("an active assignment stays").isEqualTo("ACTIVE");
            assertThat(text("SELECT reason FROM audit_events WHERE entity_type='lockers' AND entity_id=? AND action='STATUS_CHANGED'", id)).isEqualTo("고장");
            assertThat(ok(post(ops, path, stop(2, "운영 중단", "다시"), null)).get("version").asLong()).as("the same state is no change").isEqualTo(2);
            assertThat(audits("lockers", id, "STATUS_CHANGED")).isEqualTo(1);
            assertThat(ok(post(ops, path, stop(2, "사용 가능", "수리 완료"), null)).get("assignmentState").asString()).isEqualTo("사용 중");
        }
    }

    @Test void theLedgerStaysEditableInAStoppedBranch() throws Exception {
        long id = locker(first, 12000);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var ops = login(operator)) {
            created(post(ops, "/api/lockers", newLocker(first, "S-1", 12000), key()));
            ok(patch(ops, "/api/lockers/" + id, Map.of("version", 1, "ratePer4WeeksKrw", 15000)));
            ok(post(ops, "/api/lockers/" + id + "/status", stop(2, "운영 중단", "휴점"), null));
            ok(applyRate(ops, first, 16000, key()));
        }
    }

    // ---- the computed state -----------------------------------------------------------------------------------

    @Test void aLockerIsInUseFromItsStartDateNotBefore() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long assignment = assigned(cash, assignment(first, id, customer, "2026-11-05", "2026-12-03"));
            assertThat(http(staff).detail(id).get("assignmentState").asString()).as("planned is available").isEqualTo("사용 가능");
            assertThat(http(staff).detail(id).get("activeAssignment").isNull()).isTrue();
            at("2026-11-05");
            var used = http(staff).detail(id);
            assertThat(used.get("assignmentState").asString()).isEqualTo("사용 중");
            var active = used.get("activeAssignment");
            assertThat(fields(active)).containsExactlyInAnyOrder("id", "customer", "startDate", "endDate", "overdue");
            assertThat(active.get("id").asLong()).isEqualTo(assignment);
            assertThat(fields(active.get("customer"))).containsExactlyInAnyOrder("id", "memberNo", "name");
            assertThat(active.get("startDate").asString()).isEqualTo("2026-11-05");
            assertThat(active.get("endDate").asString()).isEqualTo("2026-12-03");
            assertThat(active.get("overdue").asBoolean()).isFalse();
        }
    }

    @Test void anUnreturnedLockerStaysInUseAndIsOverdueFromItsEndDate() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            assigned(cash, assignment(first, id, customer, "2026-11-01", "2026-11-29"));
            at("2026-11-28");
            assertThat(http(staff).detail(id).get("activeAssignment").get("overdue").asBoolean()).as("the last day").isFalse();
            assertThat(http(staff).counts(first).get("overdue").asLong()).isZero();
            at("2026-11-29");
            var late = http(staff).detail(id);
            assertThat(late.get("assignmentState").asString()).isEqualTo("사용 중");
            assertThat(late.get("activeAssignment").get("overdue").asBoolean()).isTrue();
            var counts = http(staff).counts(first);
            assertThat(counts.get("inUse").asLong()).isEqualTo(1);
            assertThat(counts.get("overdue").asLong()).isEqualTo(1);
            assertThat(counts.get("available").asLong()).isZero();
        }
    }

    @Test void theNextAssignmentStartingWhileTheOverdueOneStaysCountsTheLockerOnce() throws Exception {
        long id = locker(first, 12000);
        long one = customer(first), two = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            long early = assigned(cash, assignment(first, id, one, "2026-11-01", "2026-11-29"));
            assigned(cash, assignment(first, id, two, "2026-11-29", "2026-12-27"));
            at("2026-12-01");
            var active = http(staff).detail(id).get("activeAssignment");
            assertThat(active.get("id").asLong()).as("the things inside are the overdue customer's").isEqualTo(early);
            assertThat(active.get("overdue").asBoolean()).isTrue();
            var counts = http(staff).counts(first);
            assertThat(counts.get("total").asLong()).isEqualTo(1);
            assertThat(counts.get("inUse").asLong()).isEqualTo(1);
            assertThat(counts.get("overdue").asLong()).isEqualTo(1);
        }
    }

    @Test void todayIsTheKoreanDateInEveryRead() throws Exception {
        long id = locker(first, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var staff = login(plain)) {
            assigned(cash, assignment(first, id, customer, "2026-11-02", "2026-11-30"));
            atInstant("2026-11-01T14:59:59.999999Z");
            assertThat(http(staff).detail(id).get("assignmentState").asString()).as("23:59 in Korea").isEqualTo("사용 가능");
            assertThat(http(staff).filtered("사용 가능", first)).containsExactly(id);
            assertThat(http(staff).counts(first).get("available").asLong()).isEqualTo(1);
            atInstant("2026-11-01T15:00:00Z");
            assertThat(http(staff).detail(id).get("assignmentState").asString()).as("still the 1st in UTC, the 2nd in Korea").isEqualTo("사용 중");
            assertThat(http(staff).filtered("사용 중", first)).containsExactly(id);
            assertThat(http(staff).counts(first).get("inUse").asLong()).isEqualTo(1);
        }
    }

    @Test void theCountsAndTheListAgreeOnEveryState() throws Exception {
        long free = locker(first, 12000), planned = locker(first, 12000), used = locker(first, 12000), late = locker(first, 12000),
                returned = locker(first, 12000), failedOne = locker(first, 12000), closedEmpty = locker(first, 12000), closedUsed = locker(first, 12000),
                closedLate = locker(first, 12000);
        locker(second, 12000);
        long customer = customer(first);
        try (var cash = login(cashier); var ops = login(operator); var staff = login(plain)) {
            at("2026-10-01");
            assigned(cash, assignment(first, late, customer, "2026-10-01", "2026-10-29"));
            assigned(cash, assignment(first, closedLate, customer, "2026-10-01", "2026-10-29"));
            at("2026-11-01");
            assigned(cash, assignment(first, planned, customer, "2026-11-20", "2026-12-18"));
            assigned(cash, assignment(first, used, customer, "2026-11-01", "2026-11-29"));
            assigned(cash, assignment(first, closedUsed, customer, "2026-11-01", "2026-11-29"));
            long back = assigned(cash, assignment(first, returned, customer, "2026-11-01", "2026-11-29"));
            ok(giveBack(staff, back, 1));
            created(assign(cash, failed(assignment(first, failedOne, customer, "2026-11-01", "2026-11-29"))));
            for (long id : List.of(closedEmpty, closedUsed, closedLate))
                ok(post(ops, "/api/lockers/" + id + "/status", stop(1, "운영 중단", "점검"), null));
            var counts = http(staff).counts(first);
            var inUse = http(staff).filtered("사용 중", first);
            var available = http(staff).filtered("사용 가능", first);
            var blocked = http(staff).filtered("운영 중단", first);
            assertThat(inUse).containsExactlyInAnyOrder(used, late);
            assertThat(available).containsExactlyInAnyOrder(free, planned, returned, failedOne);
            assertThat(blocked).containsExactlyInAnyOrder(closedEmpty, closedUsed, closedLate);
            assertThat(counts.get("inUse").asLong()).isEqualTo(inUse.size());
            assertThat(counts.get("available").asLong()).isEqualTo(available.size());
            assertThat(counts.get("blocked").asLong()).isEqualTo(blocked.size());
            assertThat(counts.get("total").asLong()).isEqualTo(9).isEqualTo(inUse.size() + available.size() + blocked.size());
            assertThat(counts.get("overdue").asLong()).as("blocked counts only as blocked").isEqualTo(1);
            assertThat(fields(counts)).containsExactlyInAnyOrder("branchId", "total", "inUse", "available", "blocked", "overdue", "asOf");
            assertThat(counts.get("asOf").asString()).isEqualTo(clock.fixed.toString());
            var all = ok(get(staff, "/api/lockers?scope=" + first + "&pageSize=100"));
            for (var item : all.get("items")) {
                assertThat(fields(item)).isEqualTo(LOCKER_FIELDS);
                assertThat(http(staff).detail(item.get("id").asLong())).as("a list row is its detail").isEqualTo(item);
            }
            var closed = http(staff).detail(closedLate);
            assertThat(closed.get("activeAssignment").get("overdue").asBoolean()).as("staff still see whose things are inside").isTrue();
        }
    }

    @Test void theListIsOrderedByNumberAndPaged() throws Exception {
        for (String number : List.of("Z-1", "A-1", "M-1"))
            lockerService.create(owner, "all", key(), newLocker(first, number, 1000), "fixture");
        try (var staff = login(plain)) {
            var page = ok(get(staff, "/api/lockers?scope=" + first + "&page=2&pageSize=2"));
            assertThat(page.get("meta").get("total").asLong()).isEqualTo(3);
            assertThat(page.get("items").get(0).get("lockerNo").asString()).isEqualTo("Z-1");
            refused(get(staff, "/api/lockers?assignmentState=" + java.net.URLEncoder.encode("없음", "UTF-8")), 422, "VALIDATION_FAILED");
            refused(get(staff, "/api/lockers?page=0"), 422, "VALIDATION_FAILED");
        }
    }

    @Test void aBranchWithoutLockersAppearsWithZeros() throws Exception {
        locker(second, 12000);
        try (var both = login(twoBranches); var boss = login(owner)) {
            var items = ok(get(both, "/api/lockers/occupancy")).get("items");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).get("branchId").asLong()).isEqualTo(first);
            assertThat(items.get(0).get("total").asLong()).isZero();
            assertThat(items.get(1).get("total").asLong()).isEqualTo(1);
            assertThat(ok(get(boss, "/api/lockers/occupancy?scope=" + second)).get("items")).hasSize(1);
        }
    }

    @Test void lockersOutsideTheReachOfAnEmployeeAreNotExposed() throws Exception {
        long mine = locker(first, 12000), theirs = locker(second, 15000);
        try (var staff = login(plain); var ops = login(operator)) {
            var page = ok(get(staff, "/api/lockers"));
            assertThat(page.get("meta").get("total").asLong()).isEqualTo(1);
            assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(mine);
            refused(get(staff, "/api/lockers?scope=" + second), 403, "FORBIDDEN");
            refused(get(staff, "/api/lockers/occupancy?scope=" + second), 403, "FORBIDDEN");
            assertThat(ok(get(staff, "/api/lockers/occupancy")).get("items")).hasSize(1);
            assertThat(get(staff, "/api/lockers/" + theirs).statusCode()).isEqualTo(404);
            assertThat(get(staff, "/api/lockers/999999999").statusCode()).isEqualTo(404);
            assertThat(patch(ops, "/api/lockers/" + theirs, Map.of("version", 1, "lockerNo", "X")).statusCode()).isEqualTo(404);
            assertThat(post(ops, "/api/lockers/" + theirs + "/status", stop(1, "운영 중단", "고장"), null).statusCode()).isEqualTo(404);
        }
    }

    @Test void workspaceEventsNameBothResources() {
        for (String type : List.of("lockers", "locker-assignments")) {
            String resource = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    com.openstudy.modules.workspace.service.WorkspaceEventService.class, "resource", type);
            assertThat(resource).isEqualTo(type);
        }
    }

    // ---- the branch rate --------------------------------------------------------------------------------------

    @Test void theBranchRateNeedsOperationsWriteThere() throws Exception {
        locker(first, 12000);
        try (var ops = login(operator); var cash = login(cashier); var other = login(outsider); var boss = login(owner)) {
            refused(applyRate(cash, first, 15000, key()), 403, "FORBIDDEN");
            assertThat(applyRate(other, first, 15000, key()).statusCode()).as("the path's branch is out of reach").isEqualTo(404);
            assertThat(applyRate(boss, 999_999_999L, 15000, key()).statusCode()).isEqualTo(404);
            refused(applyRate(ops, first, 0, key()), 422, "VALIDATION_FAILED");
            refused(applyRate(ops, first, 15000, null), 422, "VALIDATION_FAILED");
            refused(post(ops, "/api/branches/" + first + "/locker-rate", Map.of("ratePer4WeeksKrw", 15000, "reason", "x"), key()), 422, "VALIDATION_FAILED");
            ok(applyRate(ops, first, 15000, key()));
            ok(applyRate(boss, first, 16000, key()));
        }
    }

    @Test void theBranchRateChangesEveryLockerOnceAndOnlyThere() throws Exception {
        long open = locker(first, 12000), odd = locker(first, 20000), stopped = locker(first, 13000), elsewhere = locker(second, 12000);
        long customer = customer(first);
        try (var ops = login(operator); var cash = login(cashier); var book = login(reader)) {
            ok(post(ops, "/api/lockers/" + stopped + "/status", stop(1, "운영 중단", "고장"), null));
            long held = assigned(cash, assignment(first, open, customer, "2026-11-01", "2026-11-29"));
            var eventsBefore = events(first, "lockers");
            var result = ok(applyRate(ops, first, 15000, key()));
            assertThat(fields(result)).containsExactlyInAnyOrder("updated", "ratePer4WeeksKrw");
            assertThat(result.get("updated").asLong()).isEqualTo(3);
            assertThat(result.get("ratePer4WeeksKrw").asLong()).isEqualTo(15000);
            assertThat(rate(open)).isEqualTo(15000);
            assertThat(rate(odd)).isEqualTo(15000);
            assertThat(rate(stopped)).isEqualTo(15000);
            assertThat(rate(elsewhere)).isEqualTo(12000);
            assertThat(lockerVersion(open)).isEqualTo(1);
            assertThat(lockerVersion(odd)).isEqualTo(1);
            assertThat(lockerVersion(stopped)).isEqualTo(2);
            assertThat(lockerVersion(elsewhere)).isZero();
            assertThat(audits("branches", first, "LOCKER_RATE_APPLIED")).as("one audit row for the branch").isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE branch_id=? AND action='LOCKER_RATE_APPLIED'", first)).isEqualTo(1);
            assertThat(events(first, "lockers")).isEqualTo(eventsBefore + 1);
            var charge = ok(get(book, "/api/locker-assignments/" + held)).get("charges").get(0);
            assertThat(charge.get("ratePer4WeeksKrw").asLong()).as("the snapshot stays").isEqualTo(12000);
            assertThat(charge.get("amountKrw").asLong()).isEqualTo(12000);
        }
    }

    @Test void theBranchRateIsIdempotent() throws Exception {
        long id = locker(first, 12000);
        try (var ops = login(operator)) {
            String key = key();
            var one = ok(applyRate(ops, first, 15000, key));
            assertThat(lockerVersion(id)).isEqualTo(1);
            var two = ok(applyRate(ops, first, 15000, key));
            assertThat(two).isEqualTo(one);
            assertThat(lockerVersion(id)).as("a retry changes nothing").isEqualTo(1);
            refused(applyRate(ops, first, 16000, key), 409, "IDEMPOTENCY_CONFLICT");
            assertThat(audits("branches", first, "LOCKER_RATE_APPLIED")).isEqualTo(1);
            assertThat(rate(id)).isEqualTo(15000);
        }
    }

    @Test void theSameRateStillCountsAsAppliedAndABranchWithoutLockersUpdatesNone() throws Exception {
        long id = locker(first, 15000);
        try (var ops = login(operator); var other = login(outsider)) {
            assertThat(ok(applyRate(ops, first, 15000, key())).get("updated").asLong()).isEqualTo(1);
            assertThat(lockerVersion(id)).isEqualTo(1);
            assertThat(ok(applyRate(other, second, 15000, key())).get("updated").asLong()).isZero();
        }
    }
}
