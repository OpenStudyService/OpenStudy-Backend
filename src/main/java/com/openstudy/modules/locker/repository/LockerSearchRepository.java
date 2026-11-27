package com.openstudy.modules.locker.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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

    private static final String FROM = " FROM Locker l WHERE l.branchId IN :branches";

    private final EntityManager entityManager;

    public record Page(List<Locker> rows, long total) {
    }

    public record BranchCounts(long branchId, long total, long inUse, long available, long blocked, long overdue) {
    }

    /** A null state means "no restriction". */
    public Page find(Set<Long> branches, LockerAssignmentState state, LocalDate today, long offset, int limit) {
        if (branches.isEmpty()) {
            return new Page(List.of(), 0);
        }

        String where = FROM + (state == null ? "" : " AND " + toClause(state));
        TypedQuery<Locker> rows = entityManager.createQuery(
                "SELECT l" + where + " ORDER BY l.lockerNo ASC, l.id ASC",
                Locker.class
        );
        TypedQuery<Long> count = entityManager.createQuery("SELECT COUNT(l)" + where, Long.class);
        rows.setParameter("branches", branches);
        count.setParameter("branches", branches);

        if (state != null && state != LockerAssignmentState.BLOCKED) {
            rows.setParameter("today", today);
            count.setParameter("today", today);
        }

        // The API allows any page; JPA offsets are ints, and no branch holds that many lockers.
        List<Locker> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page(found, count.getSingleResult());
    }

    /** Only branches with lockers come back. Blocked wins, so a blocked locker with an assignment counts only as blocked. */
    public List<BranchCounts> countByBranch(Set<Long> branches, LocalDate today) {
        if (branches.isEmpty()) {
            return List.of();
        }

        String overdue = LockerOccupation.AVAILABLE + " AND " + LockerOccupation.OVERDUE;
        String jpql = "SELECT l.branchId, COUNT(l), "
                + "SUM(CASE WHEN " + toClause(LockerAssignmentState.IN_USE) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + toClause(LockerAssignmentState.AVAILABLE) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + toClause(LockerAssignmentState.BLOCKED) + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + overdue + " THEN 1 ELSE 0 END) "
                + "FROM Locker l WHERE l.branchId IN :branches GROUP BY l.branchId";
        List<Object[]> rows = entityManager.createQuery(jpql, Object[].class)
                .setParameter("branches", branches)
                .setParameter("today", today)
                .getResultList();

        return rows.stream()
                .map(row -> new BranchCounts(
                        toLong(row[0]),
                        toLong(row[1]),
                        toLong(row[2]),
                        toLong(row[3]),
                        toLong(row[4]),
                        toLong(row[5])
                ))
                .toList();
    }

    private static String toClause(LockerAssignmentState state) {
        return switch (state) {
            case BLOCKED -> LockerOccupation.BLOCKED;
            case IN_USE -> LockerOccupation.AVAILABLE + " AND " + LockerOccupation.OCCUPIED;
            case AVAILABLE -> LockerOccupation.AVAILABLE + " AND NOT " + LockerOccupation.OCCUPIED;
        };
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
