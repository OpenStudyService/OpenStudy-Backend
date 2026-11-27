package com.openstudy.modules.sales.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The source rows of pass usage per using branch (#116, M13-D12): stays by their check-in time and pass ledger deductions by
 * their ledger time, both attributed to the stay's branch and grouped by the pass's selling branch. Read by table so the
 * sales module depends on neither the seat nor the ticket module. Only fixed fragments are concatenated; values are bound.
 */
@Repository
@RequiredArgsConstructor
public class PassUsageRepository {

    private static final String SESSIONS_SQL = "SELECT s.branch_id, x.purchase_branch_id, COUNT(*) "
            + "FROM seat_sessions s "
            + "JOIN passes x ON x.id = s.pass_id "
            + "WHERE s.branch_id IN (%s) AND s.effective_checked_in_at >= ? AND s.effective_checked_in_at < ?%s"
            + " GROUP BY s.branch_id, x.purchase_branch_id";
    private static final String DEDUCTIONS_SQL = "SELECT s.branch_id, x.purchase_branch_id, "
            + "COALESCE(-SUM(e.delta_minutes), 0), COALESCE(-SUM(e.delta_krw), 0), "
            + "COUNT(*) FROM pass_balance_events e LEFT JOIN seat_session_corrections c ON c.id = e.correction_id "
            + "JOIN seat_sessions s ON s.id = COALESCE(e.seat_session_id, c.seat_session_id) "
            + "JOIN passes x ON x.id = e.pass_id "
            + "WHERE s.branch_id IN (%s) AND e.created_at >= ? AND e.created_at < ?%s"
            + " GROUP BY s.branch_id, x.purchase_branch_id";

    private final EntityManager entityManager;

    /** The pass filters of the request: product, sale-time category and product kinds (stored values). */
    public record PassFilter(Long productId, String categoryCode, List<String> kinds) {
    }

    /** Rows of (using branch, selling branch, stays). */
    public List<Object[]> countSessions(List<Long> branches, Instant from, Instant to, PassFilter filter) {
        return runQuery(SESSIONS_SQL, branches, from, to, filter);
    }

    /**
     * Rows of (using branch, selling branch, minutes, won, ledger rows). A deduction is a USE row of a stay or a row of a stay
     * correction; a manual adjustment tied to no stay has no branch and is left out.
     */
    public List<Object[]> sumDeductions(List<Long> branches, Instant from, Instant to, PassFilter filter) {
        return runQuery(DEDUCTIONS_SQL, branches, from, to, filter);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> runQuery(String template, List<Long> branches, Instant from, Instant to, PassFilter filter) {
        StringBuilder extra = new StringBuilder();
        List<Object> binds = new ArrayList<>(branches);
        binds.add(from);
        binds.add(to);

        if (filter.productId() != null) {
            extra.append(" AND x.product_id = ?");
            binds.add(filter.productId());
        }

        if (filter.categoryCode() != null) {
            extra.append(" AND x.snapshot_category_code COLLATE utf8mb4_0900_bin = ?");
            binds.add(filter.categoryCode());
        }

        if (!filter.kinds().isEmpty()) {
            extra.append(" AND x.kind IN (")
                    .append(toPlaceholders(filter.kinds().size()))
                    .append(")");
            binds.addAll(filter.kinds());
        }

        Query query = entityManager.createNativeQuery(template.formatted(toPlaceholders(branches.size()), extra));

        for (int index = 0; index < binds.size(); index++) {
            query.setParameter(index + 1, binds.get(index));
        }

        return (List<Object[]>) query.getResultList();
    }

    private static String toPlaceholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
