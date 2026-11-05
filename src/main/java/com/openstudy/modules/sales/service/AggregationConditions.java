package com.openstudy.modules.sales.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.RefundBasis;

/**
 * The common condition rules of every sales aggregation (aggregation-api.md "공통 조건"), in one place for HTTP and M10:
 * <ol>
 *   <li>Filters the operation does not take are a 422 (revenue: productId·categoryCode·granularity; passes: purpose).</li>
 *   <li>Period (M9-D01): from and to together or not at all, from &lt; to, to − from ≤ 366 days (31,622,400 s) or
 *       422 PERIOD_TOO_LONG. Omitted, it is [this month's 1st 00:00, tomorrow 00:00) in Asia/Seoul at asOf.</li>
 *   <li>Branches (M9-D07): every requested branch needs READ_SALES or the whole request is a 403, an unknown branch
 *       included; nothing is dropped silently. Omitted, the branches where the requester has READ_SALES, 403 if none.</li>
 * </ol>
 * The requester's state is read here, from the database, so a caller cannot hand in a wider one. Call it inside the
 * read-only transaction of the aggregation: that first read starts the consistent snapshot, and asOf is taken after it.
 */
@Component
@RequiredArgsConstructor
public class AggregationConditions {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 366 days. Asia/Seoul has no daylight saving time, so 366 days is always this many seconds. */
    public static final Duration MAX_PERIOD = Duration.ofSeconds(31_622_400);
    /** What DATETIME(6) can hold; a syntactically valid year beyond it must stay a 422, not a failed bind. */
    private static final Instant EARLIEST = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    /** Which filters an operation takes besides branches, period and refund basis. */
    public enum Target {
        /** Revenue, branch comparison, ledger and the revenue trend: purpose only. */
        REVENUE,
        /** Pass volume, product ranking and the passes trend (#88): productId and categoryCode, never purpose. */
        PASSES
    }

    private final AuthorizationService authorization;
    private final Clock clock;

    /** Validates the condition, applies the defaults and authorizes every branch; trend only for granularity. */
    public AggregationScope resolve(long requester, AggregationCondition condition, Target target, boolean trend) {
        if (condition == null) throw ApiException.validation("condition", "집계 조건이 필요합니다.");
        filters(condition, target, trend);
        var requested = branches(condition.branchIds());
        period(condition.from(), condition.to());
        Instant from = bound(condition.from(), "from");
        Instant to = bound(condition.to(), "to");
        // The first read of the transaction: the snapshot every number is computed from starts here.
        var state = authorization.current(requester);
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean defaultPeriod = from == null;
        if (defaultPeriod) {
            LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
            from = today.withDayOfMonth(1).atStartOfDay(SEOUL).toInstant();
            to = today.plusDays(1).atStartOfDay(SEOUL).toInstant();
        }
        List<Long> applied;
        if (requested == null) {
            applied = authorization.scope(state, "all", BranchAction.READ_SALES).stream().sorted().toList();
            if (applied.isEmpty()) throw AuthorizationService.forbidden();
        } else {
            authorization.require(state, new LinkedHashSet<>(requested), BranchAction.READ_SALES);
            applied = requested.stream().sorted().toList();
        }
        return new AggregationScope(state, applied, requested == null ? applied : requested, requested == null, from, to,
                defaultPeriod, condition.refundBasis() == null ? RefundBasis.PROCESSED_DATE : condition.refundBasis(),
                condition.purpose(), condition.productId(), condition.categoryCode(), condition.granularity(), asOf);
    }

    private static void filters(AggregationCondition condition, Target target, boolean trend) {
        if (target == Target.REVENUE) {
            if (condition.productId() != null) throw ApiException.validation("productId", "매출 집계에는 상품 조건을 쓸 수 없습니다.");
            if (condition.categoryCode() != null) throw ApiException.validation("categoryCode", "매출 집계에는 분류 조건을 쓸 수 없습니다.");
        } else if (condition.purpose() != null) {
            throw ApiException.validation("purpose", "이용권 집계에는 용도 조건을 쓸 수 없습니다.");
        }
        if (!trend && condition.granularity() != null) throw ApiException.validation("granularity", "추세에서만 구간 단위를 쓸 수 있습니다.");
        if (condition.productId() != null && condition.productId() < 1) throw ApiException.validation("productId", "올바른 ID를 입력해 주세요.");
        if (condition.categoryCode() != null && (condition.categoryCode().isBlank() || condition.categoryCode().length() > 40))
            throw ApiException.validation("categoryCode", "올바른 분류 코드를 입력해 주세요.");
    }

    /** null stays null (omitted). An empty list, a null element or a duplicate is a 422; an unknown ID is the 403 below. */
    private static List<Long> branches(List<Long> branchIds) {
        if (branchIds == null) return null;
        if (branchIds.isEmpty()) throw ApiException.validation("branchIds", "지점을 하나 이상 선택해 주세요.");
        var seen = new HashSet<Long>();
        var result = new ArrayList<Long>();
        for (Long id : branchIds) {
            if (id == null) throw ApiException.validation("branchIds", "올바른 지점 ID를 입력해 주세요.");
            if (!seen.add(id)) throw ApiException.validation("branchIds", "같은 지점을 두 번 선택할 수 없습니다.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    /**
     * DATETIME(6) holds whole microseconds, so a finer instant t selects the same rows as t rounded up to the next
     * microsecond; using that value keeps the bound and the echoed condition exact.
     */
    private static Instant bound(Instant value, String field) {
        if (value == null) return null;
        if (value.isBefore(EARLIEST) || !value.isBefore(LATEST)) throw ApiException.validation(field, "지원하는 범위의 시각을 입력해 주세요.");
        Instant micros = value.truncatedTo(ChronoUnit.MICROS);
        return micros.equals(value) ? value : micros.plus(1, ChronoUnit.MICROS);
    }

    private static void period(Instant from, Instant to) {
        if (from == null && to == null) return;
        if (from == null) throw ApiException.validation("from", "시작 시각과 종료 시각을 함께 입력해 주세요.");
        if (to == null) throw ApiException.validation("to", "시작 시각과 종료 시각을 함께 입력해 주세요.");
        bound(from, "from");
        bound(to, "to");
        if (!from.isBefore(to)) throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0)
            throw ApiException.invalid("PERIOD_TOO_LONG", "집계 기간은 366일 이하여야 합니다.", "to", "기간을 366일 이하로 줄여 주세요.");
    }
}
