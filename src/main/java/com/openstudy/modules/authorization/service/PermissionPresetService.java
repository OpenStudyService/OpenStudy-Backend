package com.openstudy.modules.authorization.service;

import java.util.*;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.*;
import com.openstudy.modules.authorization.repository.PermissionPresetRepository;

@Service
public class PermissionPresetService {
    private final PermissionPresetRepository repository;
    private final AuthorizationService authorization;
    private final AuthorizedCommands commands;
    public PermissionPresetService(PermissionPresetRepository repository, AuthorizationService authorization, AuthorizedCommands commands) {
        this.repository=repository; this.authorization=authorization; this.commands=commands;
    }
    @Transactional(readOnly=true)
    public List<PermissionDefinition> definitions(long actor) {
        authorization.current(actor);
        return repository.definitions();
    }
    @Transactional(readOnly=true)
    public List<PermissionPreset> lookup(long actor, String scope) {
        var state=authorization.current(actor);
        var visible=authorization.scope(state,scope,BranchAction.READ);
        if (!state.owner() && visible.stream().noneMatch(id -> authorization.permits(state,id,BranchAction.MANAGE_PEOPLE))) return List.of();
        return repository.list();
    }
    @Transactional(readOnly=true)
    public PageResponse<PermissionPreset> list(long actor, ListQuery query) {
        var state=authorization.current(actor);
        if (!state.owner() && authorization.scope(state,"all",BranchAction.MANAGE_PEOPLE).isEmpty()) throw AuthorizationService.forbidden();
        var rows=repository.list().stream().filter(p->p.name().toLowerCase(Locale.ROOT).contains(query.q().toLowerCase(Locale.ROOT))).toList();
        return PageResponse.fromAuthorized(rows,query,"name".equals(query.sort()) ? Comparator.comparing(PermissionPreset::name) : Comparator.comparing(PermissionPreset::createdAt), Comparator.comparingLong(p->Long.parseLong(p.id())));
    }
    /** Caller must hold AuthorizedCommands' global guard for a consistent preset-copy operation. */
    public void validateCopy(String role, List<String> permissions, Long presetId) {
        if (presetId != null) {
            var preset=repository.find(presetId,true).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!preset.role().equals(role)) throw ApiException.validation("presetId","역할에 맞는 프리셋을 선택해 주세요.");
        }
        repository.validate(role, permissions);
    }
    public PermissionPreset create(long actor, Map<String,Object> body, String key, String requestId) {
        return commands.ownerCommand(actor,()-> {
            var input=new StrictInput(body).only("name","role","permissions");
            String name=input.text("name",100), role=input.choice("role","admin","staff"); var grants=input.permissions();
            repository.validate(role,grants);
            if (key==null || key.length()<16 || key.length()>128 || key.chars().anyMatch(c->c<33 || c>126)) throw ApiException.validation("Idempotency-Key","16~128자의 멱등 키가 필요합니다.");
            // Normalize the payload before hashing so JSON object key ordering is immaterial.
            var normalized=List.of(name,role,grants);
            var replay=repository.replay(actor,key,normalized); if(replay.isPresent()) return replay.get();
            try {
                long id=repository.create(actor,name,role,grants); var result=repository.find(id,true).orElseThrow();
                repository.changed(actor,id,"CREATE",null,result,requestId); repository.remember(actor,key,normalized,result); return result;
            } catch (DuplicateKeyException exception) { throw ApiException.conflict("DUPLICATE_PRESET","같은 이름과 역할의 프리셋이 있습니다."); }
        });
    }
    public PermissionPreset update(long actor, long id, Map<String,Object> body, String requestId) {
        return commands.ownerCommand(actor,()-> {
            var before=repository.find(id,true).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            if(before.system()) throw AuthorizationService.forbidden();
            var input=new StrictInput(body).only("version","name","permissions");
            ApiVersion.requireCurrent(input.version("version"),before.version()-1,"/api/permission-presets");
            var grants=input.permissions(); repository.validate(before.role(),grants);
            try { repository.update(actor,id,input.text("name",100),grants); }
            catch(DuplicateKeyException exception) { throw ApiException.conflict("DUPLICATE_PRESET","같은 이름과 역할의 프리셋이 있습니다."); }
            var result=repository.find(id,true).orElseThrow(); repository.changed(actor,id,"UPDATE",before,result,requestId); return result;
        });
    }
}
