package com.openstudy.modules.sales.service;

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
    public static final List<MetricRef> USAGE_METRICS = List.of(new MetricRef("pass.usage_session_count", "건"),
            new MetricRef("pass.usage_minutes", "분"), new MetricRef("pass.usage_credit_krw", "원"));
    private static final String HIDDEN = "다른 지점";

    private final AggregationConditions conditions;
    private final AuthorizationService authorization;
    private final PassUsageRepository usageRows;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PassUsageByBranch usage(long requester, AggregationCondition condition) {
        var scope = conditions.resolve(requester, condition, AggregationConditions.Target.PASSES, false);
        var filter = new PassUsageRepository.PassFilter(scope.productId(), scope.categoryCode(), scope.advanced().productKinds());
        // [usage branch][sale branch] → {sessions, minutes, credit}
        var cells = new TreeMap<Long, Map<Long, long[]>>();
        scope.branchIds().forEach(branch -> cells.put(branch, new TreeMap<>()));
        long sourceRows = 0;
        var sessions = usageRows.sessions(scope.branchIds(), scope.from(), scope.to(), filter);
        for (Object[] row : sessions) {
            var cell = cell(cells, row);
            long count = ((Number) row[2]).longValue();
            cell[0] += count;
            sourceRows += count;
        }
        var deductions = usageRows.deductions(scope.branchIds(), scope.from(), scope.to(), filter);
        for (Object[] row : deductions) {
            var cell = cell(cells, row);
            cell[1] += new java.math.BigDecimal(row[2].toString()).longValueExact();
            cell[2] += new java.math.BigDecimal(row[3].toString()).longValueExact();
            sourceRows += ((Number) row[4]).longValue();
        }
        long[] total = new long[3];
        var items = new ArrayList<PassUsageByBranch.Branch>();
        for (var entry : cells.entrySet()) {
            long[] sum = new long[3];
            var visible = new ArrayList<PassUsageByBranch.SaleBranch>();
            long[] hidden = null;
            for (var sale : entry.getValue().entrySet()) {
                long[] cell = sale.getValue();
                for (int index = 0; index < 3; index++) sum[index] += cell[index];
                if (authorization.permits(scope.requester(), sale.getKey(), BranchAction.READ_SALES)) {
                    visible.add(new PassUsageByBranch.SaleBranch(sale.getKey().toString(), true, null, cell[0], cell[1], cell[2]));
                } else {
                    if (hidden == null) hidden = new long[3];
                    for (int index = 0; index < 3; index++) hidden[index] += cell[index];
                }
            }
            if (hidden != null) visible.add(new PassUsageByBranch.SaleBranch(null, false, HIDDEN, hidden[0], hidden[1], hidden[2]));
            for (int index = 0; index < 3; index++) total[index] += sum[index];
            items.add(new PassUsageByBranch.Branch(entry.getKey().toString(), sum[0], sum[1], sum[2], visible));
        }
        var base = AggregationMetas.of(scope, items.size(), sourceRows, USAGE_METRICS, null, null);
        var view = base.condition();
        // Usage has no refund basis (contract: condition.refundBasis is null), and it is not revenue.
        var condition2 = new AggregationConditionView(view.branchIds(), view.allBranches(), view.from(), view.to(), null, view.purpose(),
                view.productId(), view.categoryCode(), view.granularity(), view.methods(), view.productKinds(), view.operatorIds(),
                view.processingBranchIds());
        var notes = new ArrayList<>(base.conditionNotes());
        notes.add(new ConditionNote("USAGE_NOT_REVENUE", "이용권 사용량이며 매출이 아닙니다. 매출은 판매 지점에 귀속됩니다."));
        var meta = new AggregationMeta(condition2, base.defaultPeriod(), base.timeZone(), base.currency(), base.asOf(), base.rowCount(),
                base.sourceRowCount(), base.dataStatus(), base.partial(), base.metrics(), notes);
        return new PassUsageByBranch(meta, new PassUsageByBranch.Amounts(total[0], total[1], total[2]), items);
    }

    private static long[] cell(Map<Long, Map<Long, long[]>> cells, Object[] row) {
        long usage = ((Number) row[0]).longValue(), sale = ((Number) row[1]).longValue();
        return cells.get(usage).computeIfAbsent(sale, key -> new long[3]);
    }

}
