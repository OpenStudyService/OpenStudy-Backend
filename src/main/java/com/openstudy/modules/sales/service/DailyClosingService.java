package com.openstudy.modules.sales.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import com.openstudy.modules.sales.dto.response.RevenueSummary;
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
        METHODS.put("CASH", "현금");
        METHODS.put("CARD", "카드");
        METHODS.put("TRANSFER", "계좌이체");
        METHODS.put("OTHER", "기타");
    }

    private static final int BUSINESS_DATE_MAX_LENGTH = 10;
    private static final int NOTE_MAX_LENGTH = 500;
    private static final int CREATED_STATUS = 201;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String AUDIT_ENTITY = "daily-closings";
    private static final String AUDIT_ACTION = "CLOSED";
    private static final String ALL_BRANCHES_SCOPE = "all";
    private static final long MAX_LIST_DAYS = 366;
    private static final String BRANCH_ID_PATTERN = "[1-9][0-9]{0,18}";
    private static final String DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String TIME_ZONE = "Asia/Seoul";
    private static final String CURRENCY = "KRW";

    /** Columns of an adjustment row: id, refund, payment, amount, processor, time, the refund's reason. */
    private static final int ADJUSTMENT_ID_COLUMN = 0;
    private static final int REFUND_ID_COLUMN = 1;
    private static final int PAYMENT_ID_COLUMN = 2;
    private static final int AMOUNT_COLUMN = 3;
    private static final int PROCESSOR_COLUMN = 4;
    private static final int PROCESSED_AT_COLUMN = 5;
    private static final int REASON_COLUMN = 6;

    /** Positions in a per-method pair: {amount, count}. */
    private static final int METHOD_AMOUNT = 0;
    private static final int METHOD_COUNT = 1;

    private final DailyClosingRepository dailyClosingRepository;
    private final SalesRevenueService salesRevenueService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DailyClosingView create(long actor, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "businessDate", "note");
        long branch = input.id("branchId");
        LocalDate day = parseDate(input.text("businessDate", BUSINESS_DATE_MAX_LENGTH), "businessDate");
        String note = input.has("note") ? input.text("note", NOTE_MAX_LENGTH) : null;
        validateKey(key);

        if (!day.isBefore(findToday())) {
            throw ApiException.invalid(
                    "CLOSING_DATE_OPEN",
                    "끝난 날만 마감할 수 있습니다.",
                    "businessDate",
                    "오늘 이전 날짜를 입력해 주세요."
            );
        }

        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(branch, day.toString(), note)));
        AtomicReference<Optional<IdempotencyRecord>> prior = new AtomicReference<>(Optional.empty());

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.CLOSE_SALES,
                () -> prior.set(idempotencyStore.lock(actor, CREATE, scopeKey, key)),
                state -> {
                    Optional<DailyClosingView> replayed = findReplay(prior.get(), fingerprint);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Optional<DailyClosing> existing = dailyClosingRepository.findByBranchIdAndBusinessDate(branch, day);

                    if (existing.isPresent()) {
                        throw OperationsConflict.of(
                                "CLOSING_EXISTS",
                                "이미 마감한 날짜입니다.",
                                "closingId",
                                existing.get().getId().toString()
                        );
                    }

                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    Snapshot snapshot = new Snapshot(
                            createBasisSnapshot(actor, branch, day, RefundBasis.PROCESSED_DATE),
                            createBasisSnapshot(actor, branch, day, RefundBasis.PAYMENT_DATE)
                    );
                    DailyClosing closing = new DailyClosing(
                            branch,
                            day,
                            note,
                            objectMapper.writeValueAsString(snapshot),
                            actor,
                            now
                    );
                    DailyClosing saved = dailyClosingRepository.saveAndFlush(closing);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("businessDate", day.toString());
                    after.put("netAmountKrw", snapshot.processedDate().totals().netAmountKrw());
                    auditTrail.record(AuditEvent.change(
                            actor,
                            branch,
                            AUDIT_ENTITY,
                            saved.getId(),
                            AUDIT_ACTION,
                            null,
                            null,
                            objectMapper.writeValueAsString(after),
                            requestId
                    ));
                    DailyClosingView result = toView(saved, state);
                    idempotencyStore.complete(
                            actor,
                            CREATE,
                            scopeKey,
                            key,
                            fingerprint,
                            CREATED_STATUS,
                            objectMapper.writeValueAsString(result),
                            clock.instant().plus(IDEMPOTENCY_TTL)
                    );

                    return result;
                }
        );
    }

    /** One closing: unseen branch 404, seen without sales:read 403 (the closing shows amounts). */
    @Transactional(readOnly = true)
    public DailyClosingView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        DailyClosing closing = dailyClosingRepository.findById(id)
                .filter(found -> authorizationService.permits(state, found.getBranchId(), BranchAction.READ))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        authorizationService.require(state, Set.of(closing.getBranchId()), BranchAction.READ_SALES);

        return toView(closing, state);
    }

    /** Closings of the branches, latest day first; branches follow the M9 rule (omitted = every branch with sales:read). */
    @Transactional(readOnly = true)
    public PageResponse<DailyClosingView.Summary> list(
            long actor,
            List<String> branchIds,
            String from,
            String to,
            ListQuery query
    ) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> branches = resolveBranches(state, branchIds);
        LocalDate today = findToday();
        boolean onlyOneBound = (from == null) != (to == null);

        if (onlyOneBound) {
            throw ApiException.validation(from == null ? "from" : "to", "시작일과 종료일을 함께 입력해 주세요.");
        }

        LocalDate start = from == null ? today.withDayOfMonth(1).minusMonths(1) : parseDate(from, "from");
        LocalDate end = to == null ? today : parseDate(to, "to");

        if (end.isBefore(start)) {
            throw ApiException.validation("to", "종료일은 시작일 이후여야 합니다.");
        }

        if (ChronoUnit.DAYS.between(start, end) > MAX_LIST_DAYS) {
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간은 최대 366일입니다.", "to", "기간을 줄여 주세요.");
        }

        long total = dailyClosingRepository.countBetween(branches, start, end);
        PageRequest pageRequest = PageRequest.of((int) (query.page() - 1), query.pageSize());
        List<DailyClosing> rows = dailyClosingRepository.findBetween(branches, start, end, pageRequest);
        Map<Long, Long> counts = new HashMap<>();

        if (!rows.isEmpty()) {
            List<Long> closingIds = rows.stream()
                    .map(DailyClosing::getId)
                    .toList();
            dailyClosingRepository.countAdjustments(closingIds)
                    .forEach(row -> counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        }

        List<Long> closers = rows.stream()
                .map(DailyClosing::getClosedBy)
                .toList();
        Map<Long, String> names = findEmployeeNames(closers);
        List<DailyClosingView.Summary> items = rows.stream()
                .map(row -> new DailyClosingView.Summary(
                        row.getId().toString(),
                        Long.toString(row.getBranchId()),
                        row.getBusinessDate(),
                        toActor(row.getClosedBy(), names),
                        row.getClosedAt(),
                        readSnapshot(row).processedDate().totals(),
                        counts.getOrDefault(row.getId(), 0L)
                ))
                .toList();

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), total));
    }

    private DailyClosingView toView(DailyClosing closing, AuthorizationState state) {
        Snapshot snapshot = readSnapshot(closing);
        AggregationCondition condition = createCondition(
                closing.getBranchId(),
                closing.getBusinessDate(),
                RefundBasis.PAYMENT_DATE
        );
        Amounts current = toAmounts(salesRevenueService.revenue(state.employeeId(), condition).totals());
        boolean reasons = authorizationService.permits(state, closing.getBranchId(), BranchAction.READ_PAYMENTS);
        List<Object[]> rows = dailyClosingRepository.findAdjustments(closing.getId());
        Set<Long> actors = new TreeSet<>();
        actors.add(closing.getClosedBy());
        rows.forEach(row -> actors.add(((Number) row[PROCESSOR_COLUMN]).longValue()));
        Map<Long, String> names = findEmployeeNames(actors);
        List<DailyClosingView.Adjustment> adjustments = new ArrayList<>();

        for (Object[] row : rows) {
            adjustments.add(new DailyClosingView.Adjustment(
                    row[ADJUSTMENT_ID_COLUMN].toString(),
                    row[REFUND_ID_COLUMN].toString(),
                    row[PAYMENT_ID_COLUMN].toString(),
                    -((Number) row[AMOUNT_COLUMN]).longValue(),
                    reasons ? (String) row[REASON_COLUMN] : null,
                    toActor(((Number) row[PROCESSOR_COLUMN]).longValue(), names),
                    Instant.parse((String) row[PROCESSED_AT_COLUMN])
            ));
        }

        return new DailyClosingView(
                closing.getId().toString(),
                Long.toString(closing.getBranchId()),
                closing.getBusinessDate(),
                closing.getNote(),
                toActor(closing.getClosedBy(), names),
                closing.getClosedAt(),
                snapshot,
                new DailyClosingView.Current(current, clock.instant()),
                current.minus(snapshot.paymentDate().totals()),
                adjustments,
                TIME_ZONE,
                CURRENCY
        );
    }

    /** The M9 revenue of the branch's day for one refund basis, with the per-method split of the same rows. */
    private BasisSnapshot createBasisSnapshot(long actor, long branch, LocalDate day, RefundBasis basis) {
        RevenueSummary summary = salesRevenueService.revenue(actor, createCondition(branch, day, basis));
        List<PurposeAmounts> purposes = summary.byPurpose()
                .stream()
                .map(row -> new PurposeAmounts(
                        row.purpose(),
                        row.approvedAmountKrw(),
                        row.refundAmountKrw(),
                        row.netAmountKrw(),
                        row.approvedPaymentCount(),
                        row.refundCount()
                ))
                .toList();
        Instant from = day.atStartOfDay(AggregationConditions.SEOUL).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(AggregationConditions.SEOUL).toInstant();
        Map<String, long[]> approved = toMethodSums(dailyClosingRepository.sumApprovalsByMethod(branch, from, to));
        List<Object[]> refundRows = basis == RefundBasis.PROCESSED_DATE
                ? dailyClosingRepository.sumRefundsByMethodProcessed(branch, from, to)
                : dailyClosingRepository.sumRefundsByMethodPaid(branch, from, to);
        Map<String, long[]> refunded = toMethodSums(refundRows);
        List<MethodAmounts> methods = new ArrayList<>();

        METHODS.forEach((stored, label) -> {
            long[] in = approved.getOrDefault(stored, new long[2]);
            long[] out = refunded.getOrDefault(stored, new long[2]);
            methods.add(new MethodAmounts(
                    label,
                    in[METHOD_AMOUNT],
                    out[METHOD_AMOUNT],
                    in[METHOD_AMOUNT] - out[METHOD_AMOUNT],
                    in[METHOD_COUNT],
                    out[METHOD_COUNT]
            ));
        });

        return new BasisSnapshot(toAmounts(summary.totals()), purposes, methods);
    }

    private static Map<String, long[]> toMethodSums(List<Object[]> rows) {
        Map<String, long[]> result = new HashMap<>();
        rows.forEach(row -> result.put(
                (String) row[0],
                new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()}
        ));

        return result;
    }

    private static AggregationCondition createCondition(long branch, LocalDate day, RefundBasis basis) {
        return AggregationCondition.revenue(
                List.of(branch),
                day.atStartOfDay(AggregationConditions.SEOUL).toInstant(),
                day.plusDays(1).atStartOfDay(AggregationConditions.SEOUL).toInstant(),
                basis,
                null
        );
    }

    private static Amounts toAmounts(RevenueAmounts totals) {
        return new Amounts(
                totals.approvedAmountKrw(),
                totals.refundAmountKrw(),
                totals.netAmountKrw(),
                totals.approvedPaymentCount(),
                totals.refundCount()
        );
    }

    private Snapshot readSnapshot(DailyClosing closing) {
        return objectMapper.readValue(closing.getSnapshotJson(), Snapshot.class);
    }

    private Set<Long> resolveBranches(AuthorizationState state, List<String> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            Set<Long> all = authorizationService.scope(state, ALL_BRANCHES_SCOPE, BranchAction.READ_SALES);

            if (all.isEmpty()) {
                throw AuthorizationService.forbidden();
            }

            return all;
        }

        Set<Long> requested = new TreeSet<>();

        for (String raw : branchIds) {
            if (raw == null || !raw.matches(BRANCH_ID_PATTERN)) {
                throw ApiException.validation("branchIds", "올바른 지점 ID를 입력해 주세요.");
            }

            if (!requested.add(Long.parseLong(raw))) {
                throw ApiException.validation("branchIds", "지점이 중복되었습니다.");
            }
        }

        authorizationService.require(state, requested, BranchAction.READ_SALES);

        return requested;
    }

    private Map<Long, String> findEmployeeNames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();

        if (!ids.isEmpty()) {
            dailyClosingRepository.findEmployeeNames(Set.copyOf(ids))
                    .forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        }

        return names;
    }

    private static DailyClosingView.ActorSummary toActor(long id, Map<Long, String> names) {
        return new DailyClosingView.ActorSummary(Long.toString(id), names.get(id));
    }

    private Optional<DailyClosingView> findReplay(Optional<IdempotencyRecord> prior, byte[] fingerprint) {
        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            idempotencyStore.discard(prior.get());

            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), DailyClosingView.class));
    }

    private LocalDate findToday() {
        return LocalDate.ofInstant(clock.instant(), AggregationConditions.SEOUL);
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            if (value == null || !value.matches(DATE_PATTERN)) {
                throw new DateTimeParseException("format", String.valueOf(value), 0);
            }

            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "YYYY-MM-DD 형식의 날짜를 입력해 주세요.");
        }
    }

    private static void validateKey(String key) {
        boolean invalidLength = key == null || key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH;

        if (invalidLength) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
