package com.openstudy.modules.authorization.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.authorization.entity.BranchMembership;
import com.openstudy.modules.authorization.entity.MembershipStatus;

public interface BranchMembershipRepository extends JpaRepository<BranchMembership, Long> {
    /** SELECT ... FOR UPDATE on the (employee_id, branch_id) unique row, gap-locking it when absent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BranchMembership> findForUpdateByEmployeeIdAndBranchId(long employeeId, long branchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BranchMembership> findForUpdateByEmployeeIdAndBranchIdAndStatus(long employeeId, long branchId, MembershipStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<BranchMembership> findForUpdateByEmployeeIdOrderByBranchIdAsc(long employeeId);

    @EntityGraph(attributePaths = "permissions")
    List<BranchMembership> findByEmployeeIdOrderByBranchIdAsc(long employeeId);

    @EntityGraph(attributePaths = "permissions")
    List<BranchMembership> findByEmployeeIdAndStatusOrderByBranchIdAsc(long employeeId, MembershipStatus status);

    /** JPA-01 native exception: grant rows keep their former FOR SHARE lock alongside the membership's FOR UPDATE. */
    @Query(value = "SELECT p.code FROM membership_permission_grants g JOIN permission_definitions p ON p.id=g.permission_id "
            + "WHERE g.membership_id=:id ORDER BY p.code FOR SHARE", nativeQuery = true)
    List<String> lockedPermissionCodes(@Param("id") long membershipId);

    /** The former activation UPDATE, kept as one statement so approved_at is the database's UTC time. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE BranchMembership m SET m.status = com.openstudy.modules.authorization.entity.MembershipStatus.ACTIVE, "
            + "m.approvedBy = :actor, m.approvedAt = cast(sql('UTC_TIMESTAMP(6)') as Instant), m.updatedBy = :actor, "
            + "m.version = m.version + 1 WHERE m.id = :id")
    int activate(@Param("id") long id, @Param("actor") long actor);

    /** Advances an otherwise unchanged membership's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE BranchMembership m SET m.version = m.version + 1 WHERE m.id = :id")
    int incrementVersion(@Param("id") long id);
}
