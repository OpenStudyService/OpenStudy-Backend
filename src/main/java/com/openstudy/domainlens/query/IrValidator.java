package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.security.PolicyEngine;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

public final class IrValidator {
    private com.openstudy.domainlens.catalog.FormulaRegistry formulas;
    public IrValidator formulas(com.openstudy.domainlens.catalog.FormulaRegistry registry){formulas=registry;return this;}
    public void validateReference(com.openstudy.domainlens.api.work.ExecutionPlan plan,UserContext user){
        if(!plan.formulas().isEmpty()) {
            if(formulas==null)throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Stored formula registry unavailable");
            try {plan.formulas().forEach(formulas::requireRef);var ids=new HashSet<String>();plan.query().metrics().forEach(m->ids.add(m.field()));if(plan.query().analysisId()!=null)ids.add(plan.query().analysisId());require(plan.formulas().size()==ids.size() && plan.formulas().stream().map(f->f.id()).collect(java.util.stream.Collectors.toSet()).equals(ids),"Stored formula selection mismatch");FormulaPlanning.validateInputs(formulas,plan.query());}
            catch(EngineException ex){throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Stored formula version unavailable");}
        }
        validateReference(plan.query(),user,plan.asOf(),plan.snapshotAt());
    }
    private final SemanticRegistry registry;private final PolicyEngine policies;private final DateNormalizer dates;private final AnalysisRegistry analyses;
    public IrValidator(SemanticRegistry registry,PolicyEngine policies,DateNormalizer dates){this(registry,policies,dates,null);}
    public IrValidator(SemanticRegistry registry,PolicyEngine policies,DateNormalizer dates,AnalysisRegistry analyses){this.registry=registry;this.policies=policies;this.dates=dates;this.analyses=analyses;}
    PolicyEngine policies(){return policies;}
    Set<String> analysisDiscoverySources(Set<String> ids){
        if(!(analyses instanceof AnalysisExecutor executor))return Set.of();
        var sources=new HashSet<String>();
        for(String id:ids)if(executor.definition(id).filter(AnalysisRegistry.Definition::executable).isPresent()){sources.addAll(executor.discoverySources(id));sources.addAll(executor.baseSources(id));}
        sources.retainAll(registry.publicNodeIds());return Set.copyOf(sources);
    }
    /** Model-visible registered analyses are a server allowlist, including virtual sources. */
    java.util.List<com.openstudy.domainlens.api.prompt.PromptPackage.Analysis> promptAnalyses(UserContext user, Set<String> context, String question, String previousDataset) {
        if(!(analyses instanceof AnalysisExecutor executor))return List.of();
        var result=new ArrayList<com.openstudy.domainlens.api.prompt.PromptPackage.Analysis>();
        for(String id:executor.analysisIds().stream().sorted().toList()) {
            boolean repeated=previousDataset!=null && executor.definition(id).map(d->d.outputs().containsKey(previousDataset)).orElse(false);
            if(!context.containsAll(executor.discoverySources(id)) || !repeated && !executor.relevantToQuestion(id,question))continue;
            var definition=executor.definition(id).filter(AnalysisRegistry.Definition::executable);
            if(definition.isEmpty() || definition.get().outputs().isEmpty())continue;
            var d=definition.get();
            var probe=new QuerySpec(d.outputs().keySet().stream().sorted().findFirst().orElseThrow(),d.source(),d.grain(),QuerySpec.Mode.REGISTERED_ANALYSIS,List.of(),List.of(),null,List.of(),new QuerySpec.Page(0,10),50,id);
            try {
                query(probe,user);
                var inputs=new LinkedHashMap<String,com.openstudy.domainlens.api.prompt.PromptPackage.Input>();
                for(var field:executor.inputFields(id).entrySet()) {
                    try {
                        for(String dependency:field.getValue().policyFields()){policies.field(dependency,"search",user);policies.field(dependency,"filter",user);}
                        inputs.put(field.getKey(),new com.openstudy.domainlens.api.prompt.PromptPackage.Input(field.getValue().type(),field.getValue().operators()));
                    }catch(EngineException ex){if(ex.code()!=EngineErrorCode.SCOPE_DENIED && ex.code()!=EngineErrorCode.PII_DENIED)throw ex;}
                }
                result.add(new com.openstudy.domainlens.api.prompt.PromptPackage.Analysis(id,d.version(),d.source(),d.grain(),d.outputs(),inputs,d.outputs().keySet().stream().collect(java.util.stream.Collectors.toMap(k->k,k->executor.outputMaxRows(id,k))),executor.description(id),executor.presentations(id),executor.presentationEvents(id)));
            }catch(EngineException ex){if(ex.code()!=EngineErrorCode.SCOPE_DENIED && ex.code()!=EngineErrorCode.PII_DENIED)throw ex;}
        }
        return List.copyOf(result);
    }
    /** Rechecks read, field and row policies without executing any database query. */
    public void validateReference(QuerySpec query,UserContext user,Instant asOf,Instant snapshotAt) {
        query(query,user);
        new JoinPlanner(registry,policies,analyses instanceof AnalysisExecutor executor?executor:null).plan(query,user,asOf,snapshotAt);
    }
    public Set<String> referenceContext(QuerySpec query) {
        var refs=new HashSet<String>();PromptService.filterReferences(query.filter(),refs);
        if(query.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS && analyses instanceof AnalysisExecutor executor) {
            var virtualInputs=executor.inputFields(query.analysisId()).keySet();
            require(refs.stream().allMatch(id->registry.publicNodeIds().contains(id) || virtualInputs.contains(id)),"Unknown reference filter");
            refs.removeIf(id->!registry.publicNodeIds().contains(id));
            refs.addAll(executor.baseSources(query.analysisId()));
            if(registry.sources().containsKey(query.source()))refs.add(query.source());
        }else {
            refs.add(query.source());
            // Carry only approved aggregate selections; detail/virtual output columns are not grouping.
            if(query.mode()==QuerySpec.Mode.AGGREGATE) {
                query.dimensions().forEach(d->refs.add(d.field()));
                for(var selected:query.metrics()) {
                    refs.add(selected.field());var metric=registry.metrics().get(selected.field());
                    if(metric!=null) {
                        if(metric.field()!=null)refs.add(metric.field());
                        // A follow-up may retrieve no new metric. Its verified reference must
                        // still carry the complete registered path checked by ContextGate.
                        for(String path:metric.path()){
                            var relation=registry.relations().get(path);require(relation!=null,"Unknown reference metric path");
                            refs.add(relation.source());refs.add(relation.target());
                        }
                        refs.addAll(metric.contextFields());PromptService.filterReferences(metric.defaultFilter(),refs);
                    }
                }
            }
        }
        require(registry.publicNodeIds().containsAll(refs),"Unknown reference context");
        return Set.copyOf(refs);
    }
    java.time.ZoneId calendarZone(){return dates.calendarZone();}
    public IrDocument json(String json,Set<String> allowed,UserContext user) {var ir=ContractJson.document(json);validate(ir,allowed,user);return ir;}
    public void validate(IrDocument ir,Set<String> allowed,UserContext user) {
        ContractRules.datasets(ir,analyses);
        for(String id:ir.contextRefs())require(registry.publicNodeIds().contains(id) && allowed.contains(id),"Unapproved context reference");
        for(var q:ir.queries())query(q,user);
    }
    public AnalysisRegistry.Definition analysisDefinition(String id){
        if(analyses==null)throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Analysis registry absent");
        return analyses.definition(id).orElseThrow(()->new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Unregistered analysis"));
    }
    public void query(QuerySpec q,UserContext user) {
        if(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS){
            var definition=analyses==null?null:analyses.definition(q.analysisId()).orElse(null);
            if(definition!=null)require(definition.source().equals(q.source()) && definition.grain().equals(q.grain()),"Analysis source/grain mismatch");
            if(definition!=null && definition.executable() && analyses instanceof AnalysisExecutor executor){executor.validate(q,user);return;}
            throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,definition==null?"Unregistered analysis executor":"Analysis contract is registered; executor is not enabled");
        }
        var source=registry.sources().get(q.source());require(source!=null,"Unregistered source");
        require(q.grain().equals(source.grain()),"Mixed/invalid grain");
        require(q.mode()!=QuerySpec.Mode.DETAIL || (!q.dimensions().isEmpty() && q.metrics().isEmpty()),"Invalid DETAIL selection");
        require(q.mode()!=QuerySpec.Mode.AGGREGATE || !q.metrics().isEmpty(),"Missing aggregate");
        for(var s:q.dimensions())require(registry.fields().containsKey(s.field()),"Unregistered field");
        for(var m:q.metrics()) {var metric=registry.metrics().get(m.field());require(metric!=null && metric.grain().equals(q.grain()),"Unregistered metric/grain");filter(metric.defaultFilter());}
        filter(q.filter());policies.query(q,user);
    }
    public void filter(Filter filter) {
        if(filter instanceof Filter.Group g){g.children().forEach(this::filter);return;}
        if(!(filter instanceof Filter.Leaf l))return;
        var field=registry.fields().get(l.field());require(field!=null,"Unknown filter field");
        require(field.operations().contains(l.operator().name()),"Unsupported field operator");
        require(field.type()!=ValueType.TIMESTAMP || l.operator()!=Filter.Operator.BETWEEN,"Timestamp BETWEEN forbidden");
        for(Object value:l.values()) {
            Object typed=value(field.type(),value);
            require(field.metadata().allowedValues().isEmpty() || field.metadata().allowedValues().contains(typed),"Filter value outside allowlist");
            if(field.type()==ValueType.TIMESTAMP) {
                Instant instant=((LocalDateTime)typed).toInstant(ZoneOffset.UTC);
                dates.checkedBoundary(instant);
            }
        }
        if(l.operator()==Filter.Operator.BETWEEN)require(compare(value(field.type(),l.values().get(0)),value(field.type(),l.values().get(1)))<=0,"Reversed range");
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private int compare(Object a,Object b){return ((Comparable)a).compareTo(b);}
    public static Object value(ValueType type,Object value) {
        try{return switch(type) {
            case STRING -> {if(!(value instanceof String))throw new IllegalArgumentException();yield value;}
            case INTEGER -> {if(!(value instanceof Number))throw new IllegalArgumentException();yield new BigDecimal(value.toString()).longValueExact();}
            case DECIMAL -> {if(!(value instanceof Number))throw new IllegalArgumentException();yield new BigDecimal(value.toString());}
            case BOOLEAN -> {if(!(value instanceof Boolean))throw new IllegalArgumentException();yield value;}
            case DATE -> LocalDate.parse(value.toString());
            case TIMESTAMP -> {String s=value.toString();yield s.endsWith("Z")?LocalDateTime.ofInstant(Instant.parse(s),ZoneOffset.UTC):LocalDateTime.parse(s);}
        };}catch(RuntimeException ex){throw new EngineException(EngineErrorCode.IR_INVALID,"Filter type mismatch");}
    }
    private static void require(boolean condition,String message){ContractRules.require(condition,message);}
}
