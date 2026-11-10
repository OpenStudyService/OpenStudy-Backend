package com.openstudy.modules.sales;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #116 over HTTP and MySQL (operations-extension-api.md "고급 필터", "이용 지점별 사용량", "좌석 점유 추이"; M13-D09·D10·D12):
 * the payment list and the aggregations take the same advanced filters and agree; usage is reported per using branch, not as
 * revenue; the refund type is the refund basis plus the processing branch; every occupancy bucket equals the recomputation
 * from seat_sessions; a branch without seats is "해당 없음"; and a branch out of reach is refused as in M9.
 */
@MySqlIntegrationTest
class AdvancedFilterIntegrationTests {
    static final String PASSWORD = "filter-test-password";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter SQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @LocalServerPort int port;

    long owner, sellerA, sellerB, reader, westReader, first, second, empty, customer, timeProduct, periodProduct;
    final LocalDate today = LocalDate.now(SEOUL), yesterday = today.minusDays(1);
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    int seatNumber;

    @BeforeEach void setup() {
        owner = person("OWNER", "사장");
        sellerA = person("EMPLOYEE", "판매 갑");
        sellerB = person("EMPLOYEE", "판매 을");
        reader = person("EMPLOYEE", "매출 직원");
        westReader = person("EMPLOYEE", "둘째 지점 매출");
        first = branch("필터 첫 지점");
        second = branch("필터 둘째 지점");
        empty = branch("좌석 없는 지점");
        member(sellerA, first, "payments:write", "payments:read");
        member(sellerB, first, "payments:write", "payments:read");
        member(reader, first, "sales:read", "payments:read");
        member(reader, second, "sales:read");
        member(reader, empty);
        member(westReader, second, "sales:read");
        var body = new LinkedHashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(first)); body.put("name", "필터 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        customer = Long.parseLong(customers.create(owner, "all", key(), body, "filter-fixture").id());
        customerIds.add(customer);
        timeProduct = product("시간권", Map.of("kind", "시간권", "includedMinutes", 600));
        periodProduct = product("기간권", Map.of("kind", "기간권", "periodDays", 30));
    }

    @AfterEach void clean() {
        String ids = customerIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM pass_balance_events WHERE pass_id IN (SELECT id FROM passes WHERE customer_id IN (" + ids + "))");
        jdbc.update("DELETE FROM seat_sessions WHERE customer_id IN (" + ids + ")");
        jdbc.update("DELETE FROM passes WHERE customer_id IN (" + ids + ")");
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE customer_id IN (" + ids + "))");
        jdbc.update("DELETE FROM payments WHERE customer_id IN (" + ids + ")");
        jdbc.update("DELETE FROM customers WHERE id IN (" + ids + ")");
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (long id : jdbc.queryForList("SELECT id FROM products WHERE created_by IN (" + actors + ")", Long.class)) {
            jdbc.update("DELETE FROM product_revisions WHERE original_product_id=?", id);
            jdbc.update("DELETE FROM product_branches WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM product_categories WHERE created_by IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM seats WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- acceptance -----------------------------------------------------------------------------------------------

    /** AC 1 and 3: the list and the aggregations take the same filters and agree; the applied filter is reported. */
    @Test void theListAndTheAggregationsAgreeUnderTheSameFilters() throws Exception {
        try (var a = login(sellerA); var b = login(sellerB); var sales = login(reader)) {
            String cardTime = pay(a, timeProduct, "카드"), cashTime = pay(b, timeProduct, "현금"), cardPeriod = pay(a, periodProduct, "카드");
            pay(b, periodProduct, "현금");
            ok(refund(b, cardTime));
            String window = "&from=" + enc(start(today).toString()) + "&to=" + enc(start(today.plusDays(1)).toString());
            String branch = "branchIds=" + first;

            for (String filter : List.of("methods=" + enc("카드"), "operatorIds=" + sellerA, "productKinds=" + enc("시간권"),
                    "methods=" + enc("카드") + "&productKinds=" + enc("기간권"))) {
                var list = ok(get(sales, "/api/payments?scope=" + first + "&" + filter + window + "&pageSize=100"));
                var revenue = ok(get(sales, "/api/sales/revenue?" + branch + "&" + filter + window));
                var summary = list.get("summary");
                // Every refunded row was approved first: approvals = 승인 + 환불 rows of the list (contract 정합성 규칙).
                assertThat(summary.at("/approved/amountKrw").asLong() + summary.at("/refunded/amountKrw").asLong()).as(filter)
                        .isEqualTo(revenue.at("/totals/approvedAmountKrw").asLong());
                assertThat(summary.at("/approved/count").asLong() + summary.at("/refunded/count").asLong()).as(filter)
                        .isEqualTo(revenue.at("/totals/approvedPaymentCount").asLong());
                assertThat(list.at("/meta/total").asLong()).as(filter).isEqualTo(list.get("items").size());
                // The ledger of the same condition sums to the totals (M9 consistency under the advanced filters).
                var ledger = ok(get(sales, "/api/sales/ledger?" + branch + "&" + filter + window + "&pageSize=100"));
                assertThat(ledger.at("/totals/netAmountKrw")).as(filter).isEqualTo(revenue.at("/totals/netAmountKrw"));
                assertThat(ledger.at("/meta/total").asLong()).as(filter).isEqualTo(revenue.at("/aggregation/sourceRowCount").asLong());
            }
            // The card filter: the refund of the card time pass belongs to the card method; operator: the refund is 을's.
            var card = ok(get(sales, "/api/sales/revenue?" + branch + "&methods=" + enc("카드") + window));
            assertThat(card.at("/totals/approvedAmountKrw").asLong()).isEqualTo(20000);
            assertThat(card.at("/totals/refundAmountKrw").asLong()).isEqualTo(10000);
            assertThat(texts(card.at("/aggregation/condition/methods"))).containsExactly("카드");
            assertThat(codes(card)).containsExactly("REFUND_BY_PAYMENT_METHOD");
            var mine = ok(get(sales, "/api/sales/revenue?" + branch + "&operatorIds=" + sellerA + window));
            assertThat(mine.at("/totals/approvedAmountKrw").asLong()).isEqualTo(20000);
            assertThat(mine.at("/totals/refundAmountKrw").asLong()).isZero();
            var theirs = ok(get(sales, "/api/sales/revenue?" + branch + "&operatorIds=" + sellerB + window));
            assertThat(theirs.at("/totals/refundAmountKrw").asLong()).isEqualTo(10000);
            assertThat(codes(theirs)).containsExactly("OPERATOR_BY_ENTRY");
            // A product kind narrows revenue to passes and says so.
            var kinds = ok(get(sales, "/api/sales/revenue?" + branch + "&productKinds=" + enc("기간권") + window));
            assertThat(kinds.at("/aggregation/condition/purpose").asString()).isEqualTo("이용권");
            assertThat(codes(kinds)).containsExactly("PASS_ONLY");
            refused(get(sales, "/api/sales/revenue?" + branch + "&productKinds=" + enc("기간권") + "&purpose=" + enc("스터디룸") + window),
                    422, "VALIDATION_FAILED");
            refused(get(sales, "/api/sales/revenue?" + branch + "&methods=" + enc("카드") + "&methods=" + enc("카드") + window), 422,
                    "VALIDATION_FAILED");
            refused(get(sales, "/api/payments?methods=" + enc("수표")), 422, "VALIDATION_FAILED");

            // AC 3: the refund type is the basis plus the processing branch; approvals ignore the processing branch.
            var elsewhere = ok(get(sales, "/api/sales/revenue?" + branch + "&processingBranchIds=" + second + window));
            assertThat(elsewhere.at("/totals/refundAmountKrw").asLong()).isZero();
            assertThat(elsewhere.at("/totals/approvedAmountKrw").asLong()).isEqualTo(40000);
            assertThat(codes(elsewhere)).containsExactly("REFUND_PROCESSING_BRANCH");
            var here = ok(get(sales, "/api/sales/revenue?" + branch + "&processingBranchIds=" + first + "&refundBasis=paymentDate" + window));
            assertThat(here.at("/totals/refundAmountKrw").asLong()).isEqualTo(10000);
            assertThat(here.at("/aggregation/condition/refundBasis").asString()).isEqualTo("paymentDate");
            // The list: the refunded row stays only for a processing branch that refunded it.
            var listed = ok(get(sales, "/api/payments?scope=" + first + "&processingBranchIds=" + second + window + "&pageSize=100"));
            assertThat(listed.at("/summary/refunded/count").asLong()).isZero();
            assertThat(listed.at("/summary/approved/count").asLong()).isEqualTo(3);
            var filtered = ok(get(sales, "/api/payments?scope=" + first + "&methods=" + enc("현금") + "&operatorIds=" + sellerB + window));
            assertThat(texts(filtered.at("/filter/methods"))).containsExactly("현금");
            assertThat(texts(filtered.at("/filter/operatorIds"))).containsExactly(Long.toString(sellerB));
            assertThat(ids(filtered.get("items"))).contains(cashTime).doesNotContain(cardPeriod);
            same("PaymentPage", filtered);
            same("PaymentFilter", filtered.get("filter"));
            same("AggregationMeta", card.get("aggregation"));
            same("AggregationCondition", card.at("/aggregation/condition"));
        }
    }

    /** AC 2: usage is reported per using branch and selling branch, not as revenue; hidden selling branches are merged. */
    @Test void usageIsReportedPerUsingBranchAndIsNotRevenue() throws Exception {
        try (var a = login(sellerA); var sales = login(reader); var west = login(westReader)) {
            String sold = pay(a, timeProduct, "카드");
            long pass = jdbc.queryForObject("SELECT id FROM passes WHERE purchase_payment_id=?", Long.class, sold);
            long seat = seat(second, null);
            // Used yesterday at the second branch: one stay of 90 minutes and its USE row.
            long stay = stay(seat, second, pass, yesterday.atTime(10, 0), yesterday.atTime(11, 30));
            jdbc.update("INSERT INTO pass_balance_events(pass_id,seat_session_id,kind,delta_minutes,delta_krw,event_key,created_at) "
                    + "VALUES(?,?,'USE',-90,0,?,?)", pass, stay, "U-" + UUID.randomUUID(), sql(yesterday.atTime(11, 30)));
            String window = "&from=" + enc(start(yesterday).toString()) + "&to=" + enc(start(today).toString());

            var usage = ok(get(sales, "/api/sales/passes/usage?branchIds=" + first + "&branchIds=" + second + window));
            assertThat(usage.at("/totals/sessionCount").asLong()).isEqualTo(1);
            assertThat(usage.at("/totals/usedMinutes").asLong()).isEqualTo(90);
            var used = itemOf(usage, second);
            assertThat(used.get("usedMinutes").asLong()).isEqualTo(90);
            assertThat(used.at("/bySaleBranch/0/saleBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(used.at("/bySaleBranch/0/visible").asBoolean()).isTrue();
            assertThat(itemOf(usage, first).get("sessionCount").asLong()).isZero();
            assertThat(usage.at("/aggregation/condition/refundBasis").isNull()).isTrue();
            assertThat(codes(usage)).contains("USAGE_NOT_REVENUE");
            assertThat(texts(usage.at("/aggregation/metrics"), "id")).containsExactly("pass.usage_session_count", "pass.usage_minutes",
                    "pass.usage_credit_krw");
            // Revenue stays with the selling branch.
            assertThat(ok(get(sales, "/api/sales/revenue?branchIds=" + second + window)).at("/totals/approvedAmountKrw").asLong()).isZero();
            // A reader without sales:read at the selling branch sees it merged and unnamed.
            var hidden = ok(get(west, "/api/sales/passes/usage?branchIds=" + second + window));
            assertThat(hidden.at("/items/0/bySaleBranch/0/visible").asBoolean()).isFalse();
            assertThat(hidden.at("/items/0/bySaleBranch/0/saleBranchId").isNull()).isTrue();
            assertThat(hidden.at("/items/0/bySaleBranch/0/label").asString()).isEqualTo("다른 지점");
            assertThat(hidden.at("/items/0/usedMinutes").asLong()).isEqualTo(90);
            same("PassUsageByBranch", usage);
            same("PassUsageSaleBranch", hidden.at("/items/0/bySaleBranch/0"));
            // AC 6: a branch out of reach is refused like M9.
            refused(get(west, "/api/sales/passes/usage?branchIds=" + first + window), 403, "FORBIDDEN");
        }
    }

    /** AC 4, 5 and 6: every bucket equals the recomputation from seat_sessions; no seats is "해당 없음"; out of reach is 403. */
    @Test void occupancyBucketsAreRecomputedFromTheStays() throws Exception {
        try (var a = login(sellerA); var sales = login(reader); var west = login(westReader)) {
            String sold = pay(a, timeProduct, "카드");
            long pass = jdbc.queryForObject("SELECT id FROM passes WHERE purchase_payment_id=?", Long.class, sold);
            LocalDateTime registered = yesterday.minusDays(1).atTime(9, 0);
            long seatA = seat(first, registered), seatB = seat(first, registered);
            // A: 10:00-11:30, B: 10:30-11:00 (Seoul, yesterday).
            stay(seatA, first, pass, yesterday.atTime(10, 0), yesterday.atTime(11, 30));
            String other = pay(a, timeProduct, "카드");
            long pass2 = jdbc.queryForObject("SELECT id FROM passes WHERE purchase_payment_id=?", Long.class, other);
            long secondCustomer = jdbc.queryForObject("SELECT customer_id FROM passes WHERE id=?", Long.class, pass2);
            stay(seatB, first, pass2, yesterday.atTime(10, 30), yesterday.atTime(11, 0), secondCustomer);

            String hours = "/api/seats/occupancy-trend?branchId=" + first + "&granularity=hour&from=" + enc(yesterday.atTime(9, 0).atZone(SEOUL)
                    .toOffsetDateTime().toString()) + "&to=" + enc(yesterday.atTime(12, 0).atZone(SEOUL).toOffsetDateTime().toString());
            var trend = ok(get(sales, hours));
            var items = trend.get("items");
            assertThat(items).hasSize(3);
            assertThat(texts(items, "label")).containsExactly(yesterday + " 09:00", yesterday + " 10:00", yesterday + " 11:00");
            for (var bucket : items) {
                Instant from = Instant.parse(bucket.get("start").asString()), to = Instant.parse(bucket.get("end").asString());
                // The recomputation, straight from seat_sessions.
                long seconds = jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(0, TIMESTAMPDIFF(SECOND, GREATEST(effective_checked_in_at, ?), "
                        + "LEAST(effective_checked_out_at, ?)))), 0) FROM seat_sessions WHERE branch_id=?", Long.class, sql(from), sql(to), first);
                assertThat(bucket.get("occupiedSeatMinutes").asLong()).as(bucket.get("label").asString()).isEqualTo(seconds / 60);
                assertThat(bucket.get("seatCount").asLong()).isEqualTo(2);
                assertThat(bucket.get("capacitySeatMinutes").asLong()).isEqualTo(120);
            }
            assertThat(items.at("/1/occupiedSeatMinutes").asLong()).isEqualTo(90);
            assertThat(items.at("/1/occupancy/percent").decimalValue()).isEqualByComparingTo("75.0");
            assertThat(items.at("/0/occupancy/state").asString()).isEqualTo("value");
            assertThat(items.at("/0/occupancy/percent").decimalValue()).isEqualByComparingTo("0.0");
            assertThat(trend.at("/totals/occupiedSeatMinutes").asLong()).isEqualTo(120);
            same("SeatOccupancyTrend", trend);
            same("SeatOccupancyBucket", items.get(0));
            var days = ok(get(sales, "/api/seats/occupancy-trend?branchId=" + first + "&granularity=day&from="
                    + enc(start(yesterday).toString()) + "&to=" + enc(start(today).toString())));
            assertThat(days.at("/items/0/occupiedSeatMinutes").asLong()).isEqualTo(120);
            assertThat(days.at("/items/0/label").asString()).isEqualTo(yesterday.toString());
            // AC 5: a branch without seats is "해당 없음", not 0%.
            var none = ok(get(sales, "/api/seats/occupancy-trend?branchId=" + empty));
            assertThat(none.at("/items/0/occupancy/state").asString()).isEqualTo("notApplicable");
            assertThat(none.at("/items/0/occupancy/percent").isNull()).isTrue();
            assertThat(none.at("/totals/occupancy/state").asString()).isEqualTo("notApplicable");
            // AC 6 and the period limits.
            refused(get(west, "/api/seats/occupancy-trend?branchId=" + first), 403, "FORBIDDEN");
            refused(get(sales, "/api/seats/occupancy-trend?branchId=" + first + "&granularity=hour&from=" + enc(start(today.minusDays(40)).toString())
                    + "&to=" + enc(start(today).toString())), 422, "PERIOD_TOO_LONG");
            refused(get(sales, "/api/seats/occupancy-trend?branchId=" + first + "&granularity=week"), 422, "VALIDATION_FAILED");
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@filter.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode(PASSWORD), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"),
                "filter-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    long product(String kind, Map<String, Object> terms) {
        var category = categories.create(owner, key(), Map.of("code", "C" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                "name", "필터 " + kind, "kind", kind), "filter-fixture");
        var full = new LinkedHashMap<String, Object>(terms);
        if ("시간권".equals(kind)) full.put("validDays", null);
        var made = new LinkedHashMap<String, Object>();
        made.put("name", "필터 " + kind); made.put("priceKrw", 10000L); made.put("terms", full); made.put("status", "판매 중");
        made.put("categoryId", json.readTree(json.writeValueAsString(category)).get("id").asString());
        made.put("branchIds", List.of(Long.toString(first)));
        return Long.parseLong(products.create(owner, "all", key(), made, "filter-fixture").id());
    }

    long seat(long branch, LocalDateTime registered) {
        String no = "F-" + ++seatNumber;
        jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'F',?,'GENERAL','AVAILABLE')", branch, no);
        long id = jdbc.queryForObject("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", Long.class, branch, no);
        if (registered != null) jdbc.update("UPDATE seats SET created_at=? WHERE id=?", sql(registered), id);
        return id;
    }

    long stay(long seat, long branch, long pass, LocalDateTime in, LocalDateTime out) { return stay(seat, branch, pass, in, out, customer); }

    long stay(long seat, long branch, long pass, LocalDateTime in, LocalDateTime out, long who) {
        String key = "S-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seat_sessions(customer_id,pass_id,branch_id,seat_id,status,checked_in_at,effective_checked_in_at,checked_out_at,"
                + "effective_checked_out_at,start_request_key,end_request_key) VALUES(?,?,?,?,'CLOSED',?,?,?,?,?,?)", who, pass, branch, seat,
                sql(in), sql(in), sql(out), sql(out), key, key + "-E");
        return jdbc.queryForObject("SELECT id FROM seat_sessions WHERE start_request_key=?", Long.class, key);
    }

    static String sql(LocalDateTime seoul) { return SQL.format(seoul.atZone(SEOUL).toInstant()); }
    static String sql(Instant at) { return SQL.format(at); }
    static Instant start(LocalDate day) { return day.atStartOfDay(SEOUL).toInstant(); }
    static String key() { return UUID.randomUUID().toString(); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(send(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(send(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(200);
        return new Session(client, json.readTree(send(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    HttpResponse<String> send(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return send(session.client(), "GET", path, null, null, null); }

    String pay(Session session, long product, String method) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("customerId", Long.toString(customer)); body.put("branchId", Long.toString(first));
        body.put("productId", Long.toString(product)); body.put("method", method);
        var response = send(session.client(), "POST", "/api/payments", session.token(), body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response).get("id").asString();
    }

    HttpResponse<String> refund(Session session, String payment) throws Exception {
        return send(session.client(), "POST", "/api/payments/" + payment + "/refund", session.token(), Map.of("reason", "필터 환불"), key());
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).as(response.body()).isEqualTo(code);
    }

    static List<String> codes(JsonNode body) { return texts(body.at("/aggregation/conditionNotes"), "code"); }

    static JsonNode itemOf(JsonNode usage, long branch) {
        for (var item : usage.get("items")) if (item.get("branchId").asString().equals(Long.toString(branch))) return item;
        throw new AssertionError("no usage row for " + branch);
    }

    static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    static List<String> texts(JsonNode array, String field) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.get(field).asString());
        return values;
    }

    static List<String> ids(JsonNode items) { return texts(items, "id"); }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    void same(String schema, JsonNode actual) throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        var expected = new HashSet<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> expected.add(entry.getKey()));
        assertThat(fields(actual)).as(schema).isEqualTo(expected);
    }
}
