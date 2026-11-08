package com.openstudy.modules.workforce.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.workforce.entity.LeaveGrant;
import com.openstudy.modules.workforce.entity.LeaveRequest;

/**
 * Leave grants and requests by their deciding branch (workforce-requests-api.md "조회 범위"): a branch the reader manages
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
    public record Filter(Set<Long> managed, long reader, Set<Long> ownBranches, Long employee) {}

    public record Page<T>(List<T> rows, long total) {}

    /** Grants by origin branch, earliest expiry first, then id; expired ones only when asked for. */
    public Page<LeaveGrant> grants(Filter filter, Integer year, LocalDate validOn, long offset, int limit) {
        var binds = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(where("LeaveGrant g", "g", "g.originBranchId", filter, binds));
        add(where, binds, "g.grantYear = :year", "year", year);
        add(where, binds, "g.validThrough >= :validOn", "validOn", validOn);
        return page(where.toString(), "g", " ORDER BY g.validThrough, g.id", LeaveGrant.class, binds, offset, limit);
    }

    /** Requests by branch, the latest start first, then id; [from, to] keeps requests whose days touch it. */
    public Page<LeaveRequest> requests(Filter filter, LeaveRequest.Status status, LocalDate from, LocalDate to, long offset, int limit) {
        var binds = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(where("LeaveRequest l", "l", "l.branchId", filter, binds));
        add(where, binds, "l.status = :status", "status", status);
        add(where, binds, "l.endsOn >= :from", "from", from);
        add(where, binds, "l.startsOn <= :to", "to", to);
        return page(where.toString(), "l", " ORDER BY l.startsOn DESC, l.id DESC", LeaveRequest.class, binds, offset, limit);
    }

    private <T> Page<T> page(String where, String alias, String order, Class<T> type, Map<String, Object> binds, long offset, int limit) {
        var rows = entityManager.createQuery("SELECT " + alias + where + order, type);
        Query count = entityManager.createQuery("SELECT COUNT(" + alias + ")" + where);
        binds.forEach(rows::setParameter);
        binds.forEach(count::setParameter);
        // The API allows any page; JPA offsets are ints, and no employee has that many leave rows.
        var found = offset > Integer.MAX_VALUE ? List.<T>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page<>(found, ((Number) count.getSingleResult()).longValue());
    }

    private static String where(String entity, String alias, String branch, Filter filter, Map<String, Object> binds) {
        var where = new StringBuilder(" FROM ").append(entity).append(" WHERE (");
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
        add(where, binds, alias + ".employeeId = :employee", "employee", filter.employee());
        return where.toString();
    }

    private static void add(StringBuilder where, Map<String, Object> binds, String clause, String name, Object value) {
        if (value == null) return;
        where.append(" AND ").append(clause);
        binds.put(name, value);
    }
}
