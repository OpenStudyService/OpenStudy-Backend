package com.openstudy.modules.facility.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.facility.entity.Facility;
import com.openstudy.modules.facility.entity.FacilityStatus;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;

/**
 * Branch-scoped facility reads. The list and the summary build their WHERE clause here, from one filter, so a count can
 * never disagree with the list it stands for. The next inspection date and "inspection due" are computed in SQL from the
 * same two fragments for filtering, sorting and counting, so a page boundary and a total are decided by the same rule
 * as the value shown. Only fixed fragments are concatenated: the sort expression comes from a closed enum and every
 * value is bound.
 */
@Repository
@RequiredArgsConstructor
public class FacilitySearchRepository {
    /**
     * The date a facility is next due (M7-D06): last inspection + interval, or the registration date when it was never
     * inspected. created_at is stored in UTC; the single placeholder is the offset of Asia/Seoul, which avoids needing
     * the server's time zone tables.
     */
    private static final String NEXT_ON = "CASE WHEN f.last_inspected_on IS NULL THEN DATE(CONVERT_TZ(f.created_at, '+00:00', ?)) "
            + "ELSE DATE_ADD(f.last_inspected_on, INTERVAL f.inspection_interval_days DAY) END";
    /** Due when the next date is today or earlier; a facility out of service is never asked for an inspection. */
    private static final String DUE = "(f.status <> 'OUT_OF_SERVICE' AND " + NEXT_ON + " <= ?)";
    /** The facility's open inspection work: the newest one, the same rule the service applies when it reads works. */
    private static final String FROM = "facilities f LEFT JOIN facility_works w ON w.id = (SELECT MAX(x.id) FROM facility_works x "
            + "WHERE x.facility_id = f.id AND x.kind = 'INSPECTION' AND x.status IN ('OPEN', 'IN_PROGRESS'))";

    private final EntityManager entityManager;

    public enum Sort {
        NEXT_INSPECTION_ON(NEXT_ON, true), NAME("f.name", false), CREATED_AT("f.created_at", false);
        final String expression;
        final boolean needsOffset;
        Sort(String expression, boolean needsOffset) { this.expression = expression; this.needsOffset = needsOffset; }
    }

    /**
     * {@code offset} is the UTC offset of Asia/Seoul at the time of the request, e.g. "+09:00"; {@code today} is the date
     * there. {@code dueOnly} keeps only facilities that are due.
     */
    public record Filter(Set<Long> branches, String q, Long type, Long assignee, FacilityWorkStatus workStatus,
            boolean dueOnly, FacilityStatus status, LocalDate today, String offset) {}

    public record Page(List<Facility> rows, long total) {}

    public record BranchCounts(long branchId, long total, long normal, long checkNeeded, long broken, long outOfService,
            long inspectionDue) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        var rowBinds = new ArrayList<Object>(binds);
        if (sort.needsOffset) rowBinds.add(filter.offset());
        String order = descending ? " DESC" : " ASC";
        Query rows = entityManager.createNativeQuery("SELECT f.* FROM " + FROM + " WHERE " + where
                + " ORDER BY " + sort.expression + order + ", f.id" + order + " LIMIT ? OFFSET ?", Facility.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + FROM + " WHERE " + where);
        bind(rows, rowBinds);
        bind(count, binds);
        rows.setParameter(rowBinds.size() + 1, limit);
        rows.setParameter(rowBinds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<BranchCounts> counts(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        binds.add(filter.offset());
        binds.add(filter.today());
        where(filter, where, binds);
        Query query = entityManager.createNativeQuery("SELECT f.branch_id, COUNT(*), SUM(f.status = 'NORMAL'), "
                + "SUM(f.status = 'CHECK_NEEDED'), SUM(f.status = 'BROKEN'), SUM(f.status = 'OUT_OF_SERVICE'), SUM(" + DUE + ") "
                + "FROM " + FROM + " WHERE " + where + " GROUP BY f.branch_id");
        bind(query, binds);
        return ((List<Object[]>) query.getResultList()).stream().map(row -> new BranchCounts(number(row[0]), number(row[1]),
                number(row[2]), number(row[3]), number(row[4]), number(row[5]), number(row[6]))).toList();
    }

    private static void where(Filter filter, StringBuilder where, List<Object> binds) {
        var branches = filter.branches().stream().sorted().toList();
        where.append("f.branch_id IN (").append(String.join(",", Collections.nCopies(branches.size(), "?"))).append(")");
        binds.addAll(branches);
        if (filter.status() != null) { where.append(" AND f.status = ?"); binds.add(filter.status().name()); }
        if (filter.type() != null) { where.append(" AND f.type_id = ?"); binds.add(filter.type()); }
        if (filter.assignee() != null) { where.append(" AND w.assigned_employee_id = ?"); binds.add(filter.assignee()); }
        if (filter.workStatus() != null) { where.append(" AND w.status = ?"); binds.add(filter.workStatus().name()); }
        if (filter.dueOnly()) {
            where.append(" AND ").append(DUE);
            binds.add(filter.offset());
            binds.add(filter.today());
        }
        if (!filter.q().isEmpty()) {
            where.append(" AND (f.name LIKE ? ESCAPE '!' OR f.location LIKE ? ESCAPE '!')");
            binds.add("%" + like(filter.q()) + "%");
            binds.add("%" + like(filter.q()) + "%");
        }
    }

    private static void bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

    private static String like(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
