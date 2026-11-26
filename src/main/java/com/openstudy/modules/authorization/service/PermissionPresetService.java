package com.openstudy.modules.authorization.service;

import java.util.*;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.*;
import com.openstudy.modules.authorization.entity.BranchRole;
import com.openstudy.modules.authorization.repository.PermissionDefinitionRepository;
import com.openstudy.modules.authorization.repository.PermissionPresetRepository;

@Service
@RequiredArgsConstructor
public class PermissionPresetService {

    /** Codes a preset, invitation or membership may grant. */
    private static final Set<String> ASSIGNABLE_CODES = Set.of(
            "members:write",
            "operations:write",
            "payments:read",
            "payments:write",
            "sales:read",
            "facilities:write",
            "notices:write",
            "people:manage",
            "ai:read",
            "customers:cross-branch-read"
    );
    private static final String CREATE_OPERATION = "createPermissionPreset";
    private static final String ALL_SCOPE = "all";
    private static final String CREATE_SCOPE = ALL_SCOPE;
    private static final String AUDIT_ENTITY_TYPE = "permission-preset";
    private static final String RELOAD_PATH = "/api/permission-presets";
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MIN_KEY_CHAR = 33;
    private static final int MAX_KEY_CHAR = 126;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);

    private final PermissionPresetRepository permissionPresetRepository;
    private final PermissionDefinitionRepository permissionDefinitionRepository;
    private final EntityManager entityManager;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PermissionDefinition> definitions(long actor) {
        authorizationService.current(actor);

        return permissionDefinitionRepository.findAllByOrderByCodeAsc()
                .stream()
                .filter(definition -> ASSIGNABLE_CODES.contains(definition.getCode()))
                .map(definition -> new PermissionDefinition(
                        definition.getCode(),
                        definition.getDescription(),
                        definition.isAdminOnly()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionPreset> lookup(long actor, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> visible = authorizationService.scope(state, scope, BranchAction.READ);
        boolean managesPeople = visible.stream()
                .anyMatch(id -> authorizationService.permits(state, id, BranchAction.MANAGE_PEOPLE));

        if (!state.owner() && !managesPeople) {
            return List.of();
        }

        return findAll();
    }

    @Transactional(readOnly = true)
    public PageResponse<PermissionPreset> list(long actor, ListQuery query) {
        AuthorizationState state = authorizationService.current(actor);
        if (!state.owner() && managesNoBranch(state)) {
            throw AuthorizationService.forbidden();
        }

        String keyword = query.q().toLowerCase(Locale.ROOT);
        List<PermissionPreset> rows = findAll()
                .stream()
                .filter(preset -> preset.name().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();

        Comparator<PermissionPreset> order = "name".equals(query.sort())
                ? Comparator.comparing(PermissionPreset::name)
                : Comparator.comparing(PermissionPreset::createdAt);
        Comparator<PermissionPreset> tieBreaker = Comparator.comparingLong(preset -> Long.parseLong(preset.id()));

        return PageResponse.fromAuthorized(rows, query, order, tieBreaker);
    }

    /** Caller must hold AuthorizedCommands' global guard for a consistent preset-copy operation. */
    public void validateCopy(String role, List<String> permissions, Long presetId) {
        if (presetId != null) {
            PermissionPreset preset = findLocked(presetId).orElseThrow(PermissionPresetService::notFound);

            if (!preset.role().equals(role)) {
                throw ApiException.validation("presetId", "역할에 맞는 프리셋을 선택해 주세요.");
            }
        }

        validateGrants(role, permissions);
    }

    public PermissionPreset create(long actor, Map<String, Object> body, String key, String requestId) {
        return authorizedCommands.ownerCommand(actor, () -> {
            StrictInput input = new StrictInput(body).only("name", "role", "permissions");
            String name = input.text("name", MAX_NAME_LENGTH);
            String role = input.choice("role", "admin", "staff");
            List<String> grants = input.permissions();
            validateGrants(role, grants);
            requireValidKey(key);

            // Normalize the payload before hashing so JSON object key ordering is immaterial.
            List<Object> normalized = List.of(name, role, grants);
            Optional<PermissionPreset> replayed = replay(actor, key, normalized);

            if (replayed.isPresent()) {
                return replayed.get();
            }

            // ownerCommand serializes every preset write under the guard lock, so this check cannot race;
            // it replaces catching the JDBC DuplicateKeyException on uk_permission_presets_1 (name, role).
            if (permissionPresetRepository.existsByNameAndRole(name, toBranchRole(role))) {
                throw duplicate();
            }

            com.openstudy.modules.authorization.entity.PermissionPreset preset =
                    new com.openstudy.modules.authorization.entity.PermissionPreset(
                            name,
                            toBranchRole(role),
                            findDefinitions(grants),
                            actor
                    );
            long id = permissionPresetRepository.saveAndFlush(preset).getId();

            PermissionPreset result = findLocked(id).orElseThrow();
            recordChange(actor, id, "CREATE", null, result, requestId);
            remember(actor, key, normalized, result);
            return result;
        });
    }

    public PermissionPreset update(long actor, long id, Map<String, Object> body, String requestId) {
        return authorizedCommands.ownerCommand(actor, () -> {
            PermissionPreset before = findLocked(id).orElseThrow(PermissionPresetService::notFound);

            if (before.system()) {
                throw AuthorizationService.forbidden();
            }

            StrictInput input = new StrictInput(body).only("version", "name", "permissions");
            long dbVersionBefore = before.version() - 1;
            ApiVersion.requireCurrent(input.version("version"), dbVersionBefore, RELOAD_PATH);

            List<String> grants = input.permissions();
            validateGrants(before.role(), grants);
            String name = input.text("name", MAX_NAME_LENGTH);

            com.openstudy.modules.authorization.entity.PermissionPreset preset =
                    permissionPresetRepository.findForUpdateById(id).orElseThrow();

            if (permissionPresetRepository.existsByNameAndRoleAndIdNot(name, preset.getRole(), id)) {
                throw duplicate();
            }

            preset.change(name, findDefinitions(grants), actor);
            permissionPresetRepository.flush();

            // The former UPDATE always ran version=version+1, even for an identical name and permission set.
            if (preset.getVersion() == dbVersionBefore) {
                permissionPresetRepository.incrementVersion(id);
            }

            entityManager.refresh(preset);

            PermissionPreset result = findLocked(id).orElseThrow();
            recordChange(actor, id, "UPDATE", before, result, requestId);
            return result;
        });
    }

    private boolean managesNoBranch(AuthorizationState state) {
        return authorizationService.scope(state, ALL_SCOPE, BranchAction.MANAGE_PEOPLE).isEmpty();
    }

    private List<PermissionPreset> findAll() {
        return permissionPresetRepository.findAllWithPermissionsByOrderByIdAsc()
                .stream()
                .map(preset -> toView(preset, preset.getPermissionCodes()))
                .toList();
    }

    /** The former locked read: the preset row FOR UPDATE, then its grant rows FOR SHARE. */
    private Optional<PermissionPreset> findLocked(long id) {
        return permissionPresetRepository.findForUpdateById(id)
                .map(preset -> toView(preset, permissionPresetRepository.findPermissionCodesForShare(id)));
    }

    private void validateGrants(String role, List<String> grants) {
        for (String code : grants) {
            if (!ASSIGNABLE_CODES.contains(code)) {
                throw ApiException.validation("permissions", "알 수 없는 권한입니다.");
            }

            Optional<com.openstudy.modules.authorization.entity.PermissionDefinition> definition =
                    permissionDefinitionRepository.findByCode(code);
            boolean adminOnlyForStaff = "staff".equals(role)
                    && definition.isPresent()
                    && definition.get().isAdminOnly();

            if (definition.isEmpty() || adminOnlyForStaff) {
                throw ApiException.validation("permissions", "해당 역할에 부여할 수 없는 권한입니다.");
            }
        }
    }

    private Set<com.openstudy.modules.authorization.entity.PermissionDefinition> findDefinitions(List<String> grants) {
        return new LinkedHashSet<>(permissionDefinitionRepository.findByCodeIn(grants));
    }

    private void recordChange(long actor, long id, String action, Object before, Object after, String requestId) {
        AuditEvent event = AuditEvent.change(
                actor,
                null,
                AUDIT_ENTITY_TYPE,
                id,
                action,
                objectMapper.writeValueAsString(before),
                objectMapper.writeValueAsString(after),
                requestId
        );

        auditTrail.record(event);
    }

    private Optional<PermissionPreset> replay(long actor, String key, Object input) {
        byte[] hash = hash(input);
        Optional<IdempotencyRecord> stored = idempotencyStore.lock(actor, CREATE_OPERATION, CREATE_SCOPE, key);

        if (stored.isEmpty()) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(hash, stored.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(stored.get().getResponseJson(), PermissionPreset.class));
    }

    private void remember(long actor, String key, Object input, PermissionPreset result) {
        idempotencyStore.complete(
                actor,
                CREATE_OPERATION,
                CREATE_SCOPE,
                key,
                hash(input),
                CREATED_STATUS,
                objectMapper.writeValueAsString(result),
                clock.instant().plus(KEY_RETENTION)
        );
    }

    private byte[] hash(Object input) {
        byte[] json = objectMapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);

        try {
            return MessageDigest.getInstance("SHA-256").digest(json);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null
                && key.length() >= MIN_KEY_LENGTH
                && key.length() <= MAX_KEY_LENGTH
                && key.chars().allMatch(c -> c >= MIN_KEY_CHAR && c <= MAX_KEY_CHAR);

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자의 멱등 키가 필요합니다.");
        }
    }

    private static PermissionPreset toView(
            com.openstudy.modules.authorization.entity.PermissionPreset preset,
            List<String> codes
    ) {
        return new PermissionPreset(
                Long.toString(preset.getId()),
                ApiVersion.fromDatabase(preset.getVersion()),
                preset.getCreatedAt(),
                preset.getUpdatedAt(),
                preset.getName(),
                preset.getRole() == BranchRole.MANAGER ? "admin" : "staff",
                preset.isSystem(),
                codes
        );
    }

    private static BranchRole toBranchRole(String apiRole) {
        return "admin".equals(apiRole) ? BranchRole.MANAGER : BranchRole.STAFF;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicate() {
        return ApiException.conflict("DUPLICATE_PRESET", "같은 이름과 역할의 프리셋이 있습니다.");
    }
}
