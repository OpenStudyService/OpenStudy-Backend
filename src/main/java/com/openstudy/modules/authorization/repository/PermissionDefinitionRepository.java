package com.openstudy.modules.authorization.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.authorization.entity.PermissionDefinition;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, Long> {
    List<PermissionDefinition> findAllByOrderByCodeAsc();

    Optional<PermissionDefinition> findByCode(String code);

    List<PermissionDefinition> findByCodeIn(Collection<String> codes);
}
