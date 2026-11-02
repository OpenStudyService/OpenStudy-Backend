package com.openstudy.modules.locker.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.locker.entity.Locker;
import com.openstudy.modules.locker.entity.LockerAssignmentState;

/**
 * Branch-scoped locker reads in the contract's fixed order (lockerNo, id). The filter and the counts both come from
 * {@link LockerOccupation} and run in SQL, so a page and its total are the same set the occupancy counts.
 */
@Repository
@RequiredArgsConstructor
public class LockerSearchRepository {
    private final EntityManager entityManager;

    public record Page(List<Locker> rows, long total) {}

    public record BranchCounts(long branchId, long total, long inUse, long available, long blocked, long overdue) {}

    /** A null state means "no restriction". */
    public Page find(Set<Long> branches, LockerAssignmentState state, LocalDate today, long offset, int limit) {
        if (branches.isEmpty()) return new Page(List.of(), 0);
        String where = " FROM Locker l WHERE l.branchId IN :branches" + (state == null ? "" : " AND " + clause(state));
        var rows = entityManager.createQuery("SELECT l" + where + " ORDER BY l.lockerNo ASC, l.id ASC", Locker.class);
        var count = entityManager.createQuery("SELECT COUNT(l)" + where, Long.class);
        rows.setParameter("branches", branches);
        count.setParameter("branches", branches);
        if (state != null && state != LockerAssignmentState.BLOCKED) {
            rows.setParameter("today", today);
            count.setParameter("today", today);
        }
        // The API allows any page; JPA offsets are ints, and no branch holds that many lockers.
        var found = offset > Integer.MAX_VALUE ? List.<Locker>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page(found, count.getSingleResult());
    }

    /** Only branches with lockers come back. Blocked wins, so a blocked locker with an assignment counts only as blocked. */
    public List<BranchCounts> counts(Set<Long> branches, LocalDate today) {
        if (branches.isEmpty()) return List.of();
        var rows = entityManager.createQuery("SELECT l.branchId, COUNT(l), "
                + "SUM(CASE WHEN " + clause(LockerAssignmentState.IN_USE) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + clause(LockerAssignmentState.AVAILABLE) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + clause(LockerAssignmentState.BLOCKED) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + LockerOccupation.AVAILABLE + " AND " + LockerOccupation.OVERDUE + " THEN 1 ELSE 0 END) "
                + "FROM Locker l WHERE l.branchId IN :branches GROUP BY l.branchId", Object[].class)
                .setParameter("branches", branches).setParameter("today", today).getResultList();
        return rows.stream().map(row -> new BranchCounts(number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]), number(row[5]))).toList();
    }

    private static String clause(LockerAssignmentState state) {
        return switch (state) {
            case BLOCKED -> LockerOccupation.BLOCKED;
            case IN_USE -> LockerOccupation.AVAILABLE + " AND " + LockerOccupation.OCCUPIED;
            case AVAILABLE -> LockerOccupation.AVAILABLE + " AND NOT " + LockerOccupation.OCCUPIED;
        };
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
}
