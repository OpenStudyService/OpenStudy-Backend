package com.openstudy.modules.workforce.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.workforce.entity.AttendanceRecord;
import com.openstudy.modules.workforce.entity.Shift;
import com.openstudy.modules.workforce.entity.ShiftStatus;

/**
 * Shifts and attendance by their recorded branch (M8-D02). Every query carries the reader condition together with the
 * branch set: a branch the reader manages shows everyone, any other branch only the reader's own records. The scope only
 * narrows the branches and an employee filter only narrows further, so neither can open another employee's record.
 * Only fixed fragments are concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class ShiftSearchRepository {
    private final EntityManager entityManager;

    /**
     * Who reads what: {@code branches} are the readable branches of the scope, {@code managed} the part of them where the
     * reader is OWNER or MANAGER. employee, status and [from, to) may be null for "no restriction".
     */
    public record Filter(Set<Long> branches, Set<Long> managed, long reader, Long employee, ShiftStatus status, Instant from, Instant to) {}

    public record Page(List<Shift> rows, long total) {}

    /** In the order the contract fixes: startsAt, then id, both ascending. */
    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var binds = new LinkedHashMap<String, Object>();
        String whereText = withStatus(where("Shift s", "s", "s.startsAt", filter, binds), filter, "s", binds);
        var rows = entityManager.createQuery("SELECT s" + whereText + " ORDER BY s.startsAt, s.id", Shift.class);
        Query count = entityManager.createQuery("SELECT COUNT(s)" + whereText);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch plans that many shifts.
        var found = offset > Integer.MAX_VALUE ? List.<Shift>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** Every matching shift, for the weekly schedule, which has no pages. */
    public List<Shift> all(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var binds = new LinkedHashMap<String, Object>();
        var rows = entityManager.createQuery("SELECT s" + withStatus(where("Shift s", "s", "s.startsAt", filter, binds), filter, "s", binds)
                + " ORDER BY s.startsAt, s.id", Shift.class);
        binds.forEach(rows::setParameter);
        return rows.getResultList();
    }

    /** Attendance under the same reader condition, by clock-in. The filter's status does not apply to attendance. */
    public List<AttendanceRecord> attendance(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var binds = new LinkedHashMap<String, Object>();
        var rows = entityManager.createQuery("SELECT a" + where("AttendanceRecord a", "a", "a.clockInAt", filter, binds)
                + " ORDER BY a.clockInAt, a.id", AttendanceRecord.class);
        binds.forEach(rows::setParameter);
        return rows.getResultList();
    }

    private static String where(String entity, String alias, String time, Filter filter, Map<String, Object> binds) {
        var where = new StringBuilder(" FROM ").append(entity).append(" WHERE ").append(alias).append(".branchId IN :branches AND (");
        binds.put("branches", filter.branches());
        if (!filter.managed().isEmpty()) {
            where.append(alias).append(".branchId IN :managed OR ");
            binds.put("managed", filter.managed());
        }
        where.append(alias).append(".employeeId = :reader)");
        binds.put("reader", filter.reader());
        add(where, binds, alias + ".employeeId = :employee", "employee", filter.employee());
        add(where, binds, time + " >= :from", "from", filter.from());
        add(where, binds, time + " < :to", "to", filter.to());
        return where.toString();
    }

    private static String withStatus(String where, Filter filter, String alias, Map<String, Object> binds) {
        if (filter.status() == null) return where;
        binds.put("status", filter.status());
        return where + " AND " + alias + ".status = :status";
    }

    private static void add(StringBuilder where, Map<String, Object> binds, String clause, String name, Object value) {
        if (value == null) return;
        where.append(" AND ").append(clause);
        binds.put(name, value);
    }
}
