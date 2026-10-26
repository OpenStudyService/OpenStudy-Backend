package com.openstudy.modules.branch.service;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.repository.BranchRepository;

@Service
public class BranchService {
    private final BranchRepository repository;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    public BranchService(BranchRepository repository,AuthorizationService policy,AuthorizedCommands commands,ObjectMapper json) {
        this.repository=repository;this.policy=policy;this.commands=commands;this.json=json;
    }
    @Transactional(readOnly=true)
    public PageResponse<Branch> list(long actor,String scope,ListQuery query,String status) {
        if(status!=null) status(status);
        Set<Long> allowed=policy.scope(actor,scope,BranchAction.READ);
        var rows=repository.ids().stream().filter(allowed::contains).map(repository::get)
                .filter(b->status==null||status.equals(b.status()))
                .filter(b->(b.name()+" "+b.address()+" "+b.code()).toLowerCase(Locale.ROOT).contains(query.q().toLowerCase(Locale.ROOT))).toList();
        Comparator<Branch> field="name".equals(query.sort())?Comparator.comparing(Branch::name):Comparator.comparing(Branch::createdAt);
        return PageResponse.fromAuthorized(rows,query,field,Comparator.comparingLong(b->Long.parseLong(b.id())));
    }
    @Transactional(readOnly=true)
    public List<Branch> lookup(long actor, String scope) {
        Set<Long> allowed=policy.scope(actor,scope,BranchAction.READ);
        return repository.ids().stream().filter(allowed::contains).map(repository::get).toList();
    }
    @Transactional(readOnly=true)
    public Branch get(long actor,long id) {
        // Require access before reading the branch or its schedule; no existence oracle outside scope.
        policy.require(policy.current(actor),Set.of(id),BranchAction.READ);
        return repository.get(id);
    }
    public Branch create(long actor,String key,BranchRequests.Create body,String requestId) {
        if(key==null||key.length()<16||key.length()>128) throw ApiException.validation("Idempotency-Key","16~128자 키가 필요합니다.");
        var normalized=new BranchRequests.Create(text(body.name(),"name"),text(body.address(),"address"),body.phone(),hours(body.weeklyHours()),body.status());
        String databaseStatus=status(body.status());
        return commands.ownerCommand(actor,()->{
            byte[] fingerprint=digest(json.writeValueAsString(normalized));
            var prior=repository.request(actor,key);
            if(!prior.isEmpty()) {
                if(!MessageDigest.isEqual(fingerprint,prior.getFirst().hash())) throw ApiException.conflict("IDEMPOTENCY_CONFLICT","같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.getFirst().response(),Branch.class);
            }
            long id=repository.create(normalized.name(),normalized.address(),normalized.phone(),databaseStatus,normalized.weeklyHours());
            var result=repository.get(id);String snapshot=json.writeValueAsString(result);
            repository.audit(actor,id,"CREATED",null,snapshot,requestId);
            repository.saveRequest(actor,key,fingerprint,snapshot);
            return result;
        });
    }
    public Branch update(long actor,long id,BranchRequests.Update body,String requestId) {
        ApiVersion.toDatabase(body.version());
        if(body.name()==null&&body.address()==null&&body.phone()==null&&body.weeklyHours()==null) throw ApiException.validation("body","변경할 항목이 필요합니다.");
        var schedule=body.weeklyHours()==null?null:hours(body.weeklyHours());
        return commands.inBranches(actor,Set.of(id),BranchAction.MANAGE_BRANCH,()->{
            var before=repository.get(id); checkVersion(before,body.version());
            repository.update(id,body.name()==null?before.name():text(body.name(),"name"),body.address()==null?before.address():text(body.address(),"address"),
                    body.phone()==null?before.phone():body.phone(),schedule==null?before.weeklyHours():schedule);
            var result=repository.get(id);repository.audit(actor,id,"UPDATED",json.writeValueAsString(before),json.writeValueAsString(result),requestId);
            return result;
        });
    }
    public Branch changeStatus(long actor,long id,BranchRequests.Status body,String requestId) {
        ApiVersion.toDatabase(body.version());String state=status(body.status());
        return commands.inBranches(actor,Set.of(id),BranchAction.MANAGE_BRANCH,()->{
            var before=repository.get(id);checkVersion(before,body.version());repository.status(id,state);
            var result=repository.get(id);repository.audit(actor,id,"STATUS_CHANGED",json.writeValueAsString(before),json.writeValueAsString(result),requestId);
            return result;
        });
    }
    private static void checkVersion(Branch branch,long supplied) {
        if(branch.version()!=supplied) throw ApiException.versionConflict(branch.version(),"/api/branches/"+branch.id());
    }
    private static String text(String value,String field) {
        if(value==null||value.isBlank()) throw ApiException.validation(field,"값을 입력해 주세요."); return value.strip();
    }
    private static String status(String status) {
        return switch(status==null?"":status) {
            case "운영 중"->"ACTIVE";case "운영 중지"->"SUSPENDED";
            default->throw ApiException.validation("status","올바른 지점 상태를 선택해 주세요.");
        };
    }
    private static List<WeeklyHours> hours(List<WeeklyHours> hours) {
        if(hours==null||hours.size()!=7) throw ApiException.validation("weeklyHours","7개 요일을 모두 지정해 주세요.");
        Set<Integer> days=new HashSet<>();
        for(var h:hours) {
            if(h==null||h.weekday()==null||h.weekday()<1||h.weekday()>7||!days.add(h.weekday())||h.isClosed()==null)
                throw ApiException.validation("weeklyHours","요일은 1~7까지 중복 없이 지정해 주세요.");
            if(h.isClosed() ? h.opensMinute()!=null||h.closesMinute()!=null
                    : h.opensMinute()==null||h.closesMinute()==null||h.opensMinute()<0||h.opensMinute()>1439||h.closesMinute()>2880||h.closesMinute()<=h.opensMinute())
                throw ApiException.validation("weeklyHours","휴무는 시각 없이, 영업은 시작보다 늦은 종료 분을 지정해 주세요.");
        }
        return hours.stream().sorted(Comparator.comparing(WeeklyHours::weekday)).toList();
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
