package com.openstudy.modules.workforce.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.LeaveGrant;

public interface LeaveGrantRepository extends JpaRepository<LeaveGrant, Long> {
    /** SELECT ... FOR UPDATE, after the employee row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM LeaveGrant g WHERE g.id = :id")
    Optional<LeaveGrant> locked(@Param("id") long id);

    /**
     * The grants an approval may take from (M12-D06): the employee's, valid on every day of [first, last], with days left,
     * earliest expiry first, then id. FOR UPDATE in that order, after the employee row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM LeaveGrant g WHERE g.employeeId = :employee AND g.validFrom <= :first AND g.validThrough >= :last "
            + "AND g.remainingDays > 0 ORDER BY g.validThrough, g.id")
    List<LeaveGrant> usable(@Param("employee") long employee, @Param("first") LocalDate first, @Param("last") LocalDate last);

    /** The same grants without a write lock, for the advisory check of a new request: FOR SHARE after the employee row. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT g FROM LeaveGrant g WHERE g.employeeId = :employee AND g.validFrom <= :first AND g.validThrough >= :last "
            + "AND g.remainingDays > 0 ORDER BY g.validThrough, g.id")
    List<LeaveGrant> available(@Param("employee") long employee, @Param("first") LocalDate first, @Param("last") LocalDate last);

    /** Grants by id, FOR UPDATE in id order: the restore of an approval cancellation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM LeaveGrant g WHERE g.id IN :ids ORDER BY g.id")
    List<LeaveGrant> lockedAll(@Param("ids") Collection<Long> ids);

    /** Every grant of the employee, for the balance: earliest expiry first, then id. */
    List<LeaveGrant> findByEmployeeIdOrderByValidThroughAscIdAsc(long employeeId);

    /**
     * JPA-01 native exception: the branches where the employee holds an active membership with an active account, over
     * the employee and authorization tables. A MANAGER of one of them may read the employee's balance.
     */
    @Query(value = "SELECT m.branch_id FROM branch_memberships m JOIN employees e ON e.id = m.employee_id "
            + "WHERE m.employee_id = :employee AND m.status = 'ACTIVE' AND e.status = 'ACTIVE'", nativeQuery = true)
    List<Number> activeBranches(@Param("employee") long employee);
}
