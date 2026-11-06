package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.formula.FormulaDefinition;
import java.time.Instant;
import java.util.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Routes registered analyses by immutable registration, including saved output actions. */
public final class CompositeAnalysisExecutor implements AnalysisExecutor {
    private final Map<String,AnalysisExecutor> owners;
    public CompositeAnalysisExecutor(AnalysisExecutor... executors){
        var map=new LinkedHashMap<String,AnalysisExecutor>();
        for(var executor:executors)for(var id:executor.analysisIds())require(map.putIfAbsent(id,executor)==null,"Duplicate analysis registration");
        owners=Map.copyOf(map);
    }
    private AnalysisExecutor owner(String id){var value=owners.get(id);require(value!=null,"Unknown analysis");return value;}
    public boolean supports(String id){return owners.containsKey(id);}
    public Set<String> analysisIds(){return owners.keySet();}
    public Optional<Definition> definition(String id){return supports(id)?owner(id).definition(id):Optional.empty();}
    public Map<String,InputField> inputFields(String id){return owner(id).inputFields(id);}
    public Set<String> baseSources(String id){return owner(id).baseSources(id);}
    public Set<String> discoverySources(String id){return owner(id).discoverySources(id);}
    public boolean relevantToQuestion(String id,String question){return owner(id).relevantToQuestion(id,question);}
    public String description(String id){return owner(id).description(id);}
    public int outputMaxRows(String id,String output){return owner(id).outputMaxRows(id,output);}
    public boolean isSummaryOutput(String id,String output){return owner(id).isSummaryOutput(id,output);}
    public List<FormulaDefinition.Presentation> presentations(String id){return owner(id).presentations(id);}
    public Set<String> presentationEvents(String id){return owner(id).presentationEvents(id);}
    public void validate(QuerySpec query,UserContext user){owner(query.analysisId()).validate(query,user);}
    public ExecutionPlan plan(QuerySpec query,UserContext user,Instant asOf,Instant snapshotAt){return owner(query.analysisId()).plan(query,user,asOf,snapshotAt);}
    public List<TypedDataset> executeAll(ExecutionPlan plan,UserContext user,Cancellation cancellation){return owner(plan.query().analysisId()).executeAll(plan,user,cancellation);}
    public void stream(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> sink){owner(plan.query().analysisId()).stream(plan,user,cancellation,sink);}
}
