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
    private final EntityManager entityManager;

    /**
     * @param scope branches the reader asked for (never empty here)
     * @param owner an OWNER manages every notice
     * @param writable branches where the reader holds notices:write; a non-owner manages a notice only when every
     *                 target branch is in this set
     */
    public record Filter(Set<Long> scope, boolean owner, Set<Long> writable, String q, NoticeStatus status) {}

    public record Page(List<Notice> rows, long total) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, long offset, int limit) {
        if (filter.scope().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        Query rows = entityManager.createNativeQuery("SELECT a.*" + FROM + where
                + " ORDER BY a.is_pinned DESC, COALESCE(a.published_at, a.created_at) DESC, a.id DESC LIMIT ? OFFSET ?", Notice.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*)" + FROM + where);
        bind(rows, binds);
        bind(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    /** Target branches of the given notices, without loading the entity collections one by one. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> branchIds(Collection<Long> notices) {
        Map<Long, Set<Long>> result = new HashMap<>();
        if (notices.isEmpty()) return result;
        Query query = entityManager.createNativeQuery("SELECT announcement_id, branch_id FROM announcement_branches WHERE announcement_id IN ("
                + String.join(",", Collections.nCopies(notices.size(), "?")) + ")");
        int index = 1;
        for (long id : notices) query.setParameter(index++, id);
        for (Object[] row : (List<Object[]>) query.getResultList())
            result.computeIfAbsent(((Number) row[0]).longValue(), key -> new TreeSet<>()).add(((Number) row[1]).longValue());
        return result;
    }

    private static void where(Filter filter, StringBuilder where, List<Object> binds) {
        var scope = filter.scope().stream().sorted().toList();
        // Reaches the reader's scope: addressed to everyone or to one of the scope branches.
        where.append("(a.target = 'ALL' OR EXISTS (SELECT 1 FROM announcement_branches b WHERE b.announcement_id = a.id AND b.branch_id IN (")
                .append(String.join(",", Collections.nCopies(scope.size(), "?"))).append(")))");
        binds.addAll(scope);
        // Everybody sees published notices; drafts and archived ones only their managers.
        where.append(" AND (a.status = 'PUBLISHED'");
        if (filter.owner()) {
            where.append(" OR TRUE");
        } else if (!filter.writable().isEmpty()) {
            var writable = filter.writable().stream().sorted().toList();
            where.append(" OR (a.target = 'SELECTED_BRANCHES' AND NOT EXISTS (SELECT 1 FROM announcement_branches w WHERE w.announcement_id = a.id AND w.branch_id NOT IN (")
                    .append(String.join(",", Collections.nCopies(writable.size(), "?"))).append(")))");
            binds.addAll(writable);
        }
        where.append(")");
        if (filter.status() != null) { where.append(" AND a.status = ?"); binds.add(filter.status().name()); }
        if (!filter.q().isEmpty()) { where.append(" AND a.title LIKE ? ESCAPE '!'"); binds.add("%" + like(filter.q()) + "%"); }
    }

    private static void bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
    }

    private static String like(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
