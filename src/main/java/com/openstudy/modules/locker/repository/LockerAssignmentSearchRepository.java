package com.openstudy.modules.locker.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.locker.entity.LockerAssignment;
import com.openstudy.modules.locker.entity.LockerAssignmentStatus;

/**
 * Assignments scoped through their locker's branch, latest first. A customer known to another branch by exact lookup
 * does not widen this: the branch of the locker decides. Only fixed fragments are concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class LockerAssignmentSearchRepository {

    private static final String FROM = " FROM LockerAssignment a"
            + " WHERE a.lockerId IN (SELECT l.id FROM Locker l WHERE l.branchId IN :branches)";
    /** A customer filter includes the customers merged into it (M13-D07, #114). */
    private static final String CUSTOMER_FAMILY = "a.customerId IN (SELECT m.id FROM Customer m"
            + " WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId)";
    private static final String ORDER = " ORDER BY a.createdAt DESC, a.id DESC";

    private final EntityManager entityManager;

    /** Any of lockerId, customerId and status may be null for "no restriction". */
    public record Filter(Set<Long> branches, Long lockerId, Long customerId, LockerAssignmentStatus status) {
    }

    public record Page(List<LockerAssignment> rows, long total) {
    }

    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String where = toWhere(filter, binds);
        String rowsJpql = "SELECT a" + where + ORDER;
        TypedQuery<LockerAssignment> rows = entityManager.createQuery(rowsJpql, LockerAssignment.class);
        Query count = entityManager.createQuery("SELECT COUNT(a)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch assigns that many lockers.
        List<LockerAssignment> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** The whole record of one customer at these branches, in the list's order. */
    public List<LockerAssignment> findByCustomer(long customerId, Set<Long> branches) {
        if (branches.isEmpty()) {
            return List.of();
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String where = toWhere(new Filter(branches, null, customerId, null), binds);
        String rowsJpql = "SELECT a" + where + ORDER;
        TypedQuery<LockerAssignment> rows = entityManager.createQuery(rowsJpql, LockerAssignment.class);
        binds.forEach(rows::setParameter);

        return rows.getResultList();
    }

    private static String toWhere(Filter filter, Map<String, Object> binds) {
        StringBuilder where = new StringBuilder(FROM);
        binds.put("branches", filter.branches());
        appendCondition(where, binds, "a.lockerId = :lockerId", "lockerId", filter.lockerId());
        appendCondition(where, binds, CUSTOMER_FAMILY, "customerId", filter.customerId());
        appendCondition(where, binds, "a.status = :status", "status", filter.status());

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
