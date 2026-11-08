package com.openstudy.modules.passhistory;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import com.openstudy.modules.locker.service.LockerService;
import com.openstudy.modules.reservation.service.RoomService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #72: the cross-branch summary and a customer's room and locker history (space-operations-api.md). Lives beside the
 * pass history because it needs the same real sales and stays. {@code clerk} is a plain member of the first branch and
 * {@code foreign} holds payments:write at the second, so "first" is the serving branch throughout.
 */
@MySqlIntegrationTest
class CustomerHistoryIntegrationTests extends PassUsageFixture {
    private static final String REASON = "현장 문의 응대 사유-원문";
    private static final String GRANT = "customers:cross-branch-read";
    private static final Instant TOMORROW = Instant.parse("2026-11-03T10:00:00Z");

    @Autowired RoomService roomService;
    @Autowired LockerService lockerService;
    private int spaceNumber;

    /** Runs before the fixture's own cleanup, which knows nothing of rooms and lockers. */
    @AfterEach void removeSpaces() {
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM room_usages WHERE reservation_id IN (SELECT id FROM room_reservations WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM room_reservation_slots WHERE reservation_id IN (SELECT id FROM room_reservations WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM room_reservations WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM locker_assignment_charges WHERE assignment_id IN (SELECT id FROM locker_assignments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM locker_assignments WHERE customer_id=?", customer);
        }
        for (long branch : branches) {
            jdbc.update("DELETE FROM rooms WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM lockers WHERE branch_id=?", branch);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    private long staff(long branch, String role, String status, String... grants) {
        long person = person("EMPLOYEE", role + " 직원");
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,?)", person, branch, role, status);
        for (String code : grants) grant(person, branch, code);
        return person;
    }

    private HttpResponse<String> summary(Session session, long customer, long branch, String reason) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("reason", reason);
        return http(session.client(), "POST", "/api/customers/" + customer + "/cross-branch-summary", session.token(), body, null);
    }

    private JsonNode served(long employee, long customer, long branch) throws Exception {
        try (var session = login(employee)) {
            var response = summary(session, customer, branch, REASON);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return node(response);
        }
    }

    private int summaryStatus(long employee, long customer, long branch) throws Exception {
        try (var session = login(employee)) { return summary(session, customer, branch, REASON).statusCode(); }
    }

    private long room(long branch) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomNo", "R-" + ++spaceNumber); body.put("name", "룸 " + spaceNumber);
        body.put("capacity", 4); body.put("ratePerHourKrw", 8000L);
        return Long.parseLong(roomService.create(owner, "all", key(), body, "history-fixture").id());
    }

    private long locker(long branch) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("lockerNo", "L-" + ++spaceNumber); body.put("ratePer4WeeksKrw", 20000L);
        return Long.parseLong(lockerService.create(owner, "all", key(), body, "history-fixture").id());
    }

    private JsonNode reserve(Session session, long branch, long customer, Instant start, String outcome) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("roomId", Long.toString(room(branch))); body.put("customerId", Long.toString(customer));
        body.put("startAt", start.toString()); body.put("endAt", start.plusSeconds(3600).toString()); body.put("partySize", 2); body.put("method", "카드");
        body.put("mockOutcome", outcome);
        var response = http(session.client(), "POST", "/api/room-reservations", session.token(), body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    private JsonNode assign(Session session, long branch, long customer, String start, String end, String outcome) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("lockerId", Long.toString(locker(branch))); body.put("customerId", Long.toString(customer));
        body.put("startDate", start); body.put("endDate", end); body.put("method", "카드"); body.put("mockOutcome", outcome);
        var response = http(session.client(), "POST", "/api/locker-assignments", session.token(), body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    private JsonNode act(Session session, String path, Object body) throws Exception {
        var response = http(session.client(), "POST", path, session.token(), body, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    private JsonNode startUse(Session session, JsonNode reservation) throws Exception {
        return act(session, "/api/room-reservations/" + reservation.get("id").asString() + "/start", Map.of("version", reservation.get("version").asLong()));
    }

    private JsonNode completeUse(Session session, JsonNode reservation) throws Exception {
        return act(session, "/api/room-reservations/" + reservation.get("id").asString() + "/complete", Map.of("version", reservation.get("version").asLong()));
    }

    private JsonNode giveBack(Session session, JsonNode assignment) throws Exception {
        return act(session, "/api/locker-assignments/" + assignment.get("id").asString() + "/return", Map.of("version", assignment.get("version").asLong()));
    }

    private static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private static List<JsonNode> items(JsonNode array) {
        var found = new ArrayList<JsonNode>();
        array.forEach(found::add);
        return found;
    }

    private static List<JsonNode> ofKind(JsonNode summary, String kind) {
        return items(summary.get("usages")).stream().filter(usage -> kind.equals(usage.get("kind").asString())).toList();
    }

    private long interactions(long customer) { return count("SELECT COUNT(*) FROM customer_service_interactions WHERE customer_id=?", customer); }

    private long audits(String action, long actor) { return count("SELECT COUNT(*) FROM audit_events WHERE action=? AND actor_employee_id=?", action, actor); }

    // ---- who may read the summary -------------------------------------------------------------------------------

    @Test void onlyAnOwnerAServingManagerOrAGrantedEmployeeReadsTheSummaryAndAnyoneElseIsRefused() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long manager = staff(first, "MANAGER", "ACTIVE"), granted = staff(first, "STAFF", "ACTIVE", GRANT);
        assertThat(summaryStatus(owner, customer, first)).isEqualTo(200);
        assertThat(summaryStatus(manager, customer, first)).as("the role alone").isEqualTo(200);
        assertThat(summaryStatus(granted, customer, first)).isEqualTo(200);
        assertThat(summaryStatus(clerk, customer, first)).as("no grant").isEqualTo(403);
        assertThat(summaryStatus(staff(first, "STAFF", "ACTIVE", "payments:read", "payments:write", "members:write"), customer, first)).isEqualTo(403);
        assertThat(summaryStatus(staff(second, "MANAGER", "ACTIVE"), customer, first)).as("manager of another branch").isEqualTo(403);
        assertThat(summaryStatus(staff(second, "STAFF", "ACTIVE", GRANT), customer, first)).as("grant at another branch").isEqualTo(403);
        long stopped = staff(first, "MANAGER", "REVOKED");
        assertThat(summaryStatus(stopped, customer, first)).as("stopped manager").isEqualTo(403);
        assertThat(audits("CROSS_BRANCH_SUMMARY_DENIED", stopped)).as("a stopped membership reaches the check, so it leaves the trail").isEqualTo(1);
        assertThat(summaryStatus(staff(first, "STAFF", "REVOKED", GRANT), customer, first)).as("stopped grant").isEqualTo(403);
        assertThat(summaryStatus(staff(first, "MANAGER", "PENDING"), customer, first)).as("not yet approved").isEqualTo(403);
    }

    @Test void aManagerOrGrantLosesTheSummaryTheMomentTheMembershipStopsWithoutANewLogin() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long manager = staff(first, "MANAGER", "ACTIVE");
        try (var session = login(manager)) {
            assertThat(summary(session, customer, first, REASON).statusCode()).isEqualTo(200);
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", manager, first);
            assertThat(summary(session, customer, first, REASON).statusCode()).isEqualTo(403);
        }
    }

    @Test void aStoppedServingBranchStillAnswersBecauseTheSummaryOpensNothing() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long granted = staff(first, "STAFF", "ACTIVE", GRANT);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        assertThat(summaryStatus(granted, customer, first)).isEqualTo(200);
    }

    @Test void aMalformedRequestIsRefusedBeforeAnythingIsRecorded() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long granted = staff(first, "STAFF", "ACTIVE", GRANT);
        try (var session = login(granted)) {
            String path = "/api/customers/" + customer + "/cross-branch-summary";
            String branch = Long.toString(first);
            refused(http(session.client(), "POST", path, session.token(), Map.of("branchId", branch), null), 422, "VALIDATION_FAILED");
            refused(summary(session, customer, first, "   "), 422, "VALIDATION_FAILED");
            refused(summary(session, customer, first, "가".repeat(501)), 422, "VALIDATION_FAILED");
            refused(http(session.client(), "POST", path, session.token(), Map.of("reason", REASON), null), 422, "VALIDATION_FAILED");
            refused(http(session.client(), "POST", path, session.token(), Map.of("branchId", branch, "reason", REASON, "memo", "x"), null), 422, "VALIDATION_FAILED");
            assertThat(http(session.client(), "POST", path, null, Map.of("branchId", branch, "reason", REASON), null).statusCode()).as("no CSRF token").isEqualTo(403);
            assertThat(interactions(customer)).isZero();
            assertThat(summary(session, customer, first, "가".repeat(500)).statusCode()).as("the longest reason").isEqualTo(200);
        }
    }

    // ---- what the summary shows ---------------------------------------------------------------------------------

    @Test void theSummaryCarriesOnlyTheContractFieldsAndEveryPassStatus() throws Exception {
        long customer = customer(second, "유일한이름고객");
        String phone = text("SELECT phone FROM customers WHERE id=?", customer), memberNo = text("SELECT member_no FROM customers WHERE id=?", customer);
        long active = pass(customer, second, time(600));
        long used = pass(customer, second, stored(9000, 3000));
        long expiring = pass(customer, second, time(600, 1L));
        long refunded = pass(customer, second, period(30));
        long legacy = pass(customer, second, period(7));
        refund(refunded);
        jdbc.update("UPDATE passes SET status='EXHAUSTED',remaining_credit_krw=0 WHERE id=?", used);
        jdbc.update("UPDATE passes SET expires_at=? WHERE id=?", java.sql.Timestamp.from(TEN.minusSeconds(60)), expiring);
        jdbc.update("UPDATE passes SET status='NOT_STARTED',started_at=NULL,expires_at=NULL WHERE id=?", legacy);
        try (var seller = login(foreign)) {
            stay(seller, second, customer, active, 30, "둘째 지점 내부 사유");
            completeUse(seller, startUse(seller, reserve(seller, second, customer, TOMORROW, "APPROVED")));
            assign(seller, second, customer, "2026-11-02", "2026-11-30", "APPROVED");
        }
        var response = summaryResponse(staff(first, "STAFF", "ACTIVE", GRANT), customer, first);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var summary = node(response);
        assertThat(fields(summary)).containsExactlyInAnyOrder("customerId", "passes", "usages", "asOf");
        assertThat(summary.get("customerId").asString()).isEqualTo(Long.toString(customer));
        assertThat(Instant.parse(summary.get("asOf").asString())).isEqualTo(clock.fixed);
        assertThat(summary.get("passes")).hasSize(4);
        for (var held : summary.get("passes")) assertThat(fields(held)).containsExactlyInAnyOrder("kind", "productName", "status", "expiresAt");
        assertThat(items(summary.get("passes")).stream().map(held -> held.get("status").asString()))
                .as("newest first, the legacy NOT_STARTED row left out").containsExactly("환불", "만료", "소진", "활성");
        assertThat(items(summary.get("passes")).stream().map(held -> held.get("kind").asString())).containsExactly("기간권", "시간권", "정액권", "시간권");
        assertThat(summary.get("usages")).hasSize(3);
        for (var usage : summary.get("usages")) {
            assertThat(fields(usage)).containsExactlyInAnyOrder("kind", "branchId", "startAt", "endAt");
            assertThat(usage.get("branchId").asString()).isEqualTo(Long.toString(second));
        }
        assertThat(response.body()).doesNotContain(phone, memberNo, "유일한이름고객", "secret@customer.test", "내부 메모", "둘째 지점 내부 사유",
                "결제 담당", "15437", "8000", "20000", REASON);
    }

    private HttpResponse<String> summaryResponse(long employee, long customer, long branch) throws Exception {
        try (var session = login(employee)) { return summary(session, customer, branch, REASON); }
    }

    @Test void usagesAreWhatActuallyHappenedAtAnyBranch() throws Exception {
        long customer = customer(first, "이용 고객");
        long pass = pass(customer, first, time(6000));
        Instant roomStarted, roomEnded, lockerReturned;
        try (var seller = login(payer); var boss = login(owner)) {
            reserve(seller, first, customer, TOMORROW.plusSeconds(86_400), "APPROVED");
            reserve(seller, first, customer, TOMORROW.plusSeconds(2 * 86_400), "FAILED");
            assign(seller, first, customer, "2026-11-20", "2026-12-18", "APPROVED");
            assign(seller, first, customer, "2026-11-02", "2026-11-30", "FAILED");
            giveBack(seller, assign(seller, first, customer, "2026-11-10", "2026-12-08", "APPROVED"));
            entered(seller, seat(first), customer, pass);
            assign(seller, first, customer, "2026-11-02", "2026-11-30", "APPROVED");
            var returned = assign(boss, second, customer, "2026-11-02", "2026-11-30", "APPROVED");
            var used = startUse(seller, reserve(seller, first, customer, TOMORROW, "APPROVED"));
            roomStarted = clock.fixed;
            clock.fixed = clock.fixed.plusSeconds(60);
            completeUse(seller, used);
            roomEnded = clock.fixed;
            clock.fixed = clock.fixed.plusSeconds(60);
            startUse(boss, reserve(boss, second, customer, TOMORROW.plusSeconds(3600), "APPROVED"));
            giveBack(boss, returned);
            lockerReturned = clock.fixed;
        }
        clock.fixed = Instant.parse("2026-11-15T01:00:00Z");
        var summary = served(owner, customer, first);
        var rooms = ofKind(summary, "스터디룸");
        assertThat(rooms).as("a booking nobody used is not a use").hasSize(2);
        assertThat(rooms.get(0).get("branchId").asString()).isEqualTo(Long.toString(second));
        assertThat(rooms.get(0).get("endAt").isNull()).as("still in use").isTrue();
        assertThat(Instant.parse(rooms.get(1).get("startAt").asString())).isEqualTo(roomStarted);
        assertThat(Instant.parse(rooms.get(1).get("endAt").asString())).isEqualTo(roomEnded);
        var seats = ofKind(summary, "좌석");
        assertThat(seats).hasSize(1);
        assertThat(seats.getFirst().get("endAt").isNull()).as("still seated").isTrue();
        var lockers = ofKind(summary, "사물함");
        assertThat(lockers).as("neither a failed payment, a later start nor a return before the start is a use").hasSize(2);
        assertThat(lockers).allSatisfy(usage -> assertThat(Instant.parse(usage.get("startAt").asString()))
                .as("the first day begins in Korea").isEqualTo(Instant.parse("2026-11-01T15:00:00Z")));
        var byBranch = new LinkedHashMap<String, JsonNode>();
        lockers.forEach(usage -> byBranch.put(usage.get("branchId").asString(), usage));
        assertThat(byBranch.get(Long.toString(first)).get("endAt").isNull()).as("held, not returned").isTrue();
        assertThat(Instant.parse(byBranch.get(Long.toString(second)).get("endAt").asString())).isEqualTo(lockerReturned);
    }

    @Test void onlyTheLatestFiftyUsesAreShownLatestFirstAcrossAllKinds() throws Exception {
        long customer = customer(second, "단골 고객");
        long pass = pass(customer, second, time(60_000));
        Instant firstStay = clock.fixed;
        try (var seller = login(foreign)) {
            for (int stay = 0; stay < 52; stay++) stay(seller, second, customer, pass, 1, null);
            startUse(seller, reserve(seller, second, customer, TOMORROW, "APPROVED"));
            assign(seller, second, customer, "2026-11-02", "2026-11-30", "APPROVED");
        }
        var usages = items(served(owner, customer, first).get("usages"));
        assertThat(usages).hasSize(50);
        assertThat(usages.stream().map(usage -> Instant.parse(usage.get("startAt").asString())).toList())
                .isSortedAccordingTo(java.util.Comparator.<Instant>naturalOrder().reversed());
        assertThat(usages.getFirst().get("kind").asString()).isEqualTo("스터디룸");
        assertThat(usages.stream().filter(usage -> "좌석".equals(usage.get("kind").asString()))).hasSize(49);
        assertThat(usages.stream().filter(usage -> "사물함".equals(usage.get("kind").asString()))).as("its period began this morning, before every stay").isEmpty();
        assertThat(Instant.parse(usages.getLast().get("startAt").asString())).as("the three oldest stays fall off").isEqualTo(firstStay.plusSeconds(3 * 60));
    }

    // ---- the trail ------------------------------------------------------------------------------------------------

    @Test void everySuccessfulReadLeavesItsOwnRowWithWhoWhomWhereWhyAndWhen() throws Exception {
        long customer = customer(second, "응대 고객");
        long granted = staff(first, "STAFF", "ACTIVE", GRANT);
        served(granted, customer, first);
        clock.fixed = TEN.plusSeconds(60);
        served(granted, customer, first);
        assertThat(interactions(customer)).isEqualTo(2);
        var row = jdbc.queryForMap("SELECT * FROM customer_service_interactions WHERE customer_id=? ORDER BY id DESC LIMIT 1", customer);
        assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
        assertThat(((Number) row.get("employee_id")).longValue()).isEqualTo(granted);
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("source")).isEqualTo("CROSS_BRANCH_SUMMARY");
        assertThat(jdbc.queryForObject("SELECT MAX(verified_at) FROM customer_service_interactions WHERE customer_id=?", java.sql.Timestamp.class, customer).toInstant())
                .isEqualTo(TEN.plusSeconds(60));
        assertThat(audits("CROSS_BRANCH_SUMMARY", granted)).as("the table is the record").isZero();
    }

    @Test void anOwnerWithoutAMembershipIsRecordedInTheAuditLogAndOneWithAMembershipInTheTable() throws Exception {
        long customer = customer(second, "응대 고객");
        served(owner, customer, first);
        assertThat(interactions(customer)).isZero();
        var row = jdbc.queryForMap("SELECT * FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY' AND actor_employee_id=?", owner);
        assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(first);
        assertThat(((Number) row.get("entity_id")).longValue()).isEqualTo(customer);
        assertThat(row.get("entity_type")).isEqualTo("customers");
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("occurred_at")).isNotNull();
        member(owner, first);
        served(owner, customer, first);
        assertThat(interactions(customer)).isEqualTo(1);
        assertThat(audits("CROSS_BRANCH_SUMMARY", owner)).isEqualTo(1);
    }

    @Test void aRefusalSurvivesItsRollbackWithoutTheReasonOrAnyInteraction() throws Exception {
        long customer = customer(first, "거부 고객");
        try (var session = login(clerk)) {
            var response = summary(session, customer, second, REASON);
            refused(response, 403, "FORBIDDEN");
            var row = jdbc.queryForMap("SELECT * FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY_DENIED' AND actor_employee_id=?", clerk);
            assertThat(row.get("result")).isEqualTo("DENIED");
            assertThat(row.get("entity_type")).isEqualTo("customers");
            assertThat(((Number) row.get("entity_id")).longValue()).isEqualTo(customer);
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(second);
            assertThat(row.get("reason")).isNull();
            assertThat(row.get("before_json")).isNull();
            assertThat(row.get("after_json")).isNull();
            assertThat(row.get("occurred_at")).isNotNull();
            assertThat(row.get("request_id")).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        }
        assertThat(interactions(customer)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE reason=? OR CAST(after_json AS CHAR) LIKE ?", REASON, "%" + REASON + "%")).isZero();
    }

    @Test void aRefusedEmployeeCannotTellAMissingCustomerFromAnExistingOne() throws Exception {
        long customer = customer(second, "존재 고객");
        long missing = customer + 1_000_000;
        try (var session = login(clerk)) {
            var known = summary(session, customer, first, REASON);
            var unknown = summary(session, missing, first, REASON);
            refused(known, 403, "FORBIDDEN");
            refused(unknown, 403, "FORBIDDEN");
            assertThat(node(unknown).get("message")).isEqualTo(node(known).get("message"));
            assertThat(fields(node(unknown))).isEqualTo(fields(node(known)));
        }
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY_DENIED' AND entity_id=? AND actor_employee_id=?", missing, clerk)).isEqualTo(1);
    }

    @Test void aPermittedEmployeeGetsA404ForAMissingCustomerAndNothingIsRecorded() throws Exception {
        long customer = customer(second, "존재 고객");
        long missing = customer + 1_000_000;
        try (var session = login(staff(first, "MANAGER", "ACTIVE"))) { refused(summary(session, missing, first, REASON), 404, "NOT_FOUND"); }
        try (var session = login(owner)) { refused(summary(session, missing, first, REASON), 404, "NOT_FOUND"); }
        assertThat(audits("CROSS_BRANCH_SUMMARY", owner)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY_DENIED' AND entity_id=?", missing)).isZero();
    }

    @Test void idsNoColumnCanHoldStillAnswer403AndAreKeptInTheDetail() throws Exception {
        long customer = customer(first, "거부 고객");
        long noBranch = second + 1_000_000;
        try (var session = login(owner)) {
            refused(summary(session, customer, noBranch, REASON), 403, "FORBIDDEN");
            assertThat(text("SELECT after_json->>'$.branchId' FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY_DENIED' AND actor_employee_id=? AND branch_id IS NULL", owner))
                    .isEqualTo(Long.toString(noBranch));
        }
        try (var session = login(clerk)) {
            refused(summary(session, -1, first, REASON), 403, "FORBIDDEN");
            assertThat(text("SELECT after_json->>'$.customerId' FROM audit_events WHERE action='CROSS_BRANCH_SUMMARY_DENIED' AND actor_employee_id=? AND entity_id IS NULL", clerk))
                    .isEqualTo("-1");
        }
    }

    // ---- what the summary does not open -------------------------------------------------------------------------

    @Test void theSummaryWidensNeitherTheCustomerLookupNorThePaymentDetail() throws Exception {
        long customer = customer(second, "다른 지점 고객");
        long passId = pass(customer, second, time(600));
        long payment = count("SELECT purchase_payment_id FROM passes WHERE id=?", passId);
        long granted = staff(first, "MANAGER", "ACTIVE", GRANT, "payments:read");
        served(granted, customer, first);
        try (var session = login(granted)) {
            assertThat(get(session, "/api/customers/" + customer).statusCode()).as("detail").isEqualTo(404);
            assertThat(ok(session, "/api/customers?q=" + java.net.URLEncoder.encode("다른 지점 고객", java.nio.charset.StandardCharsets.UTF_8)).get("items")).isEmpty();
            assertThat(get(session, "/api/customers/" + customer + "/pass-history").statusCode()).isEqualTo(404);
            assertThat(get(session, "/api/customers/" + customer + "/space-history").statusCode()).isEqualTo(404);
            assertThat(get(session, "/api/payments/" + payment).statusCode()).as("payments:read here is not payments:read there").isEqualTo(404);
            assertThat(get(session, "/api/payments/" + payment + "?scope=" + second).statusCode()).isEqualTo(403);
        }
        try (var session = login(staff(second, "STAFF", "ACTIVE"))) {
            assertThat(get(session, "/api/payments/" + payment).statusCode()).as("a member without payments:read").isEqualTo(403);
        }
        try (var session = login(staff(second, "STAFF", "ACTIVE", "payments:read"))) {
            assertThat(ok(session, "/api/payments/" + payment).get("id").asString()).isEqualTo(Long.toString(payment));
        }
    }

    @Test void anEmployeeWithoutTheGrantStillSeesOnlyTheAllowedBranchesOfACustomer() throws Exception {
        long customer = customer(first, "양쪽 구매 고객");
        long here = pass(customer, first, time(600)), there = pass(customer, second, time(300));
        try (var session = login(clerk)) {
            assertThat(ok(session, "/api/customers/" + customer).get("id").asString()).isEqualTo(Long.toString(customer));
            var ledgers = items(ok(session, "/api/customers/" + customer + "/pass-history").get("passes"));
            assertThat(ledgers.stream().map(ledger -> ledger.get("passId").asString())).containsExactly(Long.toString(here));
            assertThat(there).isNotEqualTo(here);
        }
    }

    // ---- the room and locker history ----------------------------------------------------------------------------

    @Test void theHistoryMatchesTheLedgersAndShowsOnlyTheBranchesInScope() throws Exception {
        long customer = customer(first, "공간 고객");
        JsonNode hereRoom, failedRoom, thereRoom, hereLocker, thereLocker;
        try (var seller = login(payer); var boss = login(owner)) {
            hereRoom = reserve(seller, first, customer, TOMORROW.plusSeconds(3600), "APPROVED");
            failedRoom = reserve(seller, first, customer, TOMORROW, "FAILED");
            thereRoom = reserve(boss, second, customer, TOMORROW, "APPROVED");
            hereLocker = assign(seller, first, customer, "2026-11-02", "2026-11-30", "APPROVED");
            thereLocker = assign(boss, second, customer, "2026-11-02", "2026-11-30", "APPROVED");
        }
        try (var session = login(payer)) {
            var history = ok(session, "/api/customers/" + customer + "/space-history");
            assertThat(fields(history)).containsExactlyInAnyOrder("customerId", "reservations", "assignments");
            assertThat(history.get("customerId").asString()).isEqualTo(Long.toString(customer));
            var reservations = items(history.get("reservations"));
            assertThat(reservations.stream().map(row -> row.get("id").asString()))
                    .as("latest start first, the failed one kept").containsExactly(hereRoom.get("id").asString(), failedRoom.get("id").asString());
            assertThat(reservations.get(0)).isEqualTo(ok(session, "/api/room-reservations/" + hereRoom.get("id").asString()));
            assertThat(reservations.get(1)).isEqualTo(ok(session, "/api/room-reservations/" + failedRoom.get("id").asString()));
            assertThat(history.get("assignments")).hasSize(1);
            assertThat(history.get("assignments").get(0)).isEqualTo(ok(session, "/api/locker-assignments/" + hereLocker.get("id").asString()));
            long paid = reservations.get(0).get("billing").get("paymentId").asLong();
            assertThat(count("SELECT amount_krw FROM payments WHERE id=?", paid)).isEqualTo(reservations.get(0).get("billing").get("amountKrw").asLong());
            assertThat(paid).isEqualTo(count("SELECT payment_id FROM room_reservations WHERE id=?", hereRoom.get("id").asLong()));
            assertThat(history.get("assignments").get(0).get("charges").get(0).get("paymentId").asLong())
                    .isEqualTo(count("SELECT payment_id FROM locker_assignment_charges WHERE assignment_id=?", hereLocker.get("id").asLong()));
        }
        try (var session = login(dual)) {
            var both = ok(session, "/api/customers/" + customer + "/space-history");
            assertThat(both.get("reservations")).hasSize(3);
            assertThat(both.get("assignments")).hasSize(2);
            var narrowed = ok(session, "/api/customers/" + customer + "/space-history?scope=" + second);
            assertThat(items(narrowed.get("reservations")).stream().map(row -> row.get("id").asString())).containsExactly(thereRoom.get("id").asString());
            assertThat(items(narrowed.get("assignments")).stream().map(row -> row.get("id").asString())).containsExactly(thereLocker.get("id").asString());
        }
        try (var session = login(clerk)) {
            assertThat(get(session, "/api/customers/" + customer + "/space-history?scope=" + second).statusCode()).as("a branch outside the membership").isEqualTo(403);
            var history = ok(session, "/api/customers/" + customer + "/space-history");
            // Compare IDs, not text: a bare ID can appear inside any other number in the response.
            assertThat(items(history.get("reservations")).stream().map(row -> row.get("id").asString()))
                    .doesNotContain(thereRoom.get("id").asString());
            assertThat(history.toString()).doesNotContain("\"branchId\":\"" + second + "\"");
        }
    }

    @Test void amountsAndPaymentReferencesNeedAPaymentPermissionBranchByBranch() throws Exception {
        long customer = customer(first, "결제 고객");
        long reader = staff(first, "STAFF", "ACTIVE", "payments:read");
        long manager = staff(first, "MANAGER", "ACTIVE");
        long mixed = staff(first, "STAFF", "ACTIVE", "payments:read");
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", mixed, second);
        try (var boss = login(owner)) {
            reserve(boss, first, customer, TOMORROW, "APPROVED");
            reserve(boss, second, customer, TOMORROW.plusSeconds(3600), "APPROVED");
            assign(boss, first, customer, "2026-11-02", "2026-11-30", "APPROVED");
            assign(boss, second, customer, "2026-11-02", "2026-11-30", "APPROVED");
        }
        String path = "/api/customers/" + customer + "/space-history";
        try (var session = login(clerk)) { assertHidden(ok(session, path)); }
        try (var session = login(manager)) { assertHidden(ok(session, path)); }
        try (var session = login(reader)) { assertShown(ok(session, path)); }
        try (var session = login(payer)) { assertShown(ok(session, path)); }
        try (var session = login(mixed)) {
            var history = ok(session, path);
            for (var row : history.get("reservations")) {
                assertThat(row.get("billing").isNull()).as("row of branch %s", row.get("branchId")).isEqualTo(!row.get("branchId").asString().equals(Long.toString(first)));
            }
            for (var row : history.get("assignments")) {
                assertThat(row.get("charges").isNull()).isEqualTo(!row.get("branchId").asString().equals(Long.toString(first)));
            }
        }
    }

    private static void assertHidden(JsonNode history) {
        assertThat(history.get("reservations")).isNotEmpty();
        assertThat(history.get("assignments")).isNotEmpty();
        history.get("reservations").forEach(row -> assertThat(row.get("billing").isNull()).isTrue());
        history.get("assignments").forEach(row -> assertThat(row.get("charges").isNull()).isTrue());
    }

    private static void assertShown(JsonNode history) {
        assertThat(history.get("reservations")).isNotEmpty();
        assertThat(history.get("assignments")).isNotEmpty();
        history.get("reservations").forEach(row -> assertThat(row.get("billing").get("amountKrw").asLong()).isEqualTo(8000));
        history.get("assignments").forEach(row -> assertThat(row.get("charges").get(0).get("amountKrw").asLong()).isEqualTo(20000));
    }

    @Test void aCustomerFoundOnlyByExactLookupIsA404AndAMissingOneToo() throws Exception {
        long customer = customer(second, "정확 일치 고객");
        try (var boss = login(owner)) { reserve(boss, second, customer, TOMORROW, "APPROVED"); }
        String memberNo = text("SELECT member_no FROM customers WHERE id=?", customer);
        try (var session = login(clerk)) {
            var found = http(session.client(), "POST", "/api/customer-lookups/check-in", session.token(), Map.of("branchId", Long.toString(first), "memberNo", memberNo), null);
            assertThat(node(found).get("items")).hasSize(1);
            assertThat(get(session, "/api/customers/" + customer + "/space-history").statusCode()).isEqualTo(404);
            assertThat(get(session, "/api/customers/" + (customer + 1_000_000) + "/space-history").statusCode()).isEqualTo(404);
        }
    }

    @Test void aHistoryWithNothingInItIsTwoEmptyLists() throws Exception {
        long customer = customer(first, "신규 고객");
        try (var session = login(clerk)) {
            var history = ok(session, "/api/customers/" + customer + "/space-history");
            assertThat(history.get("reservations")).isEmpty();
            assertThat(history.get("assignments")).isEmpty();
        }
    }
}
