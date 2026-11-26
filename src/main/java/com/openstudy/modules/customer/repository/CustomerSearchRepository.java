package com.openstudy.modules.customer.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The visibility predicate below uses the branch list four times. */
    private static final int BRANCH_BIND_COPIES = 4;
    private static final String BRANCH_JOIN = " LEFT JOIN branches b ON b.id = c.registered_branch_id";

    private final EntityManager entityManager;

    public enum Sort {
        REGISTERED_AT("registeredAt", "c.registered_at"),
        NAME("name", "c.name"),
        MEMBER_NO("memberNo", "c.member_no"),
        PHONE("phone", "c.phone"),
        STATUS("status", "c.status"),
        REGISTERED_BRANCH("registeredBranch", "b.name");

        private final String key;
        final String column;

        Sort(String key, String column) {
            this.key = key;
            this.column = column;
        }

        public static Set<String> keys() {
            return Arrays.stream(values())
                    .map(sort -> sort.key)
                    .collect(Collectors.toUnmodifiableSet());
        }

        public static Sort fromKey(String key) {
            return Arrays.stream(values())
                    .filter(sort -> sort.key.equals(key))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /** {@code phone} is the digits-only form of q, or null when q is not a phone fragment. */
    public record Filter(Set<Long> branches, boolean unassigned, CustomerStatus status, String q, String phone) {
    }

    public record Page(List<Customer> rows, long total) {
    }

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();

        if (!appendVisibility(filter, where, binds)) {
            return new Page(List.of(), 0);
        }

        appendStatus(filter, where, binds);
        appendKeyword(filter, where, binds);

        String order = descending ? " DESC" : " ASC";
        String branchJoin = sort == Sort.REGISTERED_BRANCH ? BRANCH_JOIN : "";
        String rowsSql = "SELECT c.* FROM customers c" + branchJoin + " WHERE " + where
                + " ORDER BY " + sort.column + order + ", c.id" + order + " LIMIT ? OFFSET ?";

        Query rows = entityManager.createNativeQuery(rowsSql, Customer.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM customers c WHERE " + where);
        bindAll(rows, binds);
        bindAll(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);

        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    public boolean isVisible(long id, Set<Long> branches, boolean unassigned) {
        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();

        if (!appendVisibility(new Filter(branches, unassigned, null, "", null), where, binds)) {
            return false;
        }

        where.append(" AND c.id = ?");
        binds.add(id);

        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM customers c WHERE " + where);
        bindAll(count, binds);

        return ((Number) count.getSingleResult()).longValue() > 0;
    }

    /** Appends the scope predicate; false when nothing can be visible, so no empty IN () is ever issued. */
    private static boolean appendVisibility(Filter filter, StringBuilder where, List<Object> binds) {
        List<String> any = new ArrayList<>();

        if (!filter.branches().isEmpty()) {
            String marks = String.join(",", Collections.nCopies(filter.branches().size(), "?"));
            any.add("c.registered_branch_id IN (" + marks + ")");
            any.add("EXISTS (SELECT 1 FROM payments p WHERE p.customer_id = c.id AND p.branch_id IN (" + marks + "))");
            // A target is also visible where one of its absorbed customers registered or paid (M13-D07).
            any.add("EXISTS (SELECT 1 FROM customers a WHERE a.merged_into_customer_id = c.id"
                    + " AND (a.registered_branch_id IN (" + marks + ")"
                    + " OR EXISTS (SELECT 1 FROM payments q WHERE q.customer_id = a.id AND q.branch_id IN (" + marks
                    + "))))");

            List<Long> sorted = filter.branches()
                    .stream()
                    .sorted()
                    .toList();

            for (int copy = 0; copy < BRANCH_BIND_COPIES; copy++) {
                binds.addAll(sorted);
            }
        }

        if (filter.unassigned()) {
            any.add("c.registered_branch_id IS NULL");
        }

        if (any.isEmpty()) {
            return false;
        }

        where.append("(").append(String.join(" OR ", any)).append(")");
        return true;
    }

    private static void appendStatus(Filter filter, StringBuilder where, List<Object> binds) {
        if (filter.status() != null) {
            where.append(" AND c.status = ?");
            binds.add(filter.status().name());
            return;
        }

        // A merged customer is listed only when asked for (M13-D07); the target stands for it.
        where.append(" AND c.status <> 'MERGED'");
    }

    private static void appendKeyword(Filter filter, StringBuilder where, List<Object> binds) {
        if (filter.q().isEmpty()) {
            return;
        }

        where.append(" AND (c.name LIKE ? ESCAPE '!' OR c.member_no = ?");
        binds.add("%" + escapeLike(filter.q()) + "%");
        binds.add(filter.q());

        if (filter.phone() != null) {
            where.append(" OR c.phone LIKE ?");
            binds.add("%" + filter.phone() + "%");
        }

        where.append(")");
    }

    private static void bindAll(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) {
            query.setParameter(index + 1, binds.get(index));
        }
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
