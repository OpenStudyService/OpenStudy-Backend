package com.openstudy.modules.authorization.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.entity.PermissionDefinition;
import com.openstudy.modules.authorization.repository.PermissionDefinitionRepository;

/**
 * Permission code ↔ id translation for modules that store grants by permission id but may not reference the
 * authorization module's entities (JPA-03). The dictionary changes only through migrations and seeds.
 */
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
@RequiredArgsConstructor
public class PermissionCatalog {

    private final PermissionDefinitionRepository permissionDefinitionRepository;

    /** Ids of the known codes; unknown codes are skipped, as the former INSERT ... SELECT by code skipped them. */
    public List<Long> ids(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }

        return permissionDefinitionRepository.findByCodeIn(codes)
                .stream()
                .map(PermissionDefinition::getId)
                .toList();
    }

    /** Codes of the given ids, in ascending code order. */
    public List<String> codes(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, String> codesById = permissionDefinitionRepository.findAllById(ids)
                .stream()
                .collect(Collectors.toMap(PermissionDefinition::getId, PermissionDefinition::getCode));

        return ids.stream()
                .map(codesById::get)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }
}
