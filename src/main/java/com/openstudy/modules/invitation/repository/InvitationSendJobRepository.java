package com.openstudy.modules.invitation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.invitation.entity.InvitationSendJob;
import com.openstudy.modules.invitation.entity.SendJobStatus;

/** Send jobs. Transitions are JPQL mirrors of the former UPDATE statements; the job row has no version. */
public interface InvitationSendJobRepository extends JpaRepository<InvitationSendJob, Long> {
    /** Non-locking bounded scan, (invitation id, job id) rows: never lock a job before its parent invitation. */
    @Query("SELECT j.invitationId, j.id FROM InvitationSendJob j WHERE "
            + "(j.status = com.openstudy.modules.invitation.entity.SendJobStatus.PENDING "
            + "AND (j.nextAttemptAt IS NULL OR j.nextAttemptAt <= :now)) OR "
            + "(j.status = com.openstudy.modules.invitation.entity.SendJobStatus.PROCESSING "
            + "AND (j.lockedUntil IS NULL OR j.lockedUntil <= :now)) ORDER BY j.id")
    List<Object[]> candidates(@Param("now") Instant now, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InvitationSendJob> findForUpdateById(long id);

    Optional<InvitationSendJob> findByInvitationIdAndRevision(long invitationId, int revision);

    /** SELECT ... FOR SHARE: the delivery state read under a locked invitation, as before. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<InvitationSendJob> findSharedByInvitationIdAndRevision(long invitationId, int revision);

    /** The former enqueue INSERT; next_attempt_at is the database's UTC time, so this stays one HQL insert. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("INSERT INTO InvitationSendJob (invitationId, revision, status, nextAttemptAt) VALUES (:invitation, :revision, "
            + "com.openstudy.modules.invitation.entity.SendJobStatus.PENDING, cast(sql('UTC_TIMESTAMP(6)') as Instant))")
    int enqueue(@Param("invitation") long invitationId, @Param("revision") int revision);

    /** Management closes open jobs without an error code; expiry by the worker uses {@link #skipOpen}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InvitationSendJob j SET j.status = com.openstudy.modules.invitation.entity.SendJobStatus.SKIPPED, "
            + "j.nextAttemptAt = NULL, j.lockedUntil = NULL, j.workerId = NULL "
            + "WHERE j.invitationId = :invitation AND j.status IN (com.openstudy.modules.invitation.entity.SendJobStatus.PENDING, "
            + "com.openstudy.modules.invitation.entity.SendJobStatus.PROCESSING, com.openstudy.modules.invitation.entity.SendJobStatus.FAILED)")
    int skipOpenJobs(@Param("invitation") long invitationId);

    /**
     * JPA-01 native exception: the administration guard is the first lock of every foundation write. The invitation
     * module may not call the authorization repository, so it takes the same statement here.
     */
    @Query(value = "SELECT id FROM administration_guard WHERE id=1 FOR UPDATE", nativeQuery = true)
    List<Object> lockGuard();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InvitationSendJob j SET j.status = com.openstudy.modules.invitation.entity.SendJobStatus.PROCESSING, "
            + "j.attempts = j.attempts + 1, j.workerId = :worker, j.lockedUntil = :leaseUntil, j.nextAttemptAt = NULL, "
            + "j.lastErrorCode = NULL WHERE j.id = :id")
    int claim(@Param("id") long id, @Param("worker") String workerId, @Param("leaseUntil") Instant leaseUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InvitationSendJob j SET j.status = com.openstudy.modules.invitation.entity.SendJobStatus.SENT, "
            + "j.sentAt = :now, j.lockedUntil = NULL, j.workerId = NULL, j.nextAttemptAt = NULL, j.lastErrorCode = NULL "
            + "WHERE j.id = :id")
    int sent(@Param("id") long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InvitationSendJob j SET j.status = :status, j.nextAttemptAt = NULL, j.lockedUntil = NULL, j.workerId = NULL, "
            + "j.lastErrorCode = :reason WHERE j.id = :id")
    int finish(@Param("id") long id, @Param("status") SendJobStatus status, @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InvitationSendJob j SET j.status = com.openstudy.modules.invitation.entity.SendJobStatus.SKIPPED, "
            + "j.nextAttemptAt = NULL, j.lockedUntil = NULL, j.workerId = NULL, j.lastErrorCode = 'INVITATION_EXPIRED' "
            + "WHERE j.invitationId = :invitation AND j.status IN (com.openstudy.modules.invitation.entity.SendJobStatus.PENDING, "
            + "com.openstudy.modules.invitation.entity.SendJobStatus.PROCESSING, com.openstudy.modules.invitation.entity.SendJobStatus.FAILED)")
    int skipOpen(@Param("invitation") long invitationId);
}
