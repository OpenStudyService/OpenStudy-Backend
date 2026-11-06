package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.security.*;
import java.util.*;

public final class PageService {
    private com.openstudy.domainlens.catalog.FormulaRegistry formulas;
    public PageService formulas(com.openstudy.domainlens.catalog.FormulaRegistry value){formulas=value;return this;}
    public ResultEnvelope metadata(ResultEnvelope result,List<ExecutionPlan> plans){
        var bindings=FormulaPlanning.bindings(plans,result.datasets());
        var values=formulas==null?List.<com.openstudy.domainlens.api.formula.FormulaDefinition.DisplayValues>of():FormulaDisplay.render(formulas,plans,result.datasets());
        return new ResultEnvelope(result.requestId(),result.executionId(),result.status(),result.datasets(),result.view(),result.warnings(),result.errorCode(),result.asOf(),result.snapshotVersion(),result.policyVersion(),bindings,values,result.notice());
    }
    private final PlanStore store;private final PolicyEngine policies;private final QueryExecutor executor;
    public PageService(PlanStore store,PolicyEngine policies,QueryExecutor executor){this.store=store;this.policies=policies;this.executor=executor;}
    public TypedDataset page(UUID executionId,String datasetId,PageRequest request,UserContext user,Cancellation cancellation) {
        String owner=Identity.require(user).subjectId();
        var stored=store.find(executionId,datasetId,owner).orElseThrow(()->new EngineException(EngineErrorCode.NOT_FOUND,"Dataset not found"));
        var plan=stored.plan();Identity.owner(user,plan.owner());
        ContractRules.require(stored.executionId().equals(executionId) && plan.query().id().equals(datasetId),"Stored dataset mismatch");
        policies.action("page",user);
        if(request.page()<0 || request.size()<1 || request.size()>100)throw new EngineException(EngineErrorCode.IR_INVALID,"Invalid page size/number");
        var q=plan.query();var order=request.orderBy()==null?q.orderBy():request.orderBy();
        if(request.orderBy()!=null)policies.action("sort",user);
        var aliases=new HashSet<String>();q.dimensions().forEach(s->aliases.add(s.alias()));q.metrics().forEach(s->aliases.add(s.alias()));
        if(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS)stored.columns().forEach(c->aliases.add(c.alias()));
        for(var sort:order)ContractRules.require(aliases.contains(sort.alias()) && sort.direction()!=null,"Unknown sort alias");
        var paged=new QuerySpec(q.id(),q.source(),q.grain(),q.mode(),q.dimensions(),q.metrics(),q.filter(),order,new QuerySpec.Page(request.page(),request.size()),q.limit(),q.analysisId());
        var next=new ExecutionPlan(plan.formatVersion(),plan.registryVersion(),plan.policyVersion(),plan.owner(),plan.effectiveScope(),plan.asOf(),plan.snapshotAt(),paged,plan.joins(),plan.rowFilters(),plan.uniqueKeys(),plan.timeoutSeconds(),plan.formulas());
        if(q.mode()!=QuerySpec.Mode.REGISTERED_ANALYSIS)policies.query(paged,user);
        var result=executor.execute(next,user,cancellation);
        if(!result.columns().equals(stored.columns()))throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Stored result schema mismatch");
        return result;
    }
}
