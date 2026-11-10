package com.openstudy.modules.account.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Credentials, sessions and reset links of employee accounts (#119). The password hash leaves this class only to be
 * compared by the encoder; tokens are handled as SHA-256 hashes only. Callers hold the command locks (guard, employees,
 * branches) before touching an account; the delivery worker locks a reset row alone.
 */
@Repository
@RequiredArgsConstructor
public class AccountCredentialRepository {
    private static final String UTC_TEXT = "'%Y-%m-%dT%H:%i:%s.%fZ'";

    private final EntityManager entityManager;

    /** The account fields the credential commands need. status and globalRole are the stored enum names. */
    public record Account(long id, String email, String name, String status, boolean owner, String passwordHash) {
        @Override public String toString() { return "Account[id=" + id + "]"; }
    }

    public record Membership(long branchId, String status) {}

    /** A reset row as the worker and the link endpoints see it. */
    public record ResetRow(long id, long employeeId, String status, byte[] tokenHash, Instant expiresAt, int attempts,
            String workerId, Instant leaseUntil) {
        @Override public String toString() { return "ResetRow[id=" + id + ",status=" + status + "]"; }
    }

    public Optional<Account> account(long id) {
        var rows = entityManager.createNativeQuery("SELECT id, email, name, status, global_role, password_hash FROM employees WHERE id = ?")
                .setParameter(1, id).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        var c = (Object[]) rows.getFirst();
        return Optional.of(new Account(((Number) c[0]).longValue(), (String) c[1], (String) c[2], (String) c[3],
                "OWNER".equals(c[4]), (String) c[5]));
    }

    public List<Membership> memberships(long employeeId) {
        var result = new ArrayList<Membership>();
        for (Object row : entityManager.createNativeQuery("SELECT branch_id, status FROM branch_memberships WHERE employee_id = ? ORDER BY branch_id")
                .setParameter(1, employeeId).getResultList()) {
            var c = (Object[]) row;
            result.add(new Membership(((Number) c[0]).longValue(), (String) c[1]));
        }
        return result;
    }

    /** Replaces the hash only; the account version is not a credential and stays (account-collab-api.md). */
    public void updatePassword(long id, String passwordHash) {
        entityManager.createNativeQuery("UPDATE employees SET password_hash = ? WHERE id = ?")
                .setParameter(1, passwordHash).setParameter(2, id).executeUpdate();
    }

    /** Every Spring Session row of the employee except the one kept (null keeps none). */
    public int deleteSessions(long employeeId, String keepSessionId) {
        var query = entityManager.createNativeQuery(keepSessionId == null
                ? "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?"
                : "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ? AND SESSION_ID <> ?");
        query.setParameter(1, Long.toString(employeeId));
        if (keepSessionId != null) query.setParameter(2, keepSessionId);
        return query.executeUpdate();
    }

    // ---- reset links -------------------------------------------------------------------------------------------------

    /** Earlier links of the employee stop working: a new request replaces them (M14-D05). */
    public int revokeLive(long employeeId) {
        return entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = 'REVOKED', token_hash = NULL, "
                        + "expires_at = NULL, worker_id = NULL, lease_until = NULL, failure_reason = 'SUPERSEDED' "
                        + "WHERE employee_id = ? AND status IN ('PENDING', 'SENDING', 'SENT')")
                .setParameter(1, employeeId).executeUpdate();
    }

    public void insertPending(long employeeId, long requestedBy, Instant now) {
        entityManager.createNativeQuery("INSERT INTO password_reset_tokens (employee_id, requested_by, status, next_attempt_at, "
                        + "created_at) VALUES (?, ?, 'PENDING', ?, ?)")
                .setParameter(1, employeeId).setParameter(2, requestedBy).setParameter(3, now).setParameter(4, now)
                .executeUpdate();
    }

    /** Rows the worker may take: pending and due, or sending with a lost lease. Non-locking. */
    public List<Long> candidates(Instant now, int limit) {
        var ids = new ArrayList<Long>();
        for (Object id : entityManager.createNativeQuery("SELECT id FROM password_reset_tokens WHERE (status = 'PENDING' AND "
                        + "next_attempt_at <= ?) OR (status = 'SENDING' AND lease_until <= ?) ORDER BY id LIMIT ?")
                .setParameter(1, now).setParameter(2, now).setParameter(3, limit).getResultList()) {
            ids.add(((Number) id).longValue());
        }
        return ids;
    }

    public Optional<ResetRow> lock(long id) {
        return one(entityManager.createNativeQuery(select() + " WHERE id = ? FOR UPDATE").setParameter(1, id).getResultList());
    }

    public Optional<ResetRow> lockByHash(byte[] tokenHash) {
        return one(entityManager.createNativeQuery(select() + " WHERE token_hash = ? FOR UPDATE").setParameter(1, tokenHash)
                .getResultList());
    }

    public Optional<ResetRow> findByHash(byte[] tokenHash) {
        return one(entityManager.createNativeQuery(select() + " WHERE token_hash = ?").setParameter(1, tokenHash).getResultList());
    }

    public void claim(long id, byte[] tokenHash, Instant expiresAt, String workerId, Instant leaseUntil) {
        entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = 'SENDING', token_hash = ?, expires_at = ?, "
                        + "worker_id = ?, lease_until = ?, attempts = attempts + 1, failure_reason = NULL WHERE id = ?")
                .setParameter(1, tokenHash).setParameter(2, expiresAt).setParameter(3, workerId).setParameter(4, leaseUntil)
                .setParameter(5, id).executeUpdate();
    }

    public void sent(long id) {
        entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = 'SENT', worker_id = NULL, lease_until = NULL "
                + "WHERE id = ?").setParameter(1, id).executeUpdate();
    }

    /** Back to pending without a token: the next claim makes a new one, so an uncertain link stops working. */
    public void retry(long id, Instant due, String reason) {
        entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = 'PENDING', token_hash = NULL, expires_at = NULL, "
                        + "worker_id = NULL, lease_until = NULL, next_attempt_at = ?, failure_reason = ? WHERE id = ?")
                .setParameter(1, due).setParameter(2, reason).setParameter(3, id).executeUpdate();
    }

    /** A terminal state without a usable link: FAILED (delivery gave up) or REVOKED (the account is no longer active). */
    public void close(long id, String status, String reason) {
        if (!"FAILED".equals(status) && !"REVOKED".equals(status)) throw new IllegalArgumentException("Not a closing status");
        entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = ?, token_hash = NULL, expires_at = NULL, "
                        + "worker_id = NULL, lease_until = NULL, failure_reason = ? WHERE id = ?")
                .setParameter(1, status).setParameter(2, reason).setParameter(3, id).executeUpdate();
    }

    public void used(long id, Instant now) {
        entityManager.createNativeQuery("UPDATE password_reset_tokens SET status = 'USED', token_hash = NULL, expires_at = NULL, "
                        + "worker_id = NULL, lease_until = NULL, used_at = ? WHERE id = ?")
                .setParameter(1, now).setParameter(2, id).executeUpdate();
    }

    private static String select() {
        return "SELECT id, employee_id, status, token_hash, DATE_FORMAT(expires_at, " + UTC_TEXT + "), attempts, worker_id, "
                + "DATE_FORMAT(lease_until, " + UTC_TEXT + ") FROM password_reset_tokens";
    }

    private static Optional<ResetRow> one(List<?> rows) {
        if (rows.isEmpty()) return Optional.empty();
        var c = (Object[]) rows.getFirst();
        return Optional.of(new ResetRow(((Number) c[0]).longValue(), ((Number) c[1]).longValue(), (String) c[2], (byte[]) c[3],
                instant(c[4]), ((Number) c[5]).intValue(), (String) c[6], instant(c[7])));
    }

    private static Instant instant(Object value) { return value == null ? null : Instant.parse(value.toString()); }
}
