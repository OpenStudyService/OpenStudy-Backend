package com.openstudy.modules.branch.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.branch.entity.Branch;

/** The single repository of the branch aggregate (branch + weekly hours). */
public interface BranchRepository extends JpaRepository<Branch, Long> {
    @EntityGraph(attributePaths = "weeklyHours")
    Optional<Branch> findWithWeeklyHoursById(long id);

    @EntityGraph(attributePaths = "weeklyHours")
    List<Branch> findAllWithWeeklyHoursByOrderByIdAsc();

    /**
     * JPA-01 native exception: membership rows belong to the authorization module (JPA-03), so the branch module
     * keeps this one EXISTS check in SQL instead of mapping branch_memberships here.
     */
    @Query(value = "SELECT b.* FROM branches b WHERE (:owner OR EXISTS "
            + "(SELECT 1 FROM branch_memberships m WHERE m.branch_id = b.id AND m.employee_id = :employeeId "
            + "AND m.status = 'ACTIVE')) ORDER BY b.id", nativeQuery = true)
    List<Branch> findAccessible(@Param("employeeId") long employeeId, @Param("owner") boolean owner);

    List<Branch> findByIdIn(Collection<Long> ids);

    /** Advances an unchanged branch's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Branch b SET b.version = b.version + 1 WHERE b.id = :id")
    int incrementVersion(@Param("id") long id);
}
