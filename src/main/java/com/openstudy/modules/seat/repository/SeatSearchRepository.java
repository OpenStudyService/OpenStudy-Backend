package com.openstudy.modules.seat.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.seat.entity.Seat;
import com.openstudy.modules.seat.entity.SeatDisplayStatus;
import com.openstudy.modules.seat.entity.SeatKind;

/**
 * Branch-scoped seat reads. The list and the counts build their WHERE clause from one filter, and "in use" always means
 * a row of seat_sessions whose generated active_seat_id (unique) points at the seat, so the two can never disagree.
 * Only fixed fragments are concatenated; every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class SeatSearchRepository {
    private static final String IN_USE = "EXISTS (SELECT 1 FROM seat_sessions q WHERE q.active_seat_id = s.id)";
    private static final String ORDER = " ORDER BY s.zone ASC, s.seat_no ASC, s.id ASC";
    private final EntityManager entityManager;

    /** Any of zone, kind and status may be null for "no restriction". */
    public record Filter(Set<Long> branches, String zone, SeatKind kind, SeatDisplayStatus status) {}

    public record Page(List<Seat> rows, long total) {}

    public record BranchCounts(long branchId, long total, long inUse, long available, long blocked) {}

    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        Query rows = entityManager.createNativeQuery("SELECT s.* FROM seats s WHERE " + where + ORDER + " LIMIT ? OFFSET ?", Seat.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM seats s WHERE " + where);
        bind(rows, binds);
        bind(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        @SuppressWarnings("unchecked") List<Seat> found = rows.getResultList();
        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** Every matching seat, unpaged: the board needs the whole branch on one screen. */
    @SuppressWarnings("unchecked")
    public List<Seat> findAll(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        Query rows = entityManager.createNativeQuery("SELECT s.* FROM seats s WHERE " + where + ORDER, Seat.class);
        bind(rows, binds);
        return rows.getResultList();
    }

    /** One statement, so a branch's four numbers come from the same snapshot. Branches without seats have no row. */
    @SuppressWarnings("unchecked")
    public List<BranchCounts> counts(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        Query query = entityManager.createNativeQuery("SELECT s.branch_id, COUNT(*), "
                + "SUM(" + IN_USE + "), SUM(s.status = 'AVAILABLE' AND NOT " + IN_USE + "), "
                + "SUM(s.status = 'BLOCKED' AND NOT " + IN_USE + ") FROM seats s WHERE " + where + " GROUP BY s.branch_id");
        bind(query, binds);
        return ((List<Object[]>) query.getResultList()).stream().map(row -> new BranchCounts(number(row[0]), number(row[1]),
                number(row[2]), number(row[3]), number(row[4]))).toList();
    }

    private static void where(Filter filter, StringBuilder where, List<Object> binds) {
        var branches = filter.branches().stream().sorted().toList();
        where.append("s.branch_id IN (").append(String.join(",", Collections.nCopies(branches.size(), "?"))).append(")");
        binds.addAll(branches);
        if (filter.zone() != null) { where.append(" AND s.zone = ?"); binds.add(filter.zone()); }
        if (filter.kind() != null) { where.append(" AND s.kind = ?"); binds.add(filter.kind().name()); }
        if (filter.status() != null) {
            switch (filter.status()) {
                case IN_USE -> where.append(" AND ").append(IN_USE);
                case AVAILABLE -> where.append(" AND s.status = 'AVAILABLE' AND NOT ").append(IN_USE);
                case BLOCKED -> where.append(" AND s.status = 'BLOCKED' AND NOT ").append(IN_USE);
            }
        }
    }

    private static void bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
}
