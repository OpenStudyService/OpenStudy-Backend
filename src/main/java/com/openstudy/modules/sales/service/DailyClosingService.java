package com.openstudy.modules.sales.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.exception.OperationsConflict;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.response.DailyClosingView;
import com.openstudy.modules.sales.dto.response.DailyClosingView.Amounts;
import com.openstudy.modules.sales.dto.response.DailyClosingView.BasisSnapshot;
import com.openstudy.modules.sales.dto.response.DailyClosingView.MethodAmounts;
import com.openstudy.modules.sales.dto.response.DailyClosingView.PurposeAmounts;
import com.openstudy.modules.sales.dto.response.DailyClosingView.Snapshot;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;
import com.openstudy.modules.sales.entity.DailyClosing;
import com.openstudy.modules.sales.repository.DailyClosingRepository;

/**
 * Daily closings (operations-extension-api.md "일별 마감", M13-D04·D08·D11). A branch closes an ended Asia/Seoul day once;
 * the snapshot is the M9 revenue of that day for both refund bases, taken from the same service as GET /sales/revenue, so
 * the two always agree. A refund of a payment approved on a closed day leaves an adjustment in its own transaction (payment
 * module), so snapshot + adjustments = the current payment-date total. Every command locks every branch row first, so a
 * closing and a refund of its branch never interleave.
 */
@Service
@RequiredArgsConstructor
public class DailyClosingService {
    private static final String CREATE = "daily-closings:create";
    private static final Map<String, String> METHODS = new LinkedHashMap<>();
    static {
        METHODS.put("CASH", "현금"); METHODS.put("CARD", "카드"); METHODS.put("TRANSFER", "계좌이체"); METHODS.put("OTHER", "기타");
    }

    private final DailyClosingRepository closings;
    private final SalesRevenueService revenue;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore keys;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    public DailyClosingView create(long actor, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "businessDate", "note");
        long branch = input.id("branchId");
        LocalDate day = date(input.text("businessDate", 10), "businessDate");
        String note = input.has("note") ? input.text("note", 500) : null;
        validKey(key);
        if (!day.isBefore(today()))
            throw ApiException.invalid("CLOSING_DATE_OPEN", "끝난 날만 마감할 수 있습니다.", "businessDate", "오늘 이전 날짜를 입력해 주세요.");
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, day.toString(), note)));
        var prior = new AtomicReference<Optional<IdempotencyRecord>>(Optional.empty());
        return commands.inBranches(actor, Set.of(branch), BranchAction.CLOSE_SALES, () -> prior.set(keys.lock(actor, CREATE, scopeKey, key)),
                state -> {
            var replayed = replay(prior.get(), fingerprint);
            if (replayed.isPresent()) return replayed.get();
            var existing = closings.findByBranchIdAndBusinessDate(branch, day);
            if (existing.isPresent())
                throw OperationsConflict.of("CLOSING_EXISTS", "이미 마감한 날짜입니다.", "closingId", existing.get().getId().toString());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var snapshot = new Snapshot(basis(actor, branch, day, RefundBasis.PROCESSED_DATE), basis(actor, branch, day, RefundBasis.PAYMENT_DATE));
            var saved = closings.saveAndFlush(new DailyClosing(branch, day, note, json.writeValueAsString(snapshot), actor, now));
            var after = new LinkedHashMap<String, Object>();
            after.put("businessDate", day.toString());
            after.put("netAmountKrw", snapshot.processedDate().totals().netAmountKrw());
            audit.record(AuditEvent.change(actor, branch, "daily-closings", saved.getId(), "CLOSED", null, null,
                    json.writeValueAsString(after), requestId));
            var result = view(saved, state);
            keys.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** One closing: unseen branch 404, seen without sales:read 403 (the closing shows amounts). */
    @Transactional(readOnly = true)
    public DailyClosingView get(long actor, long id) {
        var state = policy.current(actor);
        var closing = closings.findById(id).filter(found -> policy.permits(state, found.getBranchId(), BranchAction.READ))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        policy.require(state, Set.of(closing.getBranchId()), BranchAction.READ_SALES);
        return view(closing, state);
    }

    /** Closings of the branches, latest day first; branches follow the M9 rule (omitted = every branch with sales:read). */
    @Transactional(readOnly = true)
    public PageResponse<DailyClosingView.Summary> list(long actor, List<String> branchIds, String from, String to, ListQuery query) {
        var state = policy.current(actor);
        Set<Long> branches = branches(state, branchIds);
        LocalDate today = today();
        if ((from == null) != (to == null)) throw ApiException.validation(from == null ? "from" : "to", "시작일과 종료일을 함께 입력해 주세요.");
        LocalDate start = from == null ? today.withDayOfMonth(1).minusMonths(1) : date(from, "from");
        LocalDate end = to == null ? today : date(to, "to");
        if (end.isBefore(start)) throw ApiException.validation("to", "종료일은 시작일 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(start, end) > 366)
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간은 최대 366일입니다.", "to", "기간을 줄여 주세요.");
        long total = closings.countBetween(branches, start, end);
        var rows = closings.between(branches, start, end, PageRequest.of((int) (query.page() - 1), query.pageSize()));
        var counts = new HashMap<Long, Long>();
        if (!rows.isEmpty())
            closings.adjustmentCounts(rows.stream().map(DailyClosing::getId).toList())
                    .forEach(row -> counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        var names = names(rows.stream().map(DailyClosing::getClosedBy).toList());
        var items = rows.stream().map(row -> new DailyClosingView.Summary(row.getId().toString(), Long.toString(row.getBranchId()),
                row.getBusinessDate(), actorOf(row.getClosedBy(), names), row.getClosedAt(),
                snapshot(row).processedDate().totals(), counts.getOrDefault(row.getId(), 0L))).toList();
        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), total));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private DailyClosingView view(DailyClosing closing, AuthorizationState state) {
        var snapshot = snapshot(closing);
        var current = amounts(revenue.revenue(state.employeeId(), condition(closing.getBranchId(), closing.getBusinessDate(),
                RefundBasis.PAYMENT_DATE)).totals());
        boolean reasons = policy.permits(state, closing.getBranchId(), BranchAction.READ_PAYMENTS);
        var rows = closings.adjustments(closing.getId());
        var actors = new TreeSet<Long>();
        actors.add(closing.getClosedBy());
        rows.forEach(row -> actors.add(((Number) row[4]).longValue()));
        var names = names(actors);
        var adjustments = new ArrayList<DailyClosingView.Adjustment>();
        for (var row : rows)
            adjustments.add(new DailyClosingView.Adjustment(row[0].toString(), row[1].toString(), row[2].toString(),
                    -((Number) row[3]).longValue(), reasons ? (String) row[6] : null, actorOf(((Number) row[4]).longValue(), names),
                    Instant.parse((String) row[5])));
        return new DailyClosingView(closing.getId().toString(), Long.toString(closing.getBranchId()), closing.getBusinessDate(),
                closing.getNote(), actorOf(closing.getClosedBy(), names), closing.getClosedAt(), snapshot,
                new DailyClosingView.Current(current, clock.instant()), current.minus(snapshot.paymentDate().totals()), adjustments,
                "Asia/Seoul", "KRW");
    }

    /** The M9 revenue of the branch's day for one refund basis, with the per-method split of the same rows. */
    private BasisSnapshot basis(long actor, long branch, LocalDate day, RefundBasis basis) {
        var summary = revenue.revenue(actor, condition(branch, day, basis));
        var purposes = summary.byPurpose().stream().map(row -> new PurposeAmounts(row.purpose(), row.approvedAmountKrw(),
                row.refundAmountKrw(), row.netAmountKrw(), row.approvedPaymentCount(), row.refundCount())).toList();
        Instant from = day.atStartOfDay(AggregationConditions.SEOUL).toInstant(), to = day.plusDays(1).atStartOfDay(AggregationConditions.SEOUL).toInstant();
        var approved = byMethod(closings.approvalsByMethod(branch, from, to));
        var refunded = byMethod(basis == RefundBasis.PROCESSED_DATE ? closings.refundsByMethodProcessed(branch, from, to)
                : closings.refundsByMethodPaid(branch, from, to));
        var methods = new ArrayList<MethodAmounts>();
        METHODS.forEach((stored, label) -> {
            long[] in = approved.getOrDefault(stored, new long[2]), out = refunded.getOrDefault(stored, new long[2]);
            methods.add(new MethodAmounts(label, in[0], out[0], in[0] - out[0], in[1], out[1]));
        });
        return new BasisSnapshot(amounts(summary.totals()), purposes, methods);
    }

    private static Map<String, long[]> byMethod(List<Object[]> rows) {
        var result = new HashMap<String, long[]>();
        rows.forEach(row -> result.put((String) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        return result;
    }

    private static AggregationCondition condition(long branch, LocalDate day, RefundBasis basis) {
        return AggregationCondition.revenue(List.of(branch), day.atStartOfDay(AggregationConditions.SEOUL).toInstant(),
                day.plusDays(1).atStartOfDay(AggregationConditions.SEOUL).toInstant(), basis, null);
    }

    private static Amounts amounts(RevenueAmounts totals) {
        return new Amounts(totals.approvedAmountKrw(), totals.refundAmountKrw(), totals.netAmountKrw(), totals.approvedPaymentCount(),
                totals.refundCount());
    }

    private Snapshot snapshot(DailyClosing closing) { return json.readValue(closing.getSnapshotJson(), Snapshot.class); }

    private Set<Long> branches(AuthorizationState state, List<String> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            var all = policy.scope(state, "all", BranchAction.READ_SALES);
            if (all.isEmpty()) throw AuthorizationService.forbidden();
            return all;
        }
        var requested = new TreeSet<Long>();
        for (String raw : branchIds) {
            if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw ApiException.validation("branchIds", "올바른 지점 ID를 입력해 주세요.");
            if (!requested.add(Long.parseLong(raw))) throw ApiException.validation("branchIds", "지점이 중복되었습니다.");
        }
        policy.require(state, requested, BranchAction.READ_SALES);
        return requested;
    }

    private Map<Long, String> names(java.util.Collection<Long> ids) {
        var names = new HashMap<Long, String>();
        if (!ids.isEmpty()) closings.employeeNames(Set.copyOf(ids)).forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        return names;
    }

    private static DailyClosingView.ActorSummary actorOf(long id, Map<Long, String> names) {
        return new DailyClosingView.ActorSummary(Long.toString(id), names.get(id));
    }

    private Optional<DailyClosingView> replay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            keys.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), DailyClosingView.class));
    }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), AggregationConditions.SEOUL); }

    private static LocalDate date(String value, String field) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format", String.valueOf(value), 0);
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "YYYY-MM-DD 형식의 날짜를 입력해 주세요.");
        }
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
