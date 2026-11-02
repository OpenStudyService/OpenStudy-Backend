package com.openstudy.modules.inquiry.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.inquiry.entity.Inquiry;
import com.openstudy.modules.inquiry.entity.InquiryEvent;
import com.openstudy.modules.inquiry.entity.InquiryStatus;

/**
 * Branch-scoped inquiry reads. The list and the summary build their WHERE clause here, from one filter, so a count can
 * never disagree with the list it stands for. Only fixed fragments are concatenated: the sort column comes from a
 * closed enum and every value is bound. The customer is joined only to search by name; no customer column is read.
 */
@Repository
@RequiredArgsConstructor
public class InquirySearchRepository {
    private static final String FROM = " FROM inquiries i JOIN customers c ON c.id = i.customer_id WHERE ";
    private final EntityManager entityManager;

    public enum Sort {
        CREATED_AT("i.created_at"), UPDATED_AT("i.updated_at");
        final String column;
        Sort(String column) { this.column = column; }
    }

    public record Filter(Set<Long> branches, String q, InquiryStatus status, Long assignee, Long customer) {}

    public record Page(List<Inquiry> rows, long total) {}

    public record BranchCounts(long branchId, long total, long received, long inProgress, long answered) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        String order = descending ? " DESC" : " ASC";
        Query rows = entityManager.createNativeQuery("SELECT i.*" + FROM + where
                + " ORDER BY " + sort.column + order + ", i.id" + order + " LIMIT ? OFFSET ?", Inquiry.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + FROM + where);
        bind(rows, binds);
        bind(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<BranchCounts> counts(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        Query query = entityManager.createNativeQuery("SELECT i.branch_id, COUNT(*), SUM(i.status = 'RECEIVED'), "
                + "SUM(i.status = 'IN_PROGRESS'), SUM(i.status = 'ANSWERED')" + FROM + where + " GROUP BY i.branch_id");
        bind(query, binds);
        return ((List<Object[]>) query.getResultList()).stream().map(row -> new BranchCounts(number(row[0]), number(row[1]),
                number(row[2]), number(row[3]), number(row[4]))).toList();
    }

    @SuppressWarnings("unchecked")
    public List<InquiryEvent> events(long inquiry, long offset, int limit) {
        Query rows = entityManager.createNativeQuery(
                "SELECT e.* FROM inquiry_events e WHERE e.inquiry_id = ? ORDER BY e.id DESC LIMIT ? OFFSET ?", InquiryEvent.class);
        rows.setParameter(1, inquiry);
        rows.setParameter(2, limit);
        rows.setParameter(3, offset);
        return rows.getResultList();
    }

    private static void where(Filter filter, StringBuilder where, List<Object> binds) {
        var branches = filter.branches().stream().sorted().toList();
        where.append("i.branch_id IN (").append(String.join(",", Collections.nCopies(branches.size(), "?"))).append(")");
        binds.addAll(branches);
        if (filter.status() != null) { where.append(" AND i.status = ?"); binds.add(filter.status().name()); }
        if (filter.assignee() != null) { where.append(" AND i.assigned_employee_id = ?"); binds.add(filter.assignee()); }
        if (filter.customer() != null) { where.append(" AND i.customer_id = ?"); binds.add(filter.customer()); }
        if (!filter.q().isEmpty()) {
            where.append(" AND (i.subject LIKE ? ESCAPE '!' OR c.name LIKE ? ESCAPE '!')");
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
