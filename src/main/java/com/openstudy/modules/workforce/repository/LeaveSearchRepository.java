package com.openstudy.modules.workforce.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.workforce.entity.AttendanceCorrection;
import com.openstudy.modules.workforce.entity.LeaveGrant;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.ShiftSwap;

/**
 * Leave grants, leave requests, shift swaps and attendance corrections by their deciding branch (workforce-requests-api.md "조회 범위"): a branch the reader manages
 * shows everyone's, and the reader's own are shown whatever their branch, because the balance is per employee
 * (M12-D02). A branch scope narrows both parts; an employee filter only narrows further. Only fixed fragments are
 * concatenated and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class LeaveSearchRepository {

    private final EntityManager entityManager;

    /**
     * {@code managed}: the branches of the scope the reader manages. {@code ownBranches}: null for every branch of the
     * reader's own rows (scope all), or the scope's single branch. employee may be null for "no restriction".
     */
    public record Filter(Set<Long> managed, long reader, Set<Long> ownBranches, Long employee) {
    }

    public record Page<T>(List<T> rows, long total) {
    }

    /** Grants by origin branch, earliest expiry first, then id; expired ones only when asked for. */
    public Page<LeaveGrant> findGrants(Filter filter, Integer year, LocalDate validOn, long offset, int limit) {
        Map<String, Object> binds = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(createWhere("LeaveGrant g", "g", "g.originBranchId", filter, binds));
        appendCondition(where, binds, "g.grantYear = :year", "year", year);
        appendCondition(where, binds, "g.validThrough >= :validOn", "validOn", validOn);

        return findPage(
                where.toString(),
                "g",
                " ORDER BY g.validThrough, g.id",
                LeaveGrant.class,
                binds,
                offset,
                limit
        );
    }

    /** Requests by branch, the latest start first, then id; [from, to] keeps requests whose days touch it. */
    public Page<LeaveRequest> findRequests(
            Filter filter,
            LeaveRequest.Status status,
            LocalDate from,
            LocalDate to,
            long offset,
            int limit
    ) {
        Map<String, Object> binds = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(createWhere("LeaveRequest l", "l", "l.branchId", filter, binds));
        appendCondition(where, binds, "l.status = :status", "status", status);
        appendCondition(where, binds, "l.endsOn >= :from", "from", from);
        appendCondition(where, binds, "l.startsOn <= :to", "to", to);

        return findPage(
                where.toString(),
                "l",
                " ORDER BY l.startsOn DESC, l.id DESC",
                LeaveRequest.class,
                binds,
                offset,
                limit
        );
    }

    /**
     * Swaps by their shift's branch (M8-D02): the readable branches of the scope, then a managed branch shows every swap,
     * any other branch only those the reader requested or was named in. {@code person} narrows to swaps where that
     * employee is the requester or the replacement. [from, to) is on the proposed start, the day the swap is worked.
     */
    public Page<ShiftSwap> findSwaps(
            Set<Long> readable,
            Set<Long> managed,
            long reader,
            Long person,
            LeaveRequest.Status status,
            Instant from,
            Instant to,
            long offset,
            int limit
    ) {
        if (readable.isEmpty()) {
            return new Page<>(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(
                " FROM ShiftSwap w, Shift s WHERE s.id = w.originalShiftId AND s.branchId IN :readable AND ("
        );
        binds.put("readable", readable);

        if (!managed.isEmpty()) {
            where.append("s.branchId IN :managed OR ");
            binds.put("managed", managed);
        }

        where.append("w.requestedBy = :reader OR w.replacementEmployeeId = :reader)");
        binds.put("reader", reader);

        if (person != null) {
            where.append(" AND (w.requestedBy = :person OR w.replacementEmployeeId = :person)");
            binds.put("person", person);
        }

        appendCondition(where, binds, "w.status = :status", "status", status);
        appendCondition(where, binds, "w.proposedStartsAt >= :from", "from", from);
        appendCondition(where, binds, "w.proposedStartsAt < :to", "to", to);
        TypedQuery<ShiftSwap> rows = entityManager.createQuery(
                "SELECT w" + where + " ORDER BY w.createdAt DESC, w.id DESC",
                ShiftSwap.class
        );
        Query count = entityManager.createQuery("SELECT COUNT(w)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        List<ShiftSwap> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page<>(found, ((Number) count.getSingleResult()).longValue());
    }

    /**
     * Corrections by their attendance's branch (M8-D02): the readable branches of the scope, then a managed branch shows
     * every correction, any other branch only the reader's own. [from, to) is on the attendance's current clock-in, the
     * start of its Seoul workDate.
     */
    public Page<AttendanceCorrection> findCorrections(
            Set<Long> readable,
            Set<Long> managed,
            long reader,
            Long person,
            AttendanceCorrection.Status status,
            Long attendance,
            Instant from,
            Instant to,
            long offset,
            int limit
    ) {
        if (readable.isEmpty()) {
            return new Page<>(List.of(), 0);
        }

        Map<String, Object> binds = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(
                " FROM AttendanceCorrection c, AttendanceRecord a WHERE a.id = c.attendanceRecordId"
                        + " AND a.branchId IN :readable AND ("
        );
        binds.put("readable", readable);

        if (!managed.isEmpty()) {
            where.append("a.branchId IN :managed OR ");
            binds.put("managed", managed);
        }

        where.append("c.requestedBy = :reader)");
        binds.put("reader", reader);
        appendCondition(where, binds, "c.requestedBy = :person", "person", person);
        appendCondition(where, binds, "c.status = :status", "status", status);
        appendCondition(where, binds, "c.attendanceRecordId = :attendance", "attendance", attendance);
        appendCondition(where, binds, "a.clockInAt >= :from", "from", from);
        appendCondition(where, binds, "a.clockInAt < :to", "to", to);
        TypedQuery<AttendanceCorrection> rows = entityManager.createQuery(
                "SELECT c" + where + " ORDER BY c.createdAt DESC, c.id DESC",
                AttendanceCorrection.class
        );
        Query count = entityManager.createQuery("SELECT COUNT(c)" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        List<AttendanceCorrection> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page<>(found, ((Number) count.getSingleResult()).longValue());
    }

    private <T> Page<T> findPage(
            String where,
            String alias,
            String order,
            Class<T> type,
            Map<String, Object> binds,
            long offset,
            int limit
    ) {
        TypedQuery<T> rows = entityManager.createQuery("SELECT " + alias + where + order, type);
        Query count = entityManager.createQuery("SELECT COUNT(" + alias + ")" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no employee has that many leave rows.
        List<T> found = offset > Integer.MAX_VALUE
                ? List.of()
                : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();

        return new Page<>(found, ((Number) count.getSingleResult()).longValue());
    }

    private static String createWhere(
            String entity,
            String alias,
            String branch,
            Filter filter,
            Map<String, Object> binds
    ) {
        StringBuilder where = new StringBuilder(" FROM ")
                .append(entity)
                .append(" WHERE (");

        if (!filter.managed().isEmpty()) {
            where.append(branch).append(" IN :managed OR ");
            binds.put("managed", filter.managed());
        }

        where.append("(").append(alias).append(".employeeId = :reader");
        binds.put("reader", filter.reader());

        if (filter.ownBranches() != null) {
            where.append(" AND ").append(branch).append(" IN :ownBranches");
            binds.put("ownBranches", filter.ownBranches());
        }

        where.append("))");
        appendCondition(where, binds, alias + ".employeeId = :employee", "employee", filter.employee());

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
