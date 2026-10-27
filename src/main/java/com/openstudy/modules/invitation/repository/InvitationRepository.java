package com.openstudy.modules.invitation.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.invitation.entity.EmployeeInvitation;

/**
 * Invitation rows. The mail transitions below mirror the former UPDATE statements one to one: each advances the
 * version exactly once and clears updated_by (a system change), whatever the current column values are.
 */
public interface InvitationRepository extends JpaRepository<EmployeeInvitation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmployeeInvitation> findForUpdateById(long id);

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
