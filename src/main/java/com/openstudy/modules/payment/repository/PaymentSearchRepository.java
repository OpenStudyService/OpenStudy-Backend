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
 * Pass payments of the branches the reader may read payments for. from/to bound created_at as [from, to) in UTC.
 * Filtering and paging run in SQL; only fixed fragments are concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class PaymentSearchRepository {
    private final EntityManager entityManager;

    public enum Sort { CREATED_AT, AMOUNT }

    public record Filter(Set<Long> branches, PaymentStatus status, Instant from, Instant to, Long customerId) {}

    public record Page(List<Payment> rows, long total) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder("x.purpose = 'PASS' AND x.branch_id IN (")
                .append(String.join(",", Collections.nCopies(filter.branches().size(), "?"))).append(")");
        var binds = new ArrayList<Object>(filter.branches().stream().sorted().toList());
        if (filter.status() != null) { where.append(" AND x.status = ?"); binds.add(filter.status().name()); }
        if (filter.from() != null) { where.append(" AND x.created_at >= ?"); binds.add(filter.from()); }
        if (filter.to() != null) { where.append(" AND x.created_at < ?"); binds.add(filter.to()); }
        if (filter.customerId() != null) { where.append(" AND x.customer_id = ?"); binds.add(filter.customerId()); }
        String order = descending ? " DESC" : " ASC";
        String by = (sort == Sort.AMOUNT ? "x.amount_krw" : "x.created_at") + order;
        Query rows = entityManager.createNativeQuery("SELECT x.* FROM payments x WHERE " + where
                + " ORDER BY " + by + ", x.id" + order + " LIMIT ? OFFSET ?", Payment.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM payments x WHERE " + where);
        for (int index = 0; index < binds.size(); index++) {
            rows.setParameter(index + 1, binds.get(index));
            count.setParameter(index + 1, binds.get(index));
        }
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }
}
