package com.openstudy.modules.authorization.service;

import java.util.*;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.*;
import com.openstudy.modules.authorization.entity.BranchRole;
import com.openstudy.modules.authorization.repository.PermissionDefinitionRepository;
import com.openstudy.modules.authorization.repository.PermissionPresetRepository;

@Service
public class PermissionPresetService {
    private static final Set<String> CODES = Set.of("members:write", "operations:write", "payments:read",
            "payments:write", "sales:read", "facilities:write", "notices:write", "people:manage", "ai:read");
    private static final String CREATE = "createPermissionPreset";
    private final PermissionPresetRepository repository;
    private final PermissionDefinitionRepository definitions;
    private final EntityManager entityManager;
    private final AuthorizationService authorization;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;
    public PermissionPresetService(PermissionPresetRepository repository, PermissionDefinitionRepository definitions,
            EntityManager entityManager, AuthorizationService authorization, AuthorizedCommands commands, ObjectMapper json,
            AuditTrail audit, WorkspaceOutbox outbox, IdempotencyStore requests, Clock clock) {
        this.repository=repository; this.definitions=definitions; this.entityManager=entityManager; this.authorization=authorization;
        this.commands=commands; this.json=json; this.audit=audit; this.outbox=outbox; this.requests=requests; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public List<PermissionDefinition> definitions(long actor) {
        authorization.current(actor);
        return definitions.findAllByOrderByCodeAsc().stream().filter(p -> CODES.contains(p.getCode()))
                .map(p -> new PermissionDefinition(p.getCode(), p.getDescription(), p.isAdminOnly())).toList();
    }
    @Transactional(readOnly=true)
    public List<PermissionPreset> lookup(long actor, String scope) {
        var state=authorization.current(actor);
        var visible=authorization.scope(state,scope,BranchAction.READ);
        if (!state.owner() && visible.stream().noneMatch(id -> authorization.permits(state,id,BranchAction.MANAGE_PEOPLE))) return List.of();
        return all();
    }
    @Transactional(readOnly=true)
    public PageResponse<PermissionPreset> list(long actor, ListQuery query) {
        var state=authorization.current(actor);
        if (!state.owner() && authorization.scope(state,"all",BranchAction.MANAGE_PEOPLE).isEmpty()) throw AuthorizationService.forbidden();
        var rows=all().stream().filter(p->p.name().toLowerCase(Locale.ROOT).contains(query.q().toLowerCase(Locale.ROOT))).toList();
        return PageResponse.fromAuthorized(rows,query,"name".equals(query.sort()) ? Comparator.comparing(PermissionPreset::name) : Comparator.comparing(PermissionPreset::createdAt), Comparator.comparingLong(p->Long.parseLong(p.id())));
    }
    /** Caller must hold AuthorizedCommands' global guard for a consistent preset-copy operation. */
    public void validateCopy(String role, List<String> permissions, Long presetId) {
        if (presetId != null) {
            var preset=locked(presetId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!preset.role().equals(role)) throw ApiException.validation("presetId","역할에 맞는 프리셋을 선택해 주세요.");
        }
        validate(role, permissions);
    }
    public PermissionPreset create(long actor, Map<String,Object> body, String key, String requestId) {
        return commands.ownerCommand(actor,()-> {
            var input=new StrictInput(body).only("name","role","permissions");
            String name=input.text("name",100), role=input.choice("role","admin","staff"); var grants=input.permissions();
            validate(role,grants);
            if (key==null || key.length()<16 || key.length()>128 || key.chars().anyMatch(c->c<33 || c>126)) throw ApiException.validation("Idempotency-Key","16~128자의 멱등 키가 필요합니다.");
            // Normalize the payload before hashing so JSON object key ordering is immaterial.
            var normalized=List.of(name,role,grants);
            var replay=replay(actor,key,normalized); if(replay.isPresent()) return replay.get();
            // ownerCommand serializes every preset write under the guard lock, so this check cannot race;
            // it replaces catching the JDBC DuplicateKeyException on uk_permission_presets_1 (name, role).
            if (repository.existsByNameAndRole(name,role(role))) throw duplicate();
            var preset=repository.saveAndFlush(new com.openstudy.modules.authorization.entity.PermissionPreset(name,role(role),grantSet(grants),actor));
            long id=preset.getId(); var result=locked(id).orElseThrow();
            changed(actor,id,"CREATE",null,result,requestId); remember(actor,key,normalized,result); return result;
        });
    }
    public PermissionPreset update(long actor, long id, Map<String,Object> body, String requestId) {
        return commands.ownerCommand(actor,()-> {
            var before=locked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            if(before.system()) throw AuthorizationService.forbidden();
            var input=new StrictInput(body).only("version","name","permissions");
            ApiVersion.requireCurrent(input.version("version"),before.version()-1,"/api/permission-presets");
            var grants=input.permissions(); validate(before.role(),grants);
            String name=input.text("name",100);
            var preset=repository.findForUpdateById(id).orElseThrow();
            if (repository.existsByNameAndRoleAndIdNot(name,preset.getRole(),id)) throw duplicate();
            preset.change(name,grantSet(grants),actor);
            repository.flush();
            // The former UPDATE always ran version=version+1, even for an identical name and permission set.
            if (preset.getVersion()==before.version()-1) repository.incrementVersion(id);
            entityManager.refresh(preset);
            var result=locked(id).orElseThrow(); changed(actor,id,"UPDATE",before,result,requestId); return result;
        });
    }

    private List<PermissionPreset> all() {
        return repository.findAllWithPermissionsByOrderByIdAsc().stream().map(p -> view(p, p.getPermissionCodes())).toList();
    }
    /** The former locked read: the preset row FOR UPDATE, then its grant rows FOR SHARE. */
    private Optional<PermissionPreset> locked(long id) {
        return repository.findForUpdateById(id).map(p -> view(p, repository.lockedPermissionCodes(id)));
    }
    private static PermissionPreset view(com.openstudy.modules.authorization.entity.PermissionPreset p, List<String> codes) {
        return new PermissionPreset(Long.toString(p.getId()), ApiVersion.fromDatabase(p.getVersion()), p.getCreatedAt(),
                p.getUpdatedAt(), p.getName(), p.getRole() == BranchRole.MANAGER ? "admin" : "staff", p.isSystem(), codes);
    }
    private void validate(String role, List<String> grants) {
        for (String code : grants) {
            if (!CODES.contains(code)) throw ApiException.validation("permissions", "알 수 없는 권한입니다.");
            var definition=definitions.findByCode(code);
            if (definition.isEmpty() || ("staff".equals(role) && definition.get().isAdminOnly())) throw ApiException.validation("permissions", "해당 역할에 부여할 수 없는 권한입니다.");
        }
    }
    private Set<com.openstudy.modules.authorization.entity.PermissionDefinition> grantSet(List<String> grants) {
        return new LinkedHashSet<>(definitions.findByCodeIn(grants));
    }
    private static BranchRole role(String apiRole) { return "admin".equals(apiRole) ? BranchRole.MANAGER : BranchRole.STAFF; }
    private static ApiException duplicate() { return ApiException.conflict("DUPLICATE_PRESET","같은 이름과 역할의 프리셋이 있습니다."); }
    private void changed(long actor, long id, String action, Object before, Object after, String requestId) {
        audit.record(AuditEvent.change(actor, null, "permission-preset", id, action,
                json.writeValueAsString(before), json.writeValueAsString(after), requestId));
        outbox.invalidate(null, "permission-presets", id);
    }
    private Optional<PermissionPreset> replay(long actor, String key, Object input) {
        byte[] hash = hash(input);
        var stored = requests.lock(actor, CREATE, "all", key);
        if (stored.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(hash,stored.get().getRequestHash())) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        return Optional.of(json.readValue(stored.get().getResponseJson(),PermissionPreset.class));
    }
    private void remember(long actor, String key, Object input, PermissionPreset result) {
        requests.complete(actor, CREATE, "all", key, hash(input), 201, json.writeValueAsString(result),
                clock.instant().plus(Duration.ofHours(24)));
    }
    private byte[] hash(Object input) {
        try { return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
