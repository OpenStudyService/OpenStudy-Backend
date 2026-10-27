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

import com.openstudy.modules.authorization.entity.BranchRole;
import com.openstudy.modules.authorization.entity.PermissionPreset;

public interface PermissionPresetRepository extends JpaRepository<PermissionPreset, Long> {
    @EntityGraph(attributePaths = "permissions")
    List<PermissionPreset> findAllWithPermissionsByOrderByIdAsc();

    /** Locks only the preset row (SELECT ... FOR UPDATE); its grants are read by {@link #lockedPermissionCodes}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PermissionPreset> findForUpdateById(long id);

    /** JPA-01 native exception: the grant rows keep their former FOR SHARE lock alongside the preset's FOR UPDATE. */
    @Query(value = "SELECT p.code FROM permission_preset_grants g JOIN permission_definitions p ON p.id=g.permission_id "
            + "WHERE g.preset_id=:id ORDER BY p.code FOR SHARE", nativeQuery = true)
    List<String> lockedPermissionCodes(@Param("id") long id);

    boolean existsByNameAndRole(String name, BranchRole role);

    boolean existsByNameAndRoleAndIdNot(String name, BranchRole role, long id);

    /** Advances an otherwise unchanged preset's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PermissionPreset p SET p.version = p.version + 1 WHERE p.id = :id")
    int incrementVersion(@Param("id") long id);
}
