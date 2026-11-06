package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.api.prompt.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.*;
import com.openstudy.domainlens.security.PolicyEngine;
import java.util.*;

/** Adds bounded approved formula contracts to the existing protected prompt. No consumer names. */
public final class FormulaPlanning {
    private FormulaPlanning() {}
    public static PromptPackage supply(PromptPackage prompt,FormulaRegistry formulas,IrValidator validator,UserContext user){return supply(prompt,formulas,validator,user,true);}
    /** A grounded request already owns its meaning; never revive excluded few-shot literals. */
    public static PromptPackage supply(PromptPackage prompt,FormulaRegistry formulas,IrValidator validator,UserContext user,boolean includeExamples){
        var policies=validator.policies();var visible=new TreeSet<String>();prompt.data().context().stream().filter(n->n.kind().equals("metric")).forEach(n->visible.add(n.id()));prompt.data().analyses().forEach(a->visible.add(a.id()));
        var contracts=new ArrayList<FormulaDefinition.Contract>();
        for(String id:visible){if(!formulas.contains(id))continue;var f=formulas.get(id);f.baseSources().forEach(s->policies.source(s,user));f.dependencies().forEach(field->policies.field(field,"search",user));var contract=formulas.contract(id);var safe=ContractJson.read(ContractJson.write(PromptService.safe(ContractJson.read(ContractJson.write(contract),Object.class))),FormulaDefinition.Contract.class);
            com.openstudy.domainlens.api.ir.ContractRules.require(contract.id().equals(safe.id()) && contract.version().equals(safe.version()) && contract.hash().equals(safe.hash()),"Protected formula identity changed");contracts.add(safe);}
        var d=prompt.data();var examples=new ArrayList<PromptPackage.Example>(includeExamples?d.examples():List.of());
        var allowed=Set.copyOf(prompt.contextRefs());
        if(includeExamples)for(var example:formulas.examples()) {
            if(examples.size()>=3)break;
            var selected=new HashSet<String>();for(var q:example.ir().queries()){q.metrics().forEach(m->selected.add(m.field()));if(q.analysisId()!=null)selected.add(q.analysisId());}
            if(selected.isEmpty() || !visible.containsAll(selected) || !allowed.containsAll(example.ir().contextRefs()) || examples.stream().anyMatch(e->e.id().equals(example.id())))continue;
            try {
                PromptService.requireReferences(example.ir(),allowed,d.analyses());validator.validate(example.ir(),allowed,user);var schemas=new LinkedHashMap<String,List<TypedDataset.Column>>();
                for(var q:example.ir().queries())if(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS)schemas.putAll(validator.analysisDefinition(q.analysisId()).outputs());else schemas.put(q.id(),QueryPipeline.schema(formulas.semantic(),q));
                prompt.components().validate(example.ir().view(),schemas);
                var protectedExample=ContractJson.read(ContractJson.write(PromptService.safe(ContractJson.read(ContractJson.write(example),Object.class))),PromptPackage.Example.class);
                validator.validate(protectedExample.ir(),allowed,user);prompt.components().validate(protectedExample.ir().view(),schemas);examples.add(protectedExample);
            }catch(com.openstudy.domainlens.api.error.EngineException ex){if(ex.code()!=com.openstudy.domainlens.api.error.EngineErrorCode.SCOPE_DENIED && ex.code()!=com.openstudy.domainlens.api.error.EngineErrorCode.PII_DENIED)throw ex;}
        }
        var data=new PromptPackage.Data(d.question(),d.asOf(),d.calendarZone(),d.displayZone(),d.snapshotVersion(),d.context(),d.analyses(),examples,d.previous(),contracts,formulas.mixedPlanning()?com.openstudy.domainlens.api.planning.SemanticPlan.MIXED_VERSION:null);
        return new PromptPackage(prompt.promptVersion(),prompt.systemInstructions(),data,prompt.irSchema(),prompt.viewSchema(),prompt.components());
    }
    public static void validateInputs(FormulaRegistry formulas,QuerySpec query){for(var selection:query.metrics())if(formulas.contains(selection.field()))validateInput(formulas.get(selection.field()),query.filter());}
    private static void validateInput(FormulaDefinition formula,Filter filter){
        if(filter instanceof Filter.Group group){group.children().forEach(child->validateInput(formula,child));return;}
        if(filter instanceof Filter.Leaf leaf){var input=formula.inputs().get(leaf.field());com.openstudy.domainlens.api.ir.ContractRules.require(input!=null && input.operators().contains(leaf.operator()),"Unapproved formula input");for(Object value:leaf.values()){IrValidator.value(input.type(),value);com.openstudy.domainlens.api.ir.ContractRules.require(input.values().isEmpty() || input.values().contains(value),"Unapproved formula input value");}}
    }
    public static List<FormulaDefinition.Binding> bindings(List<com.openstudy.domainlens.api.work.ExecutionPlan> plans,List<TypedDataset> datasets){
        var result=new ArrayList<FormulaDefinition.Binding>();for(var p:plans)for(var d:datasets)if(p.query().id().equals(d.id())){
            if(p.query().mode()==QuerySpec.Mode.REGISTERED_ANALYSIS && !p.formulas().isEmpty())for(var c:d.columns())result.add(new FormulaDefinition.Binding(d.id(),c.alias(),p.formulas().getFirst()));
            else for(var s:p.query().metrics())p.formulas().stream().filter(f->f.id().equals(s.field())).findFirst().ifPresent(f->result.add(new FormulaDefinition.Binding(d.id(),s.alias(),f)));
        }return List.copyOf(result);
    }
}
