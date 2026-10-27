package com.openstudy.modules.employee.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.entity.GlobalRole;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    /** email uses a case-insensitive collation (utf8mb4_0900_as_ci), so the database decides equality as before. */
    Optional<Employee> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findForUpdateById(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findForUpdateByEmail(String email);

    boolean existsByGlobalRole(GlobalRole globalRole);

    @Query("SELECT e.id FROM Employee e ORDER BY e.id")
    List<Long> findAllIds();

    /**
     * Active employees who can be chosen in the given branches. JPA-01 native exception: the membership EXISTS
     * check reads the authorization module's table (JPA-03), so it stays SQL instead of mapping it here.
     */
    @Query(value = "SELECT e.id,e.name FROM employees e WHERE e.status='ACTIVE' AND "
            + "(e.global_role='OWNER' OR EXISTS (SELECT 1 FROM branch_memberships m WHERE m.employee_id=e.id "
            + "AND m.status='ACTIVE' AND m.branch_id IN (:branches))) ORDER BY e.id", nativeQuery = true)
    List<Object[]> lookup(@Param("branches") Collection<Long> branches);

    /** Revoked memberships count here: peers need a scope-free invalidation to remove stale choices. */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM employees e WHERE e.id=:employee "
            + "AND (e.global_role='OWNER' OR EXISTS(SELECT 1 FROM branch_memberships m WHERE m.employee_id=e.id "
            + "AND m.branch_id IN (:branches))))", nativeQuery = true)
    Number relatedToBranches(@Param("employee") long employee, @Param("branches") Collection<Long> branches);

    /** Login time only: the account version is not a client-visible change here, so it does not advance. */
    @Modifying
    @Query("UPDATE Employee e SET e.lastLoginAt = cast(sql('UTC_TIMESTAMP(6)') as Instant) WHERE e.id = :id")
    int recordLogin(@Param("id") long id);

    /** Advances an otherwise unchanged account's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Employee e SET e.version = e.version + 1 WHERE e.id = :id")
    int incrementVersion(@Param("id") long id);

    /**
     * Ends every session of a disabled account in the same transaction as the status change. JPA-01 native exception:
     * Spring Session's own delete commits in a separate (REQUIRES_NEW) transaction and would survive a rollback.
     * The persisted Spring principal is the decimal employee ID.
     */
    @Modifying
    @Query(value = "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=:principal", nativeQuery = true)
    int deleteSessions(@Param("principal") String principal);
}
