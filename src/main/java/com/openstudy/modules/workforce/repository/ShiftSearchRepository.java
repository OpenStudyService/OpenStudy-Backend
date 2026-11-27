package com.openstudy.modules.workforce.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
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

    private static final String SHIFT_ENTITY = "Shift s";
    private static final String SHIFT_ALIAS = "s";
    private static final String SHIFT_TIME = "s.startsAt";
    private static final String SHIFT_ORDER = " ORDER BY s.startsAt, s.id";
    private static final String ATTENDANCE_ENTITY = "AttendanceRecord a";
    private static final String ATTENDANCE_ALIAS = "a";
    private static final String ATTENDANCE_TIME = "a.clockInAt";
    private static final String OPEN_BREAK = "EXISTS (SELECT 1 FROM AttendanceBreak b WHERE b.attendanceId = a.id "
            + "AND b.endedAt IS NULL)";

    private final EntityManager entityManager;

    /**
     * Who reads what: {@code branches} are the readable branches of the scope, {@code managed} the part of them where the
     * reader is OWNER or MANAGER. employee, status and [from, to) may be null for "no restriction".
     */
    public record Filter(
            Set<Long> branches,
            Set<Long> managed,
            long reader,
            Long employee,
            ShiftStatus status,
            Instant from,
            Instant to
    ) {
    }

    public record Page(List<Shift> rows, long total) {
    }

    /**
     * What a clock-in is in now: {@code LEFT} stored, or WORKING with or without an open break. "휴게 중" is not stored,
     * so the list filter reads the breaks in the same query.
     */
    public enum AttendanceState {

        WORKING,
        ON_BREAK,
        LEFT
    }

    public record AttendancePage(List<AttendanceRecord> rows, long total) {
    }

    /** In the order the contract fixes: startsAt, then id, both ascending. */
    public Page find(Filter filter, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String whereText = appendStatus(
                createWhere(SHIFT_ENTITY, SHIFT_ALIAS, SHIFT_TIME, filter, binds),
                filter,
                SHIFT_ALIAS,
                binds
        );
        TypedQuery<Shift> rows = entityManager.createQuery("SELECT s" + whereText + SHIFT_ORDER, Shift.class);
        Query count = entityManager.createQuery("SELECT COUNT(s)" + whereText);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no branch plans that many shifts.
        List<Shift> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page(found, ((Number) count.getSingleResult()).longValue());
    }

    /** Every matching shift, for the weekly schedule, which has no pages. */
    public List<Shift> findAll(Filter filter) {
        if (filter.branches().isEmpty()) {
            return List.of();
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String whereText = appendStatus(
                createWhere(SHIFT_ENTITY, SHIFT_ALIAS, SHIFT_TIME, filter, binds),
                filter,
                SHIFT_ALIAS,
                binds
        );
        TypedQuery<Shift> rows = entityManager.createQuery("SELECT s" + whereText + SHIFT_ORDER, Shift.class);
        binds.forEach(rows::setParameter);

        return rows.getResultList();
    }

    /** Attendance under the same reader condition, by clock-in. The filter's status does not apply to attendance. */
    public List<AttendanceRecord> findAttendanceRecords(Filter filter) {
        if (filter.branches().isEmpty()) {
            return List.of();
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        String whereText = createWhere(ATTENDANCE_ENTITY, ATTENDANCE_ALIAS, ATTENDANCE_TIME, filter, binds);
        TypedQuery<AttendanceRecord> rows = entityManager.createQuery(
                "SELECT a" + whereText + " ORDER BY a.clockInAt, a.id",
                AttendanceRecord.class
        );
        binds.forEach(rows::setParameter);

        return rows.getResultList();
    }

    /**
     * The attendance list (FR-ATT-05) under the same reader condition as the shifts, by its recorded branch. The filter's
     * shift status does not apply; shift and state narrow further. Newest clock-in first, then id, both descending.
     */
    public AttendancePage findAttendance(Filter filter, Long shift, AttendanceState state, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new AttendancePage(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(
                createWhere(ATTENDANCE_ENTITY, ATTENDANCE_ALIAS, ATTENDANCE_TIME, filter, binds)
        );

        if (shift != null) {
            where.append(" AND a.shiftId = :shift");
            binds.put("shift", shift);
        }

        if (state != null) {
            AttendanceRecord.Status status = state == AttendanceState.LEFT
                    ? AttendanceRecord.Status.LEFT
                    : AttendanceRecord.Status.WORKING;
            binds.put("status", status);
            where.append(" AND a.status = :status");

            if (state == AttendanceState.WORKING) {
                where.append(" AND NOT ").append(OPEN_BREAK);
            }

            if (state == AttendanceState.ON_BREAK) {
                where.append(" AND ").append(OPEN_BREAK);
            }
        }

        TypedQuery<AttendanceRecord> rows = entityManager.createQuery(
                "SELECT a" + where + " ORDER BY a.clockInAt DESC, a.id DESC",
                AttendanceRecord.class
        );
        Query count = entityManager.createQuery("SELECT COUNT(a)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        List<AttendanceRecord> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new AttendancePage(found, ((Number) count.getSingleResult()).longValue());
    }

    private static String createWhere(
            String entity,
            String alias,
            String time,
            Filter filter,
            Map<String, Object> binds
    ) {
        StringBuilder where = new StringBuilder(" FROM ")
                .append(entity)
                .append(" WHERE ")
                .append(alias)
                .append(".branchId IN :branches AND (");
        binds.put("branches", filter.branches());

        if (!filter.managed().isEmpty()) {
            where.append(alias).append(".branchId IN :managed OR ");
            binds.put("managed", filter.managed());
        }

        where.append(alias).append(".employeeId = :reader)");
        binds.put("reader", filter.reader());
        appendCondition(where, binds, alias + ".employeeId = :employee", "employee", filter.employee());
        appendCondition(where, binds, time + " >= :from", "from", filter.from());
        appendCondition(where, binds, time + " < :to", "to", filter.to());

        return where.toString();
    }

    private static String appendStatus(String where, Filter filter, String alias, Map<String, Object> binds) {
        if (filter.status() == null) {
            return where;
        }

        binds.put("status", filter.status());

        return where + " AND " + alias + ".status = :status";
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
