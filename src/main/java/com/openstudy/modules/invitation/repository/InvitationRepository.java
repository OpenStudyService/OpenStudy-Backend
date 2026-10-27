package com.openstudy.modules.invitation.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InvitationStatus;

/**
 * Invitation rows. Every transition below mirrors one former UPDATE statement: each advances the version exactly
 * once whatever the current column values are. Mail transitions clear updated_by (a system change); management
 * transitions record the actor. DB-side timestamps keep the database's UTC time.
 */
public interface InvitationRepository extends JpaRepository<EmployeeInvitation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmployeeInvitation> findForUpdateById(long id);

    @Query("SELECT i.id FROM EmployeeInvitation i WHERE i.branchId IN :branches ORDER BY i.id")
    List<Long> findIdsByBranchIdIn(@Param("branches") Collection<Long> branches);

    /** Active invitations of one invitee in one branch, locked in id order. email compares case-insensitively. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<EmployeeInvitation> findForUpdateByBranchIdAndInviteeEmailAndStatusInOrderByIdAsc(long branchId, String email,
            Collection<InvitationStatus> statuses);

    @Query("SELECT i.id FROM EmployeeInvitation i WHERE i.tokenHash = :hash")
    List<Long> findIdsByTokenHash(@Param("hash") byte[] tokenHash);

    /** Grant ids read by query, not through the lazy collection: some callers read outside a transaction. */
    @Query("SELECT p FROM EmployeeInvitation i JOIN i.permissionIds p WHERE i.id = :id")
    List<Long> permissionIds(@Param("id") long invitationId);

    /** JPA-01 native exception: the grant rows keep their former FOR SHARE lock under the invitation's FOR UPDATE. */
    @Query(value = "SELECT permission_id FROM invitation_permission_grants WHERE invitation_id=:id FOR SHARE", nativeQuery = true)
    List<Number> lockedPermissionIds(@Param("id") long invitationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.EXPIRED, "
            + "i.tokenHash = NULL, i.updatedBy = :actor, i.version = i.version + 1 WHERE i.id = :id")
    int expireBy(@Param("id") long id, @Param("actor") long actor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.PENDING, "
            + "i.tokenHash = NULL, i.revision = i.revision + 1, i.expiresAt = :expires, i.updatedBy = :actor, "
            + "i.version = i.version + 1 WHERE i.id = :id")
    int reissue(@Param("id") long id, @Param("actor") long actor, @Param("expires") Instant expires);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.CANCELLED, "
            + "i.tokenHash = NULL, i.cancelledBy = :actor, i.cancelledAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), "
            + "i.updatedBy = :actor, i.version = i.version + 1 WHERE i.id = :id")
    int cancel(@Param("id") long id, @Param("actor") long actor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.REJECTED, "
            + "i.tokenHash = NULL, i.rejectedBy = :actor, i.rejectedAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), "
            + "i.rejectionReason = :reason, i.updatedBy = :actor, i.version = i.version + 1 WHERE i.id = :id")
    int reject(@Param("id") long id, @Param("actor") long actor, @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.ACCEPTED, "
            + "i.tokenHash = NULL, i.acceptedEmployeeId = :employee, i.acceptedAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), "
            + "i.updatedBy = :employee, i.version = i.version + 1 WHERE i.id = :id "
            + "AND i.status = com.openstudy.modules.invitation.entity.InvitationStatus.PENDING")
    int accept(@Param("id") long id, @Param("employee") long employee);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.APPROVED, "
            + "i.approvedBy = :actor, i.approvedAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), i.updatedBy = :actor, "
            + "i.version = i.version + 1 WHERE i.id = :id "
            + "AND i.status = com.openstudy.modules.invitation.entity.InvitationStatus.ACCEPTED")
    int approve(@Param("id") long id, @Param("actor") long actor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.tokenHash = :hash, i.updatedBy = NULL, i.version = i.version + 1 WHERE i.id = :id")
    int attachToken(@Param("id") long id, @Param("hash") byte[] hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.updatedBy = NULL, i.version = i.version + 1 WHERE i.id = :id")
    int markDelivered(@Param("id") long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.tokenHash = NULL, i.revision = i.revision + 1, i.updatedBy = NULL, "
            + "i.version = i.version + 1 WHERE i.id = :id")
    int advanceRevision(@Param("id") long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.tokenHash = NULL, i.updatedBy = NULL, i.version = i.version + 1 WHERE i.id = :id")
    int dropToken(@Param("id") long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE EmployeeInvitation i SET i.status = com.openstudy.modules.invitation.entity.InvitationStatus.EXPIRED, "
            + "i.tokenHash = NULL, i.updatedBy = NULL, i.version = i.version + 1 WHERE i.id = :id")
    int expire(@Param("id") long id);
}
