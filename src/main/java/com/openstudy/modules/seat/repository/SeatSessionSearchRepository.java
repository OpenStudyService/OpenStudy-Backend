package com.openstudy.modules.seat.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.seat.entity.SeatSession;

/**
 * Stay history scoped to the branch of the stay (M5-D06). The range applies to the check-in instant. Only fixed fragments
 * are concatenated, so an absent filter drops out of the statement and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class SeatSessionSearchRepository {

    /** A customer filter includes the customers merged into it (M13-D07, #114). */
    private static final String CUSTOMER_FAMILY = "s.customerId IN (SELECT m.id FROM Customer m"
            + " WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId)";

    private final EntityManager entityManager;

    /** Any of status, seatId and customerId may be null for "no restriction". */
    public record Filter(Set<Long> branches, SeatSession.Status status, TimeRange range, Long seatId, Long customerId) {
    }

    public record Page(List<SeatSession> rows, long total) {
    }

    public Page find(Filter filter, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        StringBuilder where = new StringBuilder(" FROM SeatSession s WHERE s.branchId IN :branches");
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("branches", filter.branches());
        appendCondition(where, binds, "s.status = :status", "status", filter.status());
        appendCondition(where, binds, "s.checkedInAt >= :from", "from", filter.range().from());
        appendCondition(where, binds, "s.checkedInAt < :to", "to", filter.range().to());
        appendCondition(where, binds, "s.seatId = :seatId", "seatId", filter.seatId());
        appendCondition(where, binds, CUSTOMER_FAMILY, "customerId", filter.customerId());

        String order = descending ? " DESC" : " ASC";
        String rowsJpql = "SELECT s" + where + " ORDER BY s.checkedInAt" + order + ", s.id" + order;
        TypedQuery<SeatSession> rows = entityManager.createQuery(rowsJpql, SeatSession.class);
        Query count = entityManager.createQuery("SELECT COUNT(s)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no stay lives that far out.
        List<SeatSession> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    private static void appendCondition(
            StringBuilder where,
            Map<String, Object> binds,
            String clause,
            String name,
            Object value
    ) {
        if (value == null) {
            return;
        }

        where.append(" AND ").append(clause);
        binds.put(name, value);
    }
}
