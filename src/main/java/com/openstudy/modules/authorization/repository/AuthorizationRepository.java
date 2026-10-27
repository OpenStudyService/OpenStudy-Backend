package com.openstudy.modules.authorization.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.AccessDeniedException;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.entity.BranchMembership;

/**
 * Own read model over the authorization aggregate, independent of employee/branch repositories.
 * <p>JPA-01 native exception: every statement here is part of the command lock order (guard, sorted employees,
 * sorted branches, memberships) or of the locked permission read, so the SQL is kept exactly as written.
 * Row values arrive as JDBC objects; BIGINT UNSIGNED ids are read through {@link Number}.</p>
 */
public interface AuthorizationRepository extends Repository<BranchMembership, Long> {

    default AuthorizationState current(long employeeId) {
        return state(employeeId, account(employeeId), branches(), memberships(employeeId), false);
    }

    default AuthorizationState currentLocked(long employeeId) {
        return state(employeeId, accountLocked(employeeId), branchesLocked(), membershipsLocked(employeeId), true);
    }

    private AuthorizationState state(long employeeId, List<Object[]> account, List<Object[]> branchRows,
            List<Object[]> membershipRows, boolean locking) {
        if (account.isEmpty()) throw new AccessDeniedException("접근 권한이 없습니다.");
        Map<Long, String> branches = new HashMap<>();
        for (Object[] row : branchRows) branches.put(id(row[0]), (String) row[1]);
        Map<Long, AuthorizationState.Membership> memberships = new HashMap<>();
        for (Object[] row : membershipRows) {
            long membership = id(row[0]);
            Set<String> grants = new HashSet<>(locking ? grantsLocked(membership) : grants(membership));
            memberships.put(id(row[1]), new AuthorizationState.Membership((String) row[2], (String) row[3], grants));
        }
        return new AuthorizationState(employeeId, "OWNER".equals(account.getFirst()[0]), (String) account.getFirst()[1],
                memberships, branches);
    }

    private static long id(Object value) { return ((Number) value).longValue(); }

    @Query(value = "SELECT global_role,status FROM employees WHERE id=:id", nativeQuery = true)
    List<Object[]> account(@Param("id") long employeeId);

    @Query(value = "SELECT global_role,status FROM employees WHERE id=:id FOR SHARE", nativeQuery = true)
    List<Object[]> accountLocked(@Param("id") long employeeId);

    @Query(value = "SELECT id,status FROM branches ORDER BY id", nativeQuery = true)
    List<Object[]> branches();

    @Query(value = "SELECT id,status FROM branches ORDER BY id FOR SHARE", nativeQuery = true)
    List<Object[]> branchesLocked();

    @Query(value = "SELECT id,branch_id,role,status FROM branch_memberships WHERE employee_id=:id ORDER BY branch_id", nativeQuery = true)
    List<Object[]> memberships(@Param("id") long employeeId);

    @Query(value = "SELECT id,branch_id,role,status FROM branch_memberships WHERE employee_id=:id ORDER BY branch_id FOR SHARE",
            nativeQuery = true)
    List<Object[]> membershipsLocked(@Param("id") long employeeId);

    @Query(value = "SELECT p.code FROM membership_permission_grants g JOIN permission_definitions p ON p.id=g.permission_id "
            + "WHERE g.membership_id=:id", nativeQuery = true)
    List<String> grants(@Param("id") long membershipId);

    @Query(value = "SELECT p.code FROM membership_permission_grants g JOIN permission_definitions p ON p.id=g.permission_id "
            + "WHERE g.membership_id=:id FOR SHARE", nativeQuery = true)
    List<String> grantsLocked(@Param("id") long membershipId);

    default void lockGuard() {
        if (guard().isEmpty()) throw new IllegalStateException("administration_guard row is missing");
    }

    @Query(value = "SELECT id FROM administration_guard WHERE id=1 FOR UPDATE", nativeQuery = true)
    List<Object> guard();

    /**
     * The singleton guard already serializes foundation writes. Lock administrator candidates too,
     * before branches, so last-administrator checks never acquire employee locks in reverse order.
     */
    @Query(value = "SELECT id FROM employees ORDER BY id FOR UPDATE", nativeQuery = true)
    List<Object> lockEmployees();

    /**
     * Foundation has few branches. Lock the branch set in canonical order, including read-model rows,
     * so a pre-existing REPEATABLE_READ snapshot cannot be used for an operating-status decision.
     */
    @Query(value = "SELECT id FROM branches ORDER BY id FOR UPDATE", nativeQuery = true)
    List<Object> lockBranches();

    default void lockMemberships(Set<Long> employeeIds, Set<Long> branchIds) {
        employeeIds.stream().sorted().forEach(employee -> branchIds.stream().sorted().forEach(branch -> lockMembership(employee, branch)));
    }

    @Query(value = "SELECT id FROM branch_memberships WHERE employee_id=:employee AND branch_id=:branch FOR UPDATE", nativeQuery = true)
    List<Object> lockMembership(@Param("employee") long employeeId, @Param("branch") long branchId);

    default long activeOwners() { return activeOwnerIds().size(); }

    @Query(value = "SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE' FOR SHARE", nativeQuery = true)
    List<Object> activeOwnerIds();

    default long activeAdministrators(long branch) { return activeAdministratorIds(branch).size(); }

    @Query(value = "SELECT DISTINCT e.id FROM employees e LEFT JOIN branch_memberships m "
            + "ON m.employee_id=e.id AND m.branch_id=:branch AND m.status='ACTIVE' AND m.role='MANAGER' "
            + "WHERE e.status='ACTIVE' AND (e.global_role='OWNER' OR m.id IS NOT NULL) FOR SHARE", nativeQuery = true)
    List<Object> activeAdministratorIds(@Param("branch") long branchId);
}
