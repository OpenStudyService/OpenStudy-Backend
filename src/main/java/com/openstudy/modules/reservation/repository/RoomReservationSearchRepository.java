package com.openstudy.modules.reservation.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
    private final EntityManager entityManager;

    /** Any of status, roomId and customerId may be null for "no restriction". */
    public record Filter(Set<Long> branches, RoomReservationStatus status, Long roomId, Long customerId, TimeRange range) {}

    public record Page(List<RoomReservation> rows, long total) {}

    /** The list: latest start first, a fixed order. */
    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var binds = new LinkedHashMap<String, Object>();
        String where = where(filter, binds);
        var rows = entityManager.createQuery("SELECT r" + where + " ORDER BY r.startAt DESC, r.id DESC", RoomReservation.class);
        Query count = entityManager.createQuery("SELECT COUNT(r)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch books that far out.
        var found = offset > Integer.MAX_VALUE ? List.<RoomReservation>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** The calendar: every reservation of the period that took time, earliest first. A failed payment took none. */
    public List<RoomReservation> calendar(Filter filter) {
        var binds = new LinkedHashMap<String, Object>();
        String where = where(filter, binds) + " AND r.status <> :failed";
        binds.put("failed", RoomReservationStatus.PAYMENT_FAILED);
        var rows = entityManager.createQuery("SELECT r" + where + " ORDER BY r.startAt ASC, r.id ASC", RoomReservation.class);
        binds.forEach(rows::setParameter);
        return rows.getResultList();
    }

    private static String where(Filter filter, Map<String, Object> binds) {
        var where = new StringBuilder(" FROM RoomReservation r WHERE r.branchId IN :branches");
        binds.put("branches", filter.branches());
        add(where, binds, "r.status = :status", "status", filter.status());
        add(where, binds, "r.roomId = :roomId", "roomId", filter.roomId());
        add(where, binds, "r.customerId = :customerId", "customerId", filter.customerId());
        add(where, binds, "r.endAt > :from", "from", filter.range().from());
        add(where, binds, "r.startAt < :to", "to", filter.range().to());
        return where.toString();
    }

    private static void add(StringBuilder where, Map<String, Object> binds, String clause, String name, Object value) {
        if (value == null) return;
        where.append(" AND ").append(clause);
        binds.put(name, value);
    }
}
