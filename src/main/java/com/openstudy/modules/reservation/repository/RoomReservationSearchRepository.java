package com.openstudy.modules.reservation.repository;

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
import com.openstudy.modules.reservation.entity.RoomReservation;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;

/**
 * Reservations scoped to their own branch. Both reads share one WHERE clause, so a range always picks periods that
 * overlap it (endAt &gt; from and startAt &lt; to). Only fixed fragments are concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class RoomReservationSearchRepository {

    /** A customer filter includes the customers merged into it (M13-D07, #114). */
    private static final String CUSTOMER_FAMILY = "r.customerId IN (SELECT m.id FROM Customer m"
            + " WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId)";

    private final EntityManager entityManager;

    /** Any of status, roomId and customerId may be null for "no restriction". */
    public record Filter(
            Set<Long> branches,
            RoomReservationStatus status,
            Long roomId,
            Long customerId,
            TimeRange range
    ) {
    }

    public record Page(List<RoomReservation> rows, long total) {
    }

    /** The list: latest start first, a fixed order. */
    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String where = toWhere(filter, binds);
        String rowsJpql = "SELECT r" + where + " ORDER BY r.startAt DESC, r.id DESC";
        TypedQuery<RoomReservation> rows = entityManager.createQuery(rowsJpql, RoomReservation.class);
        Query count = entityManager.createQuery("SELECT COUNT(r)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch books that far out.
        List<RoomReservation> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** The calendar: every reservation of the period that took time, earliest first. A failed payment took none. */
    public List<RoomReservation> findCalendar(Filter filter) {
        Map<String, Object> binds = new LinkedHashMap<>();
        String where = toWhere(filter, binds) + " AND r.status <> :failed";
        binds.put("failed", RoomReservationStatus.PAYMENT_FAILED);
        String rowsJpql = "SELECT r" + where + " ORDER BY r.startAt ASC, r.id ASC";
        TypedQuery<RoomReservation> rows = entityManager.createQuery(rowsJpql, RoomReservation.class);
        binds.forEach(rows::setParameter);

        return rows.getResultList();
    }

    private static String toWhere(Filter filter, Map<String, Object> binds) {
        StringBuilder where = new StringBuilder(" FROM RoomReservation r WHERE r.branchId IN :branches");
        binds.put("branches", filter.branches());
        appendCondition(where, binds, "r.status = :status", "status", filter.status());
        appendCondition(where, binds, "r.roomId = :roomId", "roomId", filter.roomId());
        appendCondition(where, binds, CUSTOMER_FAMILY, "customerId", filter.customerId());
        appendCondition(where, binds, "r.endAt > :from", "from", filter.range().from());
        appendCondition(where, binds, "r.startAt < :to", "to", filter.range().to());

        return where.toString();
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
