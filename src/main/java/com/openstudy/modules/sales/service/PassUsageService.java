package com.openstudy.modules.sales.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.response.AggregationConditionView;
import com.openstudy.modules.sales.dto.response.AggregationMeta;
import com.openstudy.modules.sales.dto.response.ConditionNote;
import com.openstudy.modules.sales.dto.response.MetricRef;
import com.openstudy.modules.sales.dto.response.PassUsageByBranch;
import com.openstudy.modules.sales.repository.PassUsageRepository;

/**
 * GET /sales/passes/usage (operations-extension-api.md "이용 지점별 사용량", M13-D12): how much passes were used at each branch,
 * split by the branch that sold them. It is not revenue: sessions are counted by their check-in time and deductions by
 * their ledger time, attributed to the stay's branch. A deduction is a USE row of a stay or a RESTORE/ADJUST row of a stay
 * correction; a manual adjustment tied to no stay has no branch and is left out. Sale branches the requester has no
 * sales:read for are merged into one hidden row.
 */
@Service
@RequiredArgsConstructor
public class PassUsageService {

    public static final List<MetricRef> USAGE_METRICS = List.of(
            new MetricRef("pass.usage_session_count", "건"),
            new MetricRef("pass.usage_minutes", "분"),
            new MetricRef("pass.usage_credit_krw", "원")
    );

    private static final String HIDDEN = "다른 지점";
    private static final ConditionNote USAGE_NOT_REVENUE_NOTE = new ConditionNote(
            "USAGE_NOT_REVENUE",
            "이용권 사용량이며 매출이 아닙니다. 매출은 판매 지점에 귀속됩니다."
    );

    /** Positions in a cell: {sessions, minutes, credit}. */
    private static final int SESSIONS = 0;
    private static final int MINUTES = 1;
    private static final int CREDIT = 2;
    private static final int CELL_SIZE = 3;

    /** Columns of a source row: (using branch, selling branch, ...). */
    private static final int USAGE_BRANCH_COLUMN = 0;
    private static final int SALE_BRANCH_COLUMN = 1;
    private static final int SESSION_COUNT_COLUMN = 2;
    private static final int MINUTES_COLUMN = 2;
    private static final int CREDIT_COLUMN = 3;
    private static final int LEDGER_ROWS_COLUMN = 4;

    private final AggregationConditions aggregationConditions;
    private final AuthorizationService authorizationService;
    private final PassUsageRepository passUsageRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PassUsageByBranch usage(long requester, AggregationCondition condition) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.PASSES,
                false
        );
        PassUsageRepository.PassFilter filter = new PassUsageRepository.PassFilter(
                scope.productId(),
                scope.categoryCode(),
                scope.advanced().productKinds()
        );
        // [usage branch][sale branch] → {sessions, minutes, credit}
        Map<Long, Map<Long, long[]>> cells = new TreeMap<>();
        scope.branchIds().forEach(branch -> cells.put(branch, new TreeMap<>()));
        long sourceRows = 0;
        List<Object[]> sessions = passUsageRepository.countSessions(
                scope.branchIds(),
                scope.from(),
                scope.to(),
                filter
        );

        for (Object[] row : sessions) {
            long[] cell = findCell(cells, row);
            long count = ((Number) row[SESSION_COUNT_COLUMN]).longValue();
            cell[SESSIONS] += count;
            sourceRows += count;
        }

        List<Object[]> deductions = passUsageRepository.sumDeductions(
                scope.branchIds(),
                scope.from(),
                scope.to(),
                filter
        );

        for (Object[] row : deductions) {
            long[] cell = findCell(cells, row);
            cell[MINUTES] += new BigDecimal(row[MINUTES_COLUMN].toString()).longValueExact();
            cell[CREDIT] += new BigDecimal(row[CREDIT_COLUMN].toString()).longValueExact();
            sourceRows += ((Number) row[LEDGER_ROWS_COLUMN]).longValue();
        }

        long[] total = new long[CELL_SIZE];
        List<PassUsageByBranch.Branch> items = new ArrayList<>();

        for (Map.Entry<Long, Map<Long, long[]>> entry : cells.entrySet()) {
            long[] sum = new long[CELL_SIZE];
            List<PassUsageByBranch.SaleBranch> visible = new ArrayList<>();
            long[] hidden = null;

            for (Map.Entry<Long, long[]> sale : entry.getValue().entrySet()) {
                long[] cell = sale.getValue();
                addCell(sum, cell);

                if (authorizationService.permits(scope.requester(), sale.getKey(), BranchAction.READ_SALES)) {
                    visible.add(new PassUsageByBranch.SaleBranch(
                            sale.getKey().toString(),
                            true,
                            null,
                            cell[SESSIONS],
                            cell[MINUTES],
                            cell[CREDIT]
                    ));
                } else {
                    if (hidden == null) {
                        hidden = new long[CELL_SIZE];
                    }

                    addCell(hidden, cell);
                }
            }

            if (hidden != null) {
                visible.add(new PassUsageByBranch.SaleBranch(
                        null,
                        false,
                        HIDDEN,
                        hidden[SESSIONS],
                        hidden[MINUTES],
                        hidden[CREDIT]
                ));
            }

            addCell(total, sum);
            items.add(new PassUsageByBranch.Branch(
                    entry.getKey().toString(),
                    sum[SESSIONS],
                    sum[MINUTES],
                    sum[CREDIT],
                    visible
            ));
        }

        AggregationMeta base = AggregationMetas.of(scope, items.size(), sourceRows, USAGE_METRICS, null, null);
        PassUsageByBranch.Amounts amounts = new PassUsageByBranch.Amounts(
                total[SESSIONS],
                total[MINUTES],
                total[CREDIT]
        );

        return new PassUsageByBranch(toUsageMeta(base), amounts, items);
    }

    /** Usage has no refund basis (contract: condition.refundBasis is null), and it is not revenue. */
    private static AggregationMeta toUsageMeta(AggregationMeta base) {
        AggregationConditionView view = base.condition();
        AggregationConditionView usageCondition = new AggregationConditionView(
                view.branchIds(),
                view.allBranches(),
                view.from(),
                view.to(),
                null,
                view.purpose(),
                view.productId(),
                view.categoryCode(),
                view.granularity(),
                view.methods(),
                view.productKinds(),
                view.operatorIds(),
                view.processingBranchIds()
        );
        List<ConditionNote> notes = new ArrayList<>(base.conditionNotes());
        notes.add(USAGE_NOT_REVENUE_NOTE);

        return new AggregationMeta(
                usageCondition,
                base.defaultPeriod(),
                base.timeZone(),
                base.currency(),
                base.asOf(),
                base.rowCount(),
                base.sourceRowCount(),
                base.dataStatus(),
                base.partial(),
                base.metrics(),
                notes
        );
    }

    private static void addCell(long[] target, long[] cell) {
        for (int index = 0; index < CELL_SIZE; index++) {
            target[index] += cell[index];
        }
    }

    private static long[] findCell(Map<Long, Map<Long, long[]>> cells, Object[] row) {
        long usageBranch = ((Number) row[USAGE_BRANCH_COLUMN]).longValue();
        long saleBranch = ((Number) row[SALE_BRANCH_COLUMN]).longValue();

        return cells.get(usageBranch)
                .computeIfAbsent(saleBranch, key -> new long[CELL_SIZE]);
    }
}
