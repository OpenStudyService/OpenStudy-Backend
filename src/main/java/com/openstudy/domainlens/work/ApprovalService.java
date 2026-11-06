package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.SearchText;
import com.openstudy.domainlens.security.Identity;
import com.openstudy.domainlens.query.IrValidator;
import java.time.*;
import java.util.*;
public final class ApprovalService {
    private final RequestStore requests;private final TraceSpanCollector trace;
    private final ApprovalStore approvals;private final WorkStore work;private final IrValidator validator;private final ApprovalPolicy policy;private final Clock clock;
    public ApprovalService(ApprovalStore approvals,WorkStore work,IrValidator validator,ApprovalPolicy policy,Clock clock){this(approvals,work,validator,policy,clock,null,TraceSpanCollector.NOOP);}
    public ApprovalService(ApprovalStore approvals,WorkStore work,IrValidator validator,ApprovalPolicy policy,Clock clock,RequestStore requests,TraceSpanCollector trace){this.requests=requests;this.trace=trace;this.approvals=approvals;this.work=work;this.validator=validator;this.policy=policy;this.clock=clock;}
    public ApprovalStore.Approval request(UUID execution,String dataset,String component,List<String> aliases,String key,UserContext user){
        String owner=Identity.require(user).subjectId();
        if(execution==null || dataset==null || component==null || key==null || key.isBlank() || key.length()>128 || aliases==null || aliases.isEmpty() || aliases.size()!=new HashSet<>(aliases).size())throw invalid();
        var saved=work.execution(execution,owner).orElseThrow(ApprovalService::missing);
        var card=saved.view()==null?null:saved.view().components().stream().filter(c->c.id().equals(component) && c.datasetId().equals(dataset)).findFirst().orElse(null);
        if(card==null || card.events().stream().noneMatch(e->e.actionId().equals("requestCsv")))throw new EngineException(EngineErrorCode.SCOPE_DENIED,"CSV action not registered on this component");
        var ds=saved.datasets().stream().filter(d->d.id().equals(dataset)).findFirst().orElseThrow(ApprovalService::missing);
        var columns=aliases.stream().map(alias->ds.columns().stream().filter(c->c.alias().equals(alias)).findFirst().orElseThrow(ApprovalService::invalid)).toList();
        var plan=ds.plan();validator.validateReference(plan.query(),user,plan.asOf(),plan.snapshotAt());
        String version=policy.authorize(plan,columns,user,false);String hash=hash(plan,columns,user,version);
        Instant now=clock.instant();return traced(execution,user,()->approvals.create(new ApprovalStore.Approval(UUID.randomUUID(),owner,key,execution,dataset,component,plan,columns,ds.totalElements(),hash,version,ApprovalStore.State.PENDING,now,now.plus(Duration.ofHours(24)))));
    }
    public ApprovalStore.Approval get(UUID id,UserContext user){return approvals.update(id,Identity.require(user).subjectId(),this::expire);}
    public List<ApprovalStore.Approval> list(UserContext user){return approvals.list(Identity.require(user).subjectId()).stream().map(a->get(a.id(),user)).toList();}
    private ApprovalStore.Approval expire(ApprovalStore.Approval a){return !clock.instant().isBefore(a.expiresAt()) && (a.state()==ApprovalStore.State.PENDING || a.state()==ApprovalStore.State.APPROVED)?a.state(ApprovalStore.State.EXPIRED):a;}
    public ApprovalStore.Approval decide(UUID id,boolean approve,String expectedHash,UserContext user){
        String owner=Identity.require(user).subjectId();
        var target=approvals.find(id,owner).orElseThrow(ApprovalService::missing);
        return traced(target.executionId(),user,()->approvals.update(id,owner,a->{
            var current=expire(a);if(current.state()==ApprovalStore.State.EXPIRED)return current;
            if(!current.hash().equals(expectedHash))throw invalid();
            revalidate(current,user,true);
            var next=approve?ApprovalStore.State.APPROVED:ApprovalStore.State.DENIED;
            if(current.state()==next)return current;
            if(current.state()!=ApprovalStore.State.PENDING)throw invalid();
            return current.state(next);
        }));
    }
    private <T>T traced(UUID execution,UserContext user,java.util.function.Supplier<T> action){var request=requests==null?Optional.<RequestStore.Request>empty():requests.forExecution(execution,user.subjectId());if(request.isEmpty())return action.get();try(var scope=trace.request(request.get().id(),user.subjectId())){try{return trace.measure("approval",action);}finally{trace.finish(request.get().state());}}}
    public void revalidate(ApprovalStore.Approval a,UserContext user,boolean decision){
        Identity.owner(user,a.owner());var p=a.plan();validator.validateReference(p.query(),user,p.asOf(),p.snapshotAt());
        String version=policy.authorize(p,a.columns(),user,decision);
        if(!a.hash().equals(hash(p,a.columns(),user,version)))throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Approval conditions changed; request new approval");
    }
    private static String hash(ExecutionPlan plan,List<TypedDataset.Column> columns,UserContext user,String version){return SearchText.hash(ContractJson.canonical(List.of(plan,columns,Identity.require(user),version)));}
    private static EngineException invalid(){return new EngineException(EngineErrorCode.VALIDATION_ERROR,"Invalid approval request or decision");}
    private static EngineException missing(){return new EngineException(EngineErrorCode.NOT_FOUND,"Approval target not found");}
}
