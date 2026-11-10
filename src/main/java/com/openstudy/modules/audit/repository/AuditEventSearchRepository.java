package com.openstudy.modules.audit.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.audit.dto.request.AuditSearchCriteria;

/**
 * Reads audit_events with the actor and branch names (#122). Only fixed fragments are concatenated and every value is
 * bound. occurred_at is stored in UTC: bounds are bound as instants and the time is read back as UTC text, like the other
 * native searches.
 */
@Repository
@RequiredArgsConstructor
public class AuditEventSearchRepository {
    private static final String COLUMNS = "a.id, DATE_FORMAT(a.occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ'), a.action, a.result, "
            + "a.entity_type, a.entity_id, a.reason, a.request_id, a.actor_employee_id, e.name, a.branch_id, b.name, "
            + "(a.before_json IS NOT NULL OR a.after_json IS NOT NULL)";
    private static final String FROM = " FROM audit_events a LEFT JOIN employees e ON e.id = a.actor_employee_id"
            + " LEFT JOIN branches b ON b.id = a.branch_id";

    private final EntityManager entityManager;

    /** One audit row; the snapshots are null in search results, which never carry them. */
    public record Row(long id, Instant occurredAt, String action, String result, String entityType, Long entityId,
            String reason, String requestId, Long actorId, String actorName, Long branchId, String branchName,
            boolean hasChanges, String beforeJson, String afterJson) {}

    public record Page(List<Row> rows, long total) {}

    /** Matching rows, latest first (occurred_at, then id, descending). */
    public Page search(AuditSearchCriteria criteria) {
        var where = new StringBuilder(" WHERE a.occurred_at >= ? AND a.occurred_at < ?");
        var binds = new ArrayList<Object>(List.of(criteria.start(), criteria.end()));
        equal(where, binds, "a.entity_type", criteria.entityType());
        equal(where, binds, "a.entity_id", criteria.entityId());
        equal(where, binds, "a.action", criteria.action());
        equal(where, binds, "a.actor_employee_id", criteria.actorEmployeeId());
        equal(where, binds, "a.branch_id", criteria.branchId());
        equal(where, binds, "a.result", criteria.result());
        equal(where, binds, "a.request_id", criteria.requestId());
        long total = ((Number) bind(entityManager.createNativeQuery("SELECT COUNT(*) FROM audit_events a" + where), binds)
                .getSingleResult()).longValue();
        if (total == 0 || criteria.offset() >= total) return new Page(List.of(), total);
        var query = bind(entityManager.createNativeQuery("SELECT " + COLUMNS + FROM + where
                + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?"), binds);
        query.setParameter(binds.size() + 1, criteria.pageSize());
        query.setParameter(binds.size() + 2, criteria.offset());
        var rows = new ArrayList<Row>();
        for (Object result : query.getResultList()) rows.add(row((Object[]) result, null, null));
        return new Page(rows, total);
    }

    /** One row with its stored snapshots. */
    public Optional<Row> find(long id) {
        var query = entityManager.createNativeQuery("SELECT " + COLUMNS + ", a.before_json, a.after_json" + FROM + " WHERE a.id = ?");
        query.setParameter(1, id);
        var results = query.getResultList();
        if (results.isEmpty()) return Optional.empty();
        var columns = (Object[]) results.getFirst();
        return Optional.of(row(columns, text(columns[13]), text(columns[14])));
    }

    private static void equal(StringBuilder where, List<Object> binds, String column, Object value) {
        if (value == null) return;
        where.append(" AND ").append(column).append(" = ?");
        binds.add(value);
    }

    private static Query bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
        return query;
    }

    private static Row row(Object[] c, String before, String after) {
        return new Row(((Number) c[0]).longValue(), Instant.parse(c[1].toString()), (String) c[2], text(c[3]), (String) c[4],
                nullable(c[5]), (String) c[6], (String) c[7], nullable(c[8]), (String) c[9], nullable(c[10]), (String) c[11],
                truthy(c[12]), before, after);
    }

    private static Long nullable(Object value) { return value == null ? null : ((Number) value).longValue(); }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) return flag;
        return value != null && ((Number) value).intValue() != 0;
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
}
