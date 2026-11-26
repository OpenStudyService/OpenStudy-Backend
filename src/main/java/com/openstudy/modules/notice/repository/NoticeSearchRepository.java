package com.openstudy.modules.notice.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.notice.entity.Notice;
import com.openstudy.modules.notice.entity.NoticeStatus;

/**
 * Notice reads. The visibility rule is part of the WHERE clause, so the status and branch restriction are applied
 * before the pinned order and the same clause counts the total. Only fixed fragments are concatenated; every value is
 * bound.
 */
@Repository
@RequiredArgsConstructor
public class NoticeSearchRepository {

    private static final String FROM = " FROM announcements a WHERE ";
    private static final String ORDER = " ORDER BY a.is_pinned DESC,"
            + " COALESCE(a.published_at, a.created_at) DESC, a.id DESC";

    private final EntityManager entityManager;

    /**
     * @param scope branches the reader asked for (never empty here)
     * @param owner an OWNER manages every notice
     * @param writable branches where the reader holds notices:write; a non-owner manages a notice only when every
     *                 target branch is in this set
     */
    public record Filter(Set<Long> scope, boolean owner, Set<Long> writable, String q, NoticeStatus status) {
    }

    public record Page(List<Notice> rows, long total) {
    }

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, long offset, int limit) {
        if (filter.scope().isEmpty()) {
            return new Page(List.of(), 0);
        }

        StringBuilder where = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        appendWhere(filter, where, binds);

        String rowsSql = "SELECT a.*" + FROM + where + ORDER + " LIMIT ? OFFSET ?";
        Query rows = entityManager.createNativeQuery(rowsSql, Notice.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + FROM + where);
        bindAll(rows, binds);
        bindAll(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);

        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    /** Target branches of the given notices, without loading the entity collections one by one. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> findBranchIds(Collection<Long> notices) {
        Map<Long, Set<Long>> result = new HashMap<>();

        if (notices.isEmpty()) {
            return result;
        }

        String sql = "SELECT announcement_id, branch_id FROM announcement_branches WHERE announcement_id IN ("
                + toMarks(notices.size()) + ")";
        Query query = entityManager.createNativeQuery(sql);
        int index = 1;

        for (long id : notices) {
            query.setParameter(index++, id);
        }

        for (Object[] row : (List<Object[]>) query.getResultList()) {
            long notice = ((Number) row[0]).longValue();
            long branch = ((Number) row[1]).longValue();
            result.computeIfAbsent(notice, key -> new TreeSet<>()).add(branch);
        }

        return result;
    }

    private static void appendWhere(Filter filter, StringBuilder where, List<Object> binds) {
        List<Long> scope = filter.scope()
                .stream()
                .sorted()
                .toList();

        // Reaches the reader's scope: addressed to everyone or to one of the scope branches.
        where.append("(a.target = 'ALL' OR EXISTS (SELECT 1 FROM announcement_branches b")
                .append(" WHERE b.announcement_id = a.id AND b.branch_id IN (")
                .append(toMarks(scope.size()))
                .append(")))");
        binds.addAll(scope);

        // Everybody sees published notices; drafts and archived ones only their managers.
        where.append(" AND (a.status = 'PUBLISHED'");

        if (filter.owner()) {
            where.append(" OR TRUE");
        } else if (!filter.writable().isEmpty()) {
            List<Long> writable = filter.writable()
                    .stream()
                    .sorted()
                    .toList();
            where.append(" OR (a.target = 'SELECTED_BRANCHES' AND NOT EXISTS (SELECT 1 FROM announcement_branches w")
                    .append(" WHERE w.announcement_id = a.id AND w.branch_id NOT IN (")
                    .append(toMarks(writable.size()))
                    .append(")))");
            binds.addAll(writable);
        }

        where.append(")");

        if (filter.status() != null) {
            where.append(" AND a.status = ?");
            binds.add(filter.status().name());
        }

        if (!filter.q().isEmpty()) {
            where.append(" AND a.title LIKE ? ESCAPE '!'");
            binds.add("%" + escapeLike(filter.q()) + "%");
        }
    }

    private static String toMarks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
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
