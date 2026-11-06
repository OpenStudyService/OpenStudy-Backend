package com.openstudy.domainlens.api.planning;

import com.openstudy.domainlens.api.prompt.PromptPackage;
import com.openstudy.domainlens.api.ir.*;
import java.time.Instant;
import java.util.*;

/** Approved, protected data only. No executable IR/UI schema or physical JPA paths. */
public record PlanningPrompt(String version, String question, Instant asOf, String calendarZone,
        List<PromptPackage.Node> context, List<PromptPackage.Analysis> analyses,
        PromptPackage.Previous previous, List<String> presentations, List<Example> examples,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Contract> formulas) {
    public PlanningPrompt { context=List.copyOf(context); analyses=List.copyOf(analyses);
        presentations=List.copyOf(presentations); examples=List.copyOf(examples);formulas=formulas==null?List.of():List.copyOf(formulas); }
    public PlanningPrompt(String version,String question,Instant asOf,String calendarZone,List<PromptPackage.Node> context,List<PromptPackage.Analysis> analyses,PromptPackage.Previous previous,List<String> presentations,List<Example> examples){this(version,question,asOf,calendarZone,context,analyses,previous,presentations,examples,List.of());}
    public record Example(String question, SemanticPlan plan) {}
    public static PlanningPrompt from(PromptPackage prompt) {
        var d=prompt.data();
        return new PlanningPrompt(version(d),d.question(),d.asOf(),d.calendarZone(),d.context(),
            d.analyses(),d.previous(),prompt.components().components().stream().map(c->c.id()).sorted().toList(),d.examples().stream().limit(3).map(PlanningPrompt::example).map(e->withFormulas(e,d.formulas(),version(d))).toList(),d.formulas());
    }
    public static String version(PromptPackage.Data d){
        if(d.formulas().isEmpty())return SemanticPlan.VERSION;
        return SemanticPlan.MIXED_VERSION.equals(d.planningVersion())?SemanticPlan.MIXED_VERSION:SemanticPlan.FORMULA_VERSION;
    }
    private static Example withFormulas(Example example,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Contract> contracts,String version) {
        if(contracts.isEmpty())return example;
        var plan=example.plan();var requests=plan.requests().stream().map(r->{
            var ids=new HashSet<String>(r.metrics());if(r.analysisId()!=null)ids.add(r.analysisId());
            var refs=contracts.stream().filter(f->ids.contains(f.id())).map(f->new com.openstudy.domainlens.api.formula.FormulaDefinition.Ref(f.id(),f.version(),f.hash())).toList();
            return new SemanticPlan.Request(r.kind(),r.source(),r.analysisId(),r.metrics(),r.dimensions(),r.filter(),r.order(),r.page(),r.size(),r.limit(),r.total(),r.presentations(),r.evidence(),refs);
        }).toList();
        return new Example(example.question(),new SemanticPlan(version,plan.status(),plan.reason(),plan.options(),requests,plan.unresolved(),plan.reasonCode()));
    }
    private static Example example(PromptPackage.Example example) {
        var ir=example.ir();var requests=new ArrayList<SemanticPlan.Request>();
        for(var q:ir.queries()) {
            var evidence=new ArrayList<SemanticPlan.Evidence>();
            var selected=new LinkedHashSet<String>();q.metrics().forEach(m->selected.add(m.field()));q.dimensions().forEach(d->selected.add(d.field()));
            if(q.mode()==QuerySpec.Mode.DETAIL)selected.add(q.source());if(q.analysisId()!=null)selected.add(q.analysisId());
            selected.forEach(id->evidence.add(new SemanticPlan.Evidence(id,SemanticPlan.Origin.QUESTION,example.question())));
            if(q.filter()!=null)evidence.add(new SemanticPlan.Evidence("filter",SemanticPlan.Origin.QUESTION,example.question()));
            var aliases=new HashMap<String,String>();q.metrics().forEach(m->aliases.put(m.alias(),m.field()));q.dimensions().forEach(d->aliases.put(d.alias(),d.field()));
            var sorts=q.orderBy().stream().filter(o->aliases.containsKey(o.alias())).map(o->new SemanticPlan.Sort(aliases.get(o.alias()),o.direction())).toList();
            if(!sorts.isEmpty())evidence.add(new SemanticPlan.Evidence("order",SemanticPlan.Origin.QUESTION,example.question()));
            requests.add(new SemanticPlan.Request(q.mode(),q.mode()==QuerySpec.Mode.DETAIL?q.source():null,q.analysisId(),
                q.metrics().stream().map(QuerySpec.Selection::field).toList(),q.dimensions().stream().map(QuerySpec.Selection::field).toList(),
                q.filter(),sorts,null,null,null,false,List.of(),evidence));
        }
        return new Example(example.question(),new SemanticPlan(SemanticPlan.VERSION,ir.status(),ir.status()==IrStatus.NEEDS_CLARIFICATION?ir.clarification():ir.unsupportedReason(),ir.clarificationOptions(),requests,List.of(),ir.reasonCode()==null?ReasonCode.defaultFor(ir.status()):ir.reasonCode()));
    }
}
