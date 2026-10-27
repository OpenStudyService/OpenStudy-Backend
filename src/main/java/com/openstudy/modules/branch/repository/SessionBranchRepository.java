package com.openstudy.modules.branch.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.branch.entity.Branch;

/** Branch reads for the session/auth contract; callers are not authorized branch actors. */
public interface SessionBranchRepository extends Repository<Branch, Long> {
    /**
     * JPA-01 native exception: membership rows belong to the authorization module (JPA-03), so the branch module
     * keeps this one EXISTS check in SQL instead of mapping branch_memberships here.
     */
    @Query(value = "SELECT b.* FROM branches b WHERE (:owner OR EXISTS "
            + "(SELECT 1 FROM branch_memberships m WHERE m.branch_id = b.id AND m.employee_id = :employeeId "
            + "AND m.status = 'ACTIVE')) ORDER BY b.id", nativeQuery = true)
    List<Branch> findAccessible(@Param("employeeId") long employeeId, @Param("owner") boolean owner);

    List<Branch> findByIdIn(Collection<Long> ids);
}
