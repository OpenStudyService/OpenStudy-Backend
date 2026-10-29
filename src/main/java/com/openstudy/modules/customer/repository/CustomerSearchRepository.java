package com.openstudy.modules.customer.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.customer.entity.Customer;
import com.openstudy.modules.customer.entity.CustomerStatus;

/**
 * Branch-scoped customer reads (M4-D05). A customer is visible when registered in, or paid at, one of the given
 * branches; rows with no registration branch only when {@code unassigned} is set (OWNER, scope=all). Filtering,
 * ordering and paging run in SQL so a list request never loads the whole ledger. Only fixed fragments are concatenated:
 * the sort column comes from a closed enum and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class CustomerSearchRepository {
    private final EntityManager entityManager;

    public enum Sort {
        REGISTERED_AT("c.registered_at"), NAME("c.name");
        final String column;
        Sort(String column) { this.column = column; }
    }

    /** {@code phone} is the digits-only form of q, or null when q is not a phone fragment. */
    public record Filter(Set<Long> branches, boolean unassigned, CustomerStatus status, String q, String phone) {}

    public record Page(List<Customer> rows, long total) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        if (!visibility(filter, where, binds)) return new Page(List.of(), 0);
        if (filter.status() != null) { where.append(" AND c.status = ?"); binds.add(filter.status().name()); }
        if (!filter.q().isEmpty()) {
            where.append(" AND (c.name LIKE ? ESCAPE '!' OR c.member_no = ?");
            binds.add("%" + like(filter.q()) + "%");
            binds.add(filter.q());
            if (filter.phone() != null) { where.append(" OR c.phone LIKE ?"); binds.add("%" + filter.phone() + "%"); }
            where.append(")");
        }
        String order = descending ? " DESC" : " ASC";
        Query rows = entityManager.createNativeQuery("SELECT c.* FROM customers c WHERE " + where
                + " ORDER BY " + sort.column + order + ", c.id" + order + " LIMIT ? OFFSET ?", Customer.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM customers c WHERE " + where);
        bind(rows, binds);
        bind(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    public boolean visible(long id, Set<Long> branches, boolean unassigned) {
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        if (!visibility(new Filter(branches, unassigned, null, "", null), where, binds)) return false;
        where.append(" AND c.id = ?");
        binds.add(id);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM customers c WHERE " + where);
        bind(count, binds);
        return ((Number) count.getSingleResult()).longValue() > 0;
    }

    /** Appends the scope predicate; false when nothing can be visible, so no empty IN () is ever issued. */
    private static boolean visibility(Filter filter, StringBuilder where, List<Object> binds) {
        List<String> any = new ArrayList<>();
        if (!filter.branches().isEmpty()) {
            String marks = String.join(",", java.util.Collections.nCopies(filter.branches().size(), "?"));
            any.add("c.registered_branch_id IN (" + marks + ")");
            any.add("EXISTS (SELECT 1 FROM payments p WHERE p.customer_id = c.id AND p.branch_id IN (" + marks + "))");
            var sorted = filter.branches().stream().sorted().toList();
            binds.addAll(sorted);
            binds.addAll(sorted);
        }
        if (filter.unassigned()) any.add("c.registered_branch_id IS NULL");
        if (any.isEmpty()) return false;
        where.append("(").append(String.join(" OR ", any)).append(")");
        return true;
    }

    private static void bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
    }

    private static String like(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
