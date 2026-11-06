package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.security.Identity;
import java.util.*;

/** Dispatches only registered server-owned analyses and validates their complete output contract. */
public final class AnalysisQueryExecutor implements QueryExecutor {
    private final QueryExecutor ordinary;
    private final AnalysisExecutor analyses;
    public AnalysisQueryExecutor(QueryExecutor ordinary,AnalysisExecutor analyses){this.ordinary=ordinary;this.analyses=analyses;}
    public TypedDataset execute(ExecutionPlan plan,UserContext user,Cancellation cancellation){
        if(plan.query().mode()!=QuerySpec.Mode.REGISTERED_ANALYSIS)return ordinary.execute(plan,user,cancellation);
        var dataset=executeAll(plan,user,cancellation).stream().filter(d->d.id().equals(plan.query().id())).findFirst()
            .orElseThrow(()->new EngineException(EngineErrorCode.IR_INVALID,"Unknown analysis output"));
        var page=plan.query().pagination();
        if(!analyses.isSummaryOutput(plan.query().analysisId(),dataset.id()))return dataset;
        // Full, bounded summary output, not a materialized unbounded population.
        if(dataset.page()==0 && dataset.totalElements()==dataset.rows().size()){
            var rows=new ArrayList<>(dataset.rows());
            Comparator<Map<String,Object>> comparator=(a,b)->0;
            var aliases=dataset.columns().stream().map(TypedDataset.Column::alias).collect(java.util.stream.Collectors.toSet());
            for(var order:plan.query().orderBy())if(aliases.contains(order.alias())){
                Comparator<Map<String,Object>> next=(a,b)->compare(a.get(order.alias()),b.get(order.alias()));
                comparator=comparator.thenComparing(order.direction()==QuerySpec.Direction.ASC?next:next.reversed());
            }
            rows.sort(comparator);long offset=(long)page.page()*page.size();int start=(int)Math.min(offset,rows.size());int end=Math.min(start+page.size(),rows.size());
            return new TypedDataset(dataset.id(),dataset.columns(),rows.subList(start,end),dataset.totalElements(),page.page(),page.size(),dataset.snapshotAt(),dataset.asOf());
        }
        throw new EngineException(EngineErrorCode.IR_INVALID,"Incomplete summary output");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static int compare(Object a,Object b){if(a==null)return b==null?0:-1;if(b==null)return 1;if(a instanceof Number && b instanceof Number)return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));return ((Comparable)a).compareTo(b);}
    public List<TypedDataset> executeAll(ExecutionPlan plan,UserContext user,Cancellation cancellation){
        if(plan.query().mode()!=QuerySpec.Mode.REGISTERED_ANALYSIS)return ordinary.executeAll(plan,user,cancellation);
        Identity.owner(user,plan.owner());cancellation.check();analyses.validate(plan.query(),user);
        var definition=analyses.definition(plan.query().analysisId()).filter(AnalysisRegistry.Definition::executable)
            .orElseThrow(()->new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Analysis executor unavailable"));
        ContractRules.require(!definition.outputs().isEmpty() && definition.outputs().size()<=6,"Analysis output budget");
        var current=analyses.plan(plan.query(),user,plan.asOf(),plan.snapshotAt());
        if(plan.formatVersion()!=current.formatVersion() || !plan.registryVersion().equals(current.registryVersion()))
            throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Stored analysis version unsupported");
        ContractRules.require(plan.joins().equals(current.joins()) && plan.uniqueKeys().equals(current.uniqueKeys())
            && plan.rowFilters().keySet().equals(current.rowFilters().keySet()) && plan.timeoutSeconds()==5,"Invalid analysis plan");
        var results=analyses.executeAll(plan,user,cancellation);var ids=new HashSet<String>();
        for(var ds:results){ResultRules.dataset(ds);ContractRules.require(ids.add(ds.id()) && ds.columns().equals(definition.outputs().get(ds.id()))
            && ds.asOf().equals(plan.asOf()) && ds.snapshotAt().equals(plan.snapshotAt()),"Analysis output contract mismatch");}
        ContractRules.require(ids.equals(definition.outputs().keySet()),"Missing analysis output");cancellation.check();return results;
    }
}
