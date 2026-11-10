package com.openstudy.modules.locker.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
    private final EntityManager entityManager;

    /** Any of lockerId, customerId and status may be null for "no restriction". */
    public record Filter(Set<Long> branches, Long lockerId, Long customerId, LockerAssignmentStatus status) {}

    public record Page(List<LockerAssignment> rows, long total) {}

    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var binds = new LinkedHashMap<String, Object>();
        String where = where(filter, binds);
        var rows = entityManager.createQuery("SELECT a" + where + " ORDER BY a.createdAt DESC, a.id DESC", LockerAssignment.class);
        Query count = entityManager.createQuery("SELECT COUNT(a)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch assigns that many lockers.
        var found = offset > Integer.MAX_VALUE ? List.<LockerAssignment>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** The whole record of one customer at these branches, in the list's order. */
    public List<LockerAssignment> ofCustomer(long customerId, Set<Long> branches) {
        if (branches.isEmpty()) return List.of();
        var binds = new LinkedHashMap<String, Object>();
        var rows = entityManager.createQuery("SELECT a" + where(new Filter(branches, null, customerId, null), binds)
                + " ORDER BY a.createdAt DESC, a.id DESC", LockerAssignment.class);
        binds.forEach(rows::setParameter);
        return rows.getResultList();
    }

    private static String where(Filter filter, Map<String, Object> binds) {
        var where = new StringBuilder(" FROM LockerAssignment a WHERE a.lockerId IN (SELECT l.id FROM Locker l WHERE l.branchId IN :branches)");
        binds.put("branches", filter.branches());
        add(where, binds, "a.lockerId = :lockerId", "lockerId", filter.lockerId());
        // A customer filter includes the customers merged into it (M13-D07, #114).
        add(where, binds, "a.customerId IN (SELECT m.id FROM Customer m WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId)", "customerId", filter.customerId());
        add(where, binds, "a.status = :status", "status", filter.status());
        return where.toString();
    }

    private static void add(StringBuilder where, Map<String, Object> binds, String clause, String name, Object value) {
        if (value == null) return;
        where.append(" AND ").append(clause);
        binds.put(name, value);
    }
}
