package com.openstudy.modules.employee.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.*;
import com.openstudy.modules.employee.dto.ManagedEmployee;
import com.openstudy.modules.employee.dto.ManagedEmployee.MembershipResult;
import com.openstudy.modules.employee.repository.EmployeeManagementRepository;

@Service
public class EmployeeManagementService {
    private final EmployeeManagementRepository repository;
    private final AuthorizationService authorization;
    private final AuthorizedCommands commands;
    private final PermissionPresetService presets;
    public EmployeeManagementService(EmployeeManagementRepository repository, AuthorizationService authorization,
            AuthorizedCommands commands, PermissionPresetService presets) {
        this.repository=repository; this.authorization=authorization; this.commands=commands; this.presets=presets;
    }
    private record Visibility(Set<Long> branches, boolean all) {}
    private Visibility visible(long actor, String scope) {
        var state=authorization.current(actor);
        var branches=authorization.scope(state,scope,BranchAction.MANAGE_PEOPLE);
        if (!state.owner() && branches.isEmpty()) throw AuthorizationService.forbidden();
        return new Visibility(branches,state.owner() && (scope==null || "all".equals(scope)));
    }
    @Transactional(readOnly=true)
    public PageResponse<ManagedEmployee> list(long actor, String scope, ListQuery query, String status) {
        if(status!=null && !Set.of("활성","승인 대기","이용 제한","비활성").contains(status)) throw ApiException.validation("status","올바른 상태를 선택해 주세요.");
        var visibility=visible(actor,scope);
        var rows=repository.ids().stream().map(id->repository.find(id,visibility.branches(),visibility.all(),false).orElseThrow())
                .filter(e->visibility.all() || !e.memberships().isEmpty())
                .filter(e->status==null || status.equals(e.status()))
                .filter(e->(e.name()+" "+e.email()).toLowerCase(Locale.ROOT).contains(query.q().toLowerCase(Locale.ROOT))).toList();
        return PageResponse.fromAuthorized(rows,query,"name".equals(query.sort()) ? Comparator.comparing(ManagedEmployee::name) : Comparator.comparing(ManagedEmployee::createdAt),Comparator.comparingLong(e->Long.parseLong(e.id())));
    }
    @Transactional(readOnly=true)
    public ManagedEmployee get(long actor, long target, String scope) {
        var visibility=visible(actor,scope);
        var employee=repository.find(target,visibility.branches(),visibility.all(),false).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!visibility.all() && employee.memberships().isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return employee;
    }
    public ManagedEmployee update(long actor, long target, Map<String,Object> body, String requestId) {
        return commands.accountChange(actor,target,()-> {
            var before=repository.find(target,Set.of(),true,true).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            var input=new StrictInput(body).only("version","name","status");
            ApiVersion.requireCurrent(input.version("version"),before.version()-1,"/api/employees/"+target);
            if(!input.has("name") && !input.has("status")) throw ApiException.validation("body","변경할 값을 입력해 주세요.");
            String name=input.has("name") ? input.text("name",100) : before.name();
            String status=input.has("status") ? input.choice("status","활성","이용 제한","비활성") : before.status();
            if("승인 대기".equals(before.status()) && "활성".equals(status)) throw ApiException.conflict("INVALID_STATE","초대 승인으로만 활성화할 수 있습니다.");
            String db=switch(status) { case "활성"->"ACTIVE"; case "이용 제한"->"SUSPENDED"; case "비활성"->"LEFT"; case "승인 대기"->"PENDING"; default->throw new IllegalStateException(); };
            repository.updateAccount(target,actor,name,db);
            var result=repository.find(target,Set.of(),true,true).orElseThrow();
            repository.changed(actor,null,target,"UPDATE_ACCOUNT",before,result,requestId); return result;
        });
    }
    public MembershipResult membership(long actor, long target, long branch, String scope, Map<String,Object> body, String requestId) {
        var input=new StrictInput(body).only("employeeVersion","version","role","status","permissions","presetId");
        String role=input.choice("role","admin","staff"), status=input.choice("status","활성","회수");
        String dbRole="admin".equals(role) ? "MANAGER":"STAFF", dbStatus="활성".equals(status) ? "ACTIVE":"REVOKED";
        return commands.membershipChange(actor,target,branch,dbRole,dbStatus,()-> {
            // Path scope is authoritative; an unrelated query scope must not broaden it.
            var visibility=visible(actor,scope);
            if(!visibility.branches().contains(branch)) throw AuthorizationService.forbidden();
            var before=repository.find(target,Set.of(branch),false,true).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            var old=before.memberships().stream().filter(m->m.branchId().equals(Long.toString(branch))).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
            ApiVersion.requireCurrent(input.version("employeeVersion"),before.version()-1,"/api/employees/"+target+"?scope="+branch);
            ApiVersion.requireCurrent(input.version("version"),old.version()-1,"/api/employees/"+target+"?scope="+branch);
            var grants=input.permissions();
            presets.validateCopy(role,grants,input.has("presetId") ? input.id("presetId") : null);
            repository.updateMembership(target,branch,actor,dbRole,dbStatus,grants);
            var after=repository.find(target,Set.of(branch),false,true).orElseThrow();
            var result=new MembershipResult(after.version(),after.memberships().getFirst());
            repository.changed(actor,branch,target,"UPDATE_MEMBERSHIP",new MembershipResult(before.version(),old),result,requestId); return result;
        });
    }
}
