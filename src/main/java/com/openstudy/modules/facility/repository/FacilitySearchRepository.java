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
    private static final String NEXT_ON = "CASE WHEN f.last_inspected_on IS NULL"
            + " THEN DATE(CONVERT_TZ(f.created_at, '+00:00', ?)) "
            + "ELSE DATE_ADD(f.last_inspected_on, INTERVAL f.inspection_interval_days DAY) END";
    /** Due when the next date is today or earlier; a facility out of service is never asked for an inspection. */
    private static final String DUE = "(f.status <> 'OUT_OF_SERVICE' AND " + NEXT_ON + " <= ?)";
    /** The facility's open inspection work: the newest one, the same rule the service applies when it reads works. */
    private static final String FROM = "facilities f LEFT JOIN facility_works w ON w.id = ("
            + "SELECT MAX(x.id) FROM facility_works x "
            + "WHERE x.facility_id = f.id AND x.kind = 'INSPECTION' AND x.status IN ('OPEN', 'IN_PROGRESS'))";
    private static final String COUNT_COLUMNS = "SELECT f.branch_id, COUNT(*), SUM(f.status = 'NORMAL'), "
            + "SUM(f.status = 'CHECK_NEEDED'), SUM(f.status = 'BROKEN'), SUM(f.status = 'OUT_OF_SERVICE'), "
            + "SUM(" + DUE + ") ";

    private final EntityManager entityManager;

    public enum Sort {
        NEXT_INSPECTION_ON(NEXT_ON, true),
        NAME("f.name", false),
        CREATED_AT("f.created_at", false);

        final String expression;
        final boolean needsOffset;

        Sort(String expression, boolean needsOffset) {
            this.expression = expression;
            this.needsOffset = needsOffset;
        }
    }

    /**
     * {@code offset} is the UTC offset of Asia/Seoul at the time of the request, e.g. "+09:00"; {@code today} is the date
     * there. {@code dueOnly} keeps only facilities that are due.
     */
    public record Filter(
            Set<Long> branches,
            String q,
            Long type,
            Long assignee,
            FacilityWorkStatus workStatus,
            boolean dueOnly,
            FacilityStatus status,
            LocalDate today,
            String offset
    ) {
    }

    public record Page(List<Facility> rows, long total) {
    }

    public record BranchCounts(
            long branchId,
            long total,
            long normal,
            long checkNeeded,
            long broken,
            long outOfService,
            long inspectionDue
    ) {
    }

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        appendWhere(filter, where, binds);
        List<Object> rowBinds = new ArrayList<>(binds);

        if (sort.needsOffset) {
            rowBinds.add(filter.offset());
        }

        String order = descending ? " DESC" : " ASC";
        String rowsSql = "SELECT f.* FROM " + FROM + " WHERE " + where
                + " ORDER BY " + sort.expression + order + ", f.id" + order + " LIMIT ? OFFSET ?";
        Query rows = entityManager.createNativeQuery(rowsSql, Facility.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + FROM + " WHERE " + where);
        bindAll(rows, rowBinds);
        bindAll(count, binds);
        rows.setParameter(rowBinds.size() + 1, limit);
        rows.setParameter(rowBinds.size() + 2, offset);

        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<BranchCounts> countByBranch(Filter filter) {
        if (filter.branches().isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        binds.add(filter.offset());
        binds.add(filter.today());
        appendWhere(filter, where, binds);

        Query query = entityManager.createNativeQuery(COUNT_COLUMNS + "FROM " + FROM + " WHERE " + where
                + " GROUP BY f.branch_id");
        bindAll(query, binds);

        return ((List<Object[]>) query.getResultList())
                .stream()
                .map(row -> new BranchCounts(
                        toLong(row[0]),
                        toLong(row[1]),
                        toLong(row[2]),
                        toLong(row[3]),
                        toLong(row[4]),
                        toLong(row[5]),
                        toLong(row[6])
                ))
                .toList();
    }

    private static void appendWhere(Filter filter, StringBuilder where, List<Object> binds) {
        List<Long> branches = filter.branches()
                .stream()
                .sorted()
                .toList();
        where.append("f.branch_id IN (")
                .append(String.join(",", Collections.nCopies(branches.size(), "?")))
                .append(")");
        binds.addAll(branches);

        if (filter.status() != null) {
            where.append(" AND f.status = ?");
            binds.add(filter.status().name());
        }

        if (filter.type() != null) {
            where.append(" AND f.type_id = ?");
            binds.add(filter.type());
        }

        if (filter.assignee() != null) {
            where.append(" AND w.assigned_employee_id = ?");
            binds.add(filter.assignee());
        }

        if (filter.workStatus() != null) {
            where.append(" AND w.status = ?");
            binds.add(filter.workStatus().name());
        }

        if (filter.dueOnly()) {
            where.append(" AND ").append(DUE);
            binds.add(filter.offset());
            binds.add(filter.today());
        }

        if (!filter.q().isEmpty()) {
            where.append(" AND (f.name LIKE ? ESCAPE '!' OR f.location LIKE ? ESCAPE '!')");
            binds.add("%" + escapeLike(filter.q()) + "%");
            binds.add("%" + escapeLike(filter.q()) + "%");
        }
    }

    private static void bindAll(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) {
            query.setParameter(index + 1, binds.get(index));
        }
    }

    private static long toLong(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
