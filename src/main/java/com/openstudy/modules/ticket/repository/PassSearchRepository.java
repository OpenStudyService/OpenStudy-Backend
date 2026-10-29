package com.openstudy.modules.ticket.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.ProductKind;

/**
 * Issued-pass list scoped to purchase branches. The status filter uses the same effective status as the response
 * (refunded > expired at {@code now} > exhausted > active, FR-PASS-11), so a filtered row never shows another status.
 * Only fixed fragments are concatenated; every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class PassSearchRepository {
    /** Effective status as a SQL expression; its single parameter is the request instant. */
    private static final String EFFECTIVE = "CASE WHEN x.status = 'REFUNDED' THEN 'REFUNDED' "
            + "WHEN x.status = 'EXPIRED' OR (x.expires_at IS NOT NULL AND x.expires_at <= ?) THEN 'EXPIRED' "
            + "WHEN x.status = 'EXHAUSTED' THEN 'EXHAUSTED' ELSE 'ACTIVE' END";
    private final EntityManager entityManager;

    public enum Sort { CREATED_AT, EXPIRES_AT }

    /** {@code status} is ACTIVE, EXHAUSTED, EXPIRED or REFUNDED as an effective status, or null. */
    public record Filter(Set<Long> branches, Long customerId, String status, ProductKind kind, Instant now) {}

    public record Page(List<Pass> rows, long total) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder("x.purchase_branch_id IN (")
                .append(String.join(",", Collections.nCopies(filter.branches().size(), "?"))).append(")");
        var binds = new ArrayList<Object>(filter.branches().stream().sorted().toList());
        if (filter.customerId() != null) { where.append(" AND x.customer_id = ?"); binds.add(filter.customerId()); }
        if (filter.kind() != null) { where.append(" AND x.kind = ?"); binds.add(filter.kind().name()); }
        if (filter.status() != null) {
            where.append(" AND ").append(EFFECTIVE).append(" = ?");
            binds.add(filter.now());
            binds.add(filter.status());
        }
        String order = descending ? " DESC" : " ASC";
        // Passes without an expiry sort last in either direction: "no expiry" is not a date.
        String by = sort == Sort.EXPIRES_AT ? "x.expires_at IS NULL, x.expires_at" + order : "x.created_at" + order;
        Query rows = entityManager.createNativeQuery("SELECT x.* FROM passes x WHERE " + where
                + " ORDER BY " + by + ", x.id" + order + " LIMIT ? OFFSET ?", Pass.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM passes x WHERE " + where);
        for (int index = 0; index < binds.size(); index++) {
            rows.setParameter(index + 1, binds.get(index));
            count.setParameter(index + 1, binds.get(index));
        }
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }
}
