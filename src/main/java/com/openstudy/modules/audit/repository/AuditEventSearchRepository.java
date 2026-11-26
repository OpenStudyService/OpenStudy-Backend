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

    private static final String COLUMNS = "a.id, "
            + "DATE_FORMAT(a.occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ'), "
            + "a.action, a.result, a.entity_type, a.entity_id, a.reason, a.request_id, "
            + "a.actor_employee_id, e.name, a.branch_id, b.name, "
            + "(a.before_json IS NOT NULL OR a.after_json IS NOT NULL)";
    private static final String FROM = " FROM audit_events a LEFT JOIN employees e ON e.id = a.actor_employee_id"
            + " LEFT JOIN branches b ON b.id = a.branch_id";
    private static final int BEFORE_JSON_COLUMN = 13;
    private static final int AFTER_JSON_COLUMN = 14;

    private final EntityManager entityManager;

    /** One audit row; the snapshots are null in search results, which never carry them. */
    public record Row(
            long id,
            Instant occurredAt,
            String action,
            String result,
            String entityType,
            Long entityId,
            String reason,
            String requestId,
            Long actorId,
            String actorName,
            Long branchId,
            String branchName,
            boolean hasChanges,
            String beforeJson,
            String afterJson
    ) {
    }

    public record Page(List<Row> rows, long total) {
    }

    /** Matching rows, latest first (occurred_at, then id, descending). */
    public Page search(AuditSearchCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE a.occurred_at >= ? AND a.occurred_at < ?");
        List<Object> binds = new ArrayList<>(List.of(criteria.start(), criteria.end()));
        appendEqual(where, binds, "a.entity_type", criteria.entityType());
        appendEqual(where, binds, "a.entity_id", criteria.entityId());
        appendEqual(where, binds, "a.action", criteria.action());
        appendEqual(where, binds, "a.actor_employee_id", criteria.actorEmployeeId());
        appendEqual(where, binds, "a.branch_id", criteria.branchId());
        appendEqual(where, binds, "a.result", criteria.result());
        appendEqual(where, binds, "a.request_id", criteria.requestId());

        Query count = bindAll(entityManager.createNativeQuery("SELECT COUNT(*) FROM audit_events a" + where), binds);
        long total = ((Number) count.getSingleResult()).longValue();

        if (total == 0 || criteria.offset() >= total) {
            return new Page(List.of(), total);
        }

        String sql = "SELECT " + COLUMNS + FROM + where + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?";
        Query query = bindAll(entityManager.createNativeQuery(sql), binds);
        query.setParameter(binds.size() + 1, criteria.pageSize());
        query.setParameter(binds.size() + 2, criteria.offset());

        List<Row> rows = new ArrayList<>();

        for (Object result : query.getResultList()) {
            rows.add(toRow((Object[]) result, null, null));
        }

        return new Page(rows, total);
    }

    /** One row with its stored snapshots. */
    public Optional<Row> find(long id) {
        String sql = "SELECT " + COLUMNS + ", a.before_json, a.after_json" + FROM + " WHERE a.id = ?";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter(1, id);
        List<?> results = query.getResultList();

        if (results.isEmpty()) {
            return Optional.empty();
        }

        Object[] columns = (Object[]) results.getFirst();
        String beforeJson = toText(columns[BEFORE_JSON_COLUMN]);
        String afterJson = toText(columns[AFTER_JSON_COLUMN]);

        return Optional.of(toRow(columns, beforeJson, afterJson));
    }

    private static void appendEqual(StringBuilder where, List<Object> binds, String column, Object value) {
        if (value == null) {
            return;
        }

        where.append(" AND ").append(column).append(" = ?");
        binds.add(value);
    }

    private static Query bindAll(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) {
            query.setParameter(index + 1, binds.get(index));
        }

        return query;
    }

    private static Row toRow(Object[] columns, String beforeJson, String afterJson) {
        return new Row(
                ((Number) columns[0]).longValue(),
                Instant.parse(columns[1].toString()),
                (String) columns[2],
                toText(columns[3]),
                (String) columns[4],
                toLong(columns[5]),
                (String) columns[6],
                (String) columns[7],
                toLong(columns[8]),
                (String) columns[9],
                toLong(columns[10]),
                (String) columns[11],
                toBoolean(columns[12]),
                beforeJson,
                afterJson
        );
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }

        return value != null && ((Number) value).intValue() != 0;
    }

    private static String toText(Object value) {
        return value == null ? null : value.toString();
    }
}
