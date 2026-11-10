package com.openstudy.modules.payment.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.payment.entity.Payment;
import com.openstudy.modules.payment.entity.PaymentStatus;

/**
 * Payments of every purpose in the branches the reader may read payments for. from/to bound created_at as [from, to) in UTC.
 * Filtering and paging run in SQL; only fixed fragments are concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class PaymentSearchRepository {
    private final EntityManager entityManager;

    public enum Sort { CREATED_AT, AMOUNT }

    /**
     * methods and productKinds hold stored values. The M13 advanced filters (M13-D10, #116): the operator of a payment row is
     * its creator; the processing branch narrows only refunded rows, by their refund's processing branch.
     */
    public record Filter(Set<Long> branches, PaymentStatus status, Instant from, Instant to, Long customerId, List<String> methods,
            List<String> productKinds, String categoryCode, List<Long> operatorIds, List<Long> processingBranchIds) {
        public Filter(Set<Long> branches, PaymentStatus status, Instant from, Instant to, Long customerId) {
            this(branches, status, from, to, customerId, List.of(), List.of(), null, List.of(), List.of());
        }
    }

    /** Count and amount of the matching rows per status (the whole list, not a page). */
    public record StatusTotal(PaymentStatus status, long count, long amountKrw) {}

    public record Page(List<Payment> rows, long total, List<StatusTotal> totals) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0, List.of());
        var where = new StringBuilder("x.branch_id IN (")
                .append(String.join(",", Collections.nCopies(filter.branches().size(), "?"))).append(")");
        var binds = new ArrayList<Object>(filter.branches().stream().sorted().toList());
        if (filter.status() != null) { where.append(" AND x.status = ?"); binds.add(filter.status().name()); }
        if (filter.from() != null) { where.append(" AND x.created_at >= ?"); binds.add(filter.from()); }
        if (filter.to() != null) { where.append(" AND x.created_at < ?"); binds.add(filter.to()); }
        // A customer filter includes the customers merged into it (M13-D07, #114).
        if (filter.customerId() != null) {
            where.append(" AND x.customer_id IN (SELECT m.id FROM customers m WHERE m.id = ? OR m.merged_into_customer_id = ?)");
            binds.add(filter.customerId()); binds.add(filter.customerId());
        }
        in(where, binds, "x.method", filter.methods());
        in(where, binds, "x.created_by", filter.operatorIds());
        if (!filter.productKinds().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = x.id AND k.kind IN (")
                    .append(String.join(",", Collections.nCopies(filter.productKinds().size(), "?"))).append("))");
            binds.addAll(filter.productKinds());
        }
        if (filter.categoryCode() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = x.id"
                    + " AND k.snapshot_category_code COLLATE utf8mb4_0900_bin = ?)");
            binds.add(filter.categoryCode());
        }
        if (!filter.processingBranchIds().isEmpty()) {
            where.append(" AND (x.status <> 'REFUNDED' OR EXISTS (SELECT 1 FROM refunds q WHERE q.payment_id = x.id"
                    + " AND q.processing_branch_id IN (").append(String.join(",", Collections.nCopies(filter.processingBranchIds().size(), "?")))
                    .append(")))");
            binds.addAll(filter.processingBranchIds());
        }
        String order = descending ? " DESC" : " ASC";
        String by = (sort == Sort.AMOUNT ? "x.amount_krw" : "x.created_at") + order;
        Query rows = entityManager.createNativeQuery("SELECT x.* FROM payments x WHERE " + where
                + " ORDER BY " + by + ", x.id" + order + " LIMIT ? OFFSET ?", Payment.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM payments x WHERE " + where);
        Query totals = entityManager.createNativeQuery("SELECT x.status, COUNT(*), COALESCE(SUM(x.amount_krw), 0) FROM payments x WHERE "
                + where + " GROUP BY x.status");
        for (int index = 0; index < binds.size(); index++) {
            rows.setParameter(index + 1, binds.get(index));
            count.setParameter(index + 1, binds.get(index));
            totals.setParameter(index + 1, binds.get(index));
        }
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        var sums = new ArrayList<StatusTotal>();
        for (Object[] row : (List<Object[]>) totals.getResultList())
            sums.add(new StatusTotal(PaymentStatus.valueOf(row[0].toString()), ((Number) row[1]).longValue(),
                    new java.math.BigDecimal(row[2].toString()).longValueExact()));
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue(), sums);
    }

    private static void in(StringBuilder where, List<Object> binds, String column, List<?> values) {
        if (values.isEmpty()) return;
        where.append(" AND ").append(column).append(" IN (").append(String.join(",", Collections.nCopies(values.size(), "?"))).append(")");
        binds.addAll(values);
    }
}
