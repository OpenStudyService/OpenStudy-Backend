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
    private static final String COUNT_COLUMNS = "SELECT i.branch_id, COUNT(*), SUM(i.status = 'RECEIVED'), "
            + "SUM(i.status = 'IN_PROGRESS'), SUM(i.status = 'ANSWERED')";
    private static final String EVENTS_SQL = """
            SELECT e.*
            FROM inquiry_events e
            WHERE e.inquiry_id = ?
            ORDER BY e.id DESC
            LIMIT ? OFFSET ?
            """;

    private final EntityManager entityManager;

    public enum Sort {
        CREATED_AT("i.created_at"),
        UPDATED_AT("i.updated_at");

        final String column;

        Sort(String column) {
            this.column = column;
        }
    }

    public record Filter(Set<Long> branches, String q, InquiryStatus status, Long assignee, Long customer) {
    }

    public record Page(List<Inquiry> rows, long total) {
    }

    public record BranchCounts(long branchId, long total, long received, long inProgress, long answered) {
    }

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) {
            return new Page(List.of(), 0);
        }

        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        appendWhere(filter, where, binds);

        String order = descending ? " DESC" : " ASC";
        String rowsSql = "SELECT i.*" + FROM + where
                + " ORDER BY " + sort.column + order + ", i.id" + order + " LIMIT ? OFFSET ?";
        Query rows = entityManager.createNativeQuery(rowsSql, Inquiry.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + FROM + where);
        bindAll(rows, binds);
        bindAll(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);

        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<BranchCounts> countByBranch(Filter filter) {
        if (filter.branches().isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        appendWhere(filter, where, binds);

        Query query = entityManager.createNativeQuery(COUNT_COLUMNS + FROM + where + " GROUP BY i.branch_id");
        bindAll(query, binds);

        return ((List<Object[]>) query.getResultList())
                .stream()
                .map(row -> new BranchCounts(
                        toLong(row[0]),
                        toLong(row[1]),
                        toLong(row[2]),
                        toLong(row[3]),
                        toLong(row[4])
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<InquiryEvent> findEvents(long inquiry, long offset, int limit) {
        Query rows = entityManager.createNativeQuery(EVENTS_SQL, InquiryEvent.class);
        rows.setParameter(1, inquiry);
        rows.setParameter(2, limit);
        rows.setParameter(3, offset);

        return rows.getResultList();
    }

    private static void appendWhere(Filter filter, StringBuilder where, List<Object> binds) {
        List<Long> branches = filter.branches()
                .stream()
                .sorted()
                .toList();
        where.append("i.branch_id IN (")
                .append(String.join(",", Collections.nCopies(branches.size(), "?")))
                .append(")");
        binds.addAll(branches);

        if (filter.status() != null) {
            where.append(" AND i.status = ?");
            binds.add(filter.status().name());
        }

        if (filter.assignee() != null) {
            where.append(" AND i.assigned_employee_id = ?");
            binds.add(filter.assignee());
        }

        if (filter.customer() != null) {
            where.append(" AND i.customer_id = ?");
            binds.add(filter.customer());
        }

        if (!filter.q().isEmpty()) {
            where.append(" AND (i.subject LIKE ? ESCAPE '!' OR c.name LIKE ? ESCAPE '!')");
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
