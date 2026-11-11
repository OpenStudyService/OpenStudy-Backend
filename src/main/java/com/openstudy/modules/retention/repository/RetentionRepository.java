package com.openstudy.modules.retention.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The retention rules, their run records and the cleanup statements (#100, ADR-M11-001 M11-D01·D05, ADR-M11-002). Every
 * statement binds its values; the tables belong to other modules, so they are reached by SQL here and never by their
 * entities. Large sets are handled in chunks so one run does not hold a long lock.
 */
@Repository
@RequiredArgsConstructor
public class RetentionRepository {
    static final int CHUNK = 500;
    /** What an anonymized customer is called; the row, member number and references stay (M11-D05). */
    public static final String ANONYMIZED_NAME = "파기된 고객";

    private final EntityManager entityManager;

    public record Rule(String code, boolean enabled, Integer periodDays, Integer legalMinimumDays) {}

    public Optional<Rule> rule(String code) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT code, enabled, period_days, legal_minimum_days FROM retention_rules WHERE code = ?")
                .setParameter(1, code).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new Rule((String) row[0], toBoolean(row[1]), toInteger(row[2]), toInteger(row[3])));
    }

    public void recordRun(String code, int periodDays, Instant cutoff, Instant started, Instant finished, int target,
            int processed, String errorCode) {
        entityManager.createNativeQuery("INSERT INTO retention_runs(rule_code, period_days, cutoff_at, started_at, finished_at, "
                        + "target_count, processed_count, result, error_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .setParameter(1, code).setParameter(2, periodDays).setParameter(3, cutoff).setParameter(4, started)
                .setParameter(5, finished).setParameter(6, target).setParameter(7, processed)
                .setParameter(8, errorCode == null ? "SUCCESS" : "FAILED").setParameter(9, errorCode).executeUpdate();
    }

    // ---- CUSTOMER_IDENTITY (M11R-D01) ---------------------------------------------------------------------------------

    /**
     * Customers whose last use (registration, check-in or check-out, approved payment, pass expiry, locker end, room end,
     * inquiry change, whichever is latest) is before the cutoff, and who have nothing still running: a usable pass, a
     * session, a locker, a room booking, an open inquiry or a pending payment.
     */
    private static final String INACTIVE_CUSTOMERS = " FROM customers c WHERE c.anonymized_at IS NULL"
            + " AND GREATEST(c.registered_at,"
            + " COALESCE((SELECT MAX(COALESCE(s.checked_out_at, s.checked_in_at)) FROM seat_sessions s WHERE s.customer_id = c.id), c.registered_at),"
            + " COALESCE((SELECT MAX(p.approved_at) FROM payments p WHERE p.customer_id = c.id), c.registered_at),"
            + " COALESCE((SELECT MAX(pa.expires_at) FROM passes pa WHERE pa.customer_id = c.id), c.registered_at),"
            + " COALESCE((SELECT MAX(COALESCE(l.returned_at, TIMESTAMP(l.end_date))) FROM locker_assignments l WHERE l.customer_id = c.id), c.registered_at),"
            + " COALESCE((SELECT MAX(r.end_at) FROM room_reservations r WHERE r.customer_id = c.id), c.registered_at),"
            + " COALESCE((SELECT MAX(i.updated_at) FROM inquiries i WHERE i.customer_id = c.id), c.registered_at)) < :cutoff"
            + " AND NOT EXISTS (SELECT 1 FROM passes pa WHERE pa.customer_id = c.id AND pa.status IN ('ACTIVE', 'NOT_STARTED')"
            + " AND (pa.expires_at IS NULL OR pa.expires_at > :now))"
            + " AND NOT EXISTS (SELECT 1 FROM seat_sessions s WHERE s.customer_id = c.id AND s.status = 'ACTIVE')"
            + " AND NOT EXISTS (SELECT 1 FROM locker_assignments l WHERE l.customer_id = c.id AND l.status IN ('ACTIVE', 'PAYMENT_PENDING'))"
            + " AND NOT EXISTS (SELECT 1 FROM room_reservations r WHERE r.customer_id = c.id AND r.status IN ('PAYMENT_PENDING', 'CONFIRMED', 'IN_USE'))"
            + " AND NOT EXISTS (SELECT 1 FROM inquiries i WHERE i.customer_id = c.id AND i.status IN ('RECEIVED', 'IN_PROGRESS'))"
            + " AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.customer_id = c.id AND p.status = 'PENDING')";

    public int countInactiveCustomers(Instant cutoff, Instant now) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*)" + INACTIVE_CUSTOMERS)
                .setParameter("cutoff", cutoff).setParameter("now", now).getSingleResult()).intValue();
    }

    /**
     * Replaces the identifying fields of up to one chunk of inactive customers and returns how many changed. The version
     * moves so a screen still holding the old values cannot write them back.
     */
    public int anonymizeInactiveCustomers(Instant cutoff, Instant now) {
        List<Long> ids = longs(entityManager.createNativeQuery("SELECT c.id" + INACTIVE_CUSTOMERS + " ORDER BY c.id LIMIT " + CHUNK)
                .setParameter("cutoff", cutoff).setParameter("now", now).getResultList());
        if (ids.isEmpty()) return 0;
        return entityManager.createNativeQuery("UPDATE customers SET name = :name, phone = NULL, email = NULL, memo = NULL, "
                        + "status_reason = NULL, anonymized_at = :now, version = version + 1 WHERE id IN (:ids) AND anonymized_at IS NULL")
                .setParameter("name", ANONYMIZED_NAME).setParameter("now", now).setParameter("ids", ids).executeUpdate();
    }

    // ---- AI_CONVERSATION (M11R-D06) -----------------------------------------------------------------------------------

    public int countIdleConversations(Instant cutoff) {
        return ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM ai_conversations WHERE updated_at < ?")
                .setParameter(1, cutoff).getSingleResult()).intValue();
    }

    /** Deletes up to one chunk of conversations whose last question is before the cutoff, with their questions. */
    public int deleteIdleConversations(Instant cutoff) {
        List<String> ids = strings(entityManager.createNativeQuery(
                        "SELECT id FROM ai_conversations WHERE updated_at < ? ORDER BY id LIMIT " + CHUNK)
                .setParameter(1, cutoff).getResultList());
        if (ids.isEmpty()) return 0;
        entityManager.createNativeQuery("DELETE FROM ai_executions WHERE conversation_id IN (:ids)").setParameter("ids", ids).executeUpdate();
        // ai_messages go with their conversation (ON DELETE CASCADE).
        return entityManager.createNativeQuery("DELETE FROM ai_conversations WHERE id IN (:ids)").setParameter("ids", ids).executeUpdate();
    }

    // ---- ACCOUNT_MAIL_HISTORY (M11R-D10) ------------------------------------------------------------------------------

    /** Reset links that were used, revoked or failed, or whose link expired, before the cutoff. */
    public int deleteEndedResetLinks(Instant cutoff) {
        return entityManager.createNativeQuery("DELETE FROM password_reset_tokens WHERE "
                        + "(status IN ('USED', 'REVOKED', 'FAILED') AND updated_at < ?) OR (status IN ('SENDING', 'SENT') AND expires_at < ?)")
                .setParameter(1, cutoff).setParameter(2, cutoff).executeUpdate();
    }

    /**
     * Invitations nobody joined through (rejected, cancelled, expired) that ended before the cutoff, with their grants and
     * send jobs. Accepted and approved invitations are the employee's joining record and stay.
     */
    public int deleteUnjoinedInvitations(Instant cutoff) {
        List<Long> ids = longs(entityManager.createNativeQuery("SELECT id FROM employee_invitations WHERE "
                        + "(status IN ('REJECTED', 'CANCELLED', 'EXPIRED') AND updated_at < ?) OR (status = 'PENDING' AND expires_at < ?) "
                        + "ORDER BY id LIMIT " + CHUNK)
                .setParameter(1, cutoff).setParameter(2, cutoff).getResultList());
        if (ids.isEmpty()) return 0;
        entityManager.createNativeQuery("DELETE FROM invitation_send_jobs WHERE invitation_id IN (:ids)").setParameter("ids", ids).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM invitation_permission_grants WHERE invitation_id IN (:ids)").setParameter("ids", ids).executeUpdate();
        return entityManager.createNativeQuery("DELETE FROM employee_invitations WHERE id IN (:ids)").setParameter("ids", ids).executeUpdate();
    }

    /** Finished send jobs of invitations that are no longer pending; a pending invitation keeps its jobs for retries. */
    public int deleteFinishedSendJobs(Instant cutoff) {
        return entityManager.createNativeQuery("DELETE j FROM invitation_send_jobs j JOIN employee_invitations i ON i.id = j.invitation_id "
                        + "WHERE j.status IN ('SENT', 'FAILED', 'SKIPPED') AND j.updated_at < ? AND i.status <> 'PENDING'")
                .setParameter(1, cutoff).executeUpdate();
    }

    // ---- IDEMPOTENCY_RECORD (M11R-D11) and AUDIT_EVENT (M11R-D07) -----------------------------------------------------

    public int deleteExpiredIdempotencyRecords(Instant cutoff) {
        return entityManager.createNativeQuery("DELETE FROM idempotency_records WHERE expires_at < ? LIMIT " + CHUNK)
                .setParameter(1, cutoff).executeUpdate();
    }

    public int deleteAuditEventsBefore(Instant cutoff) {
        return entityManager.createNativeQuery("DELETE FROM audit_events WHERE occurred_at < ? LIMIT " + CHUNK)
                .setParameter(1, cutoff).executeUpdate();
    }

    private static boolean toBoolean(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    private static Integer toInteger(Object value) { return value == null ? null : ((Number) value).intValue(); }

    private static List<Long> longs(List<?> rows) {
        var ids = new ArrayList<Long>();
        for (Object row : rows) ids.add(((Number) row).longValue());
        return ids;
    }

    private static List<String> strings(List<?> rows) {
        var ids = new ArrayList<String>();
        for (Object row : rows) ids.add((String) row);
        return ids;
    }
}
