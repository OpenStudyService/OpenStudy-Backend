package com.openstudy.domainlens.ai;
import com.openstudy.domainlens.api.planning.*;
import com.openstudy.domainlens.api.ir.*;
import java.util.*;

/** Small provider schema, narrowed to approved visible IDs. */
final class SemanticPlanSchema {
    private SemanticPlanSchema() {}
    static Map<String,Object> from(PlanningPrompt prompt) {
        var fields=prompt.context().stream().filter(n->n.kind().equals("field")).map(n->n.id()).sorted().toList();
        var metrics=prompt.context().stream().filter(n->n.kind().equals("metric")).map(n->n.id()).sorted().toList();
        var sources=prompt.context().stream().filter(n->n.kind().equals("entity")).map(n->n.id()).sorted().toList();
        var analyses=prompt.analyses().stream().map(a->a.id()).sorted().toList();
        var sort=object(Map.of("field",strings(java.util.stream.Stream.concat(fields.stream(),metrics.stream()).toList()),"direction",strings(List.of("ASC","DESC"))));
        var proofSlots=new TreeSet<String>();proofSlots.addAll(fields);proofSlots.addAll(metrics);proofSlots.addAll(sources);proofSlots.addAll(analyses);proofSlots.addAll(List.of("filter","order","page","size","limit","total","presentation"));
        var evidence=object(Map.of("slot",strings(List.copyOf(proofSlots)),"origin",strings(List.of("QUESTION","PREVIOUS","CONTRACT")),"quote",Map.of("type","string","maxLength",256)));
        var request=new LinkedHashMap<String,Object>();
        request.put("kind",strings(List.of("DETAIL","AGGREGATE","REGISTERED_ANALYSIS")));
        request.put("source",nullable(strings(sources)));request.put("analysisId",nullable(strings(analyses)));
        request.put("metrics",ids(metrics));request.put("dimensions",ids(fields));
        request.put("filter",nullable(Map.of("$ref","#/$defs/filter")));
        request.put("order",array(sort,0,8));
        request.put("page",nullable(Map.of("type","integer","minimum",0,"maximum",10000)));
        request.put("size",nullable(Map.of("type","integer","minimum",1,"maximum",100)));
        request.put("limit",nullable(Map.of("type","integer","minimum",1,"maximum",50)));
        request.put("total",Map.of("type","boolean"));request.put("presentations",ids(prompt.presentations()));
        request.put("evidence",array(evidence,0,30));
        request.put("formulas",array(Map.of("type","null"),0,0));
        if(!prompt.formulas().isEmpty()) {
            var choices=prompt.formulas().stream().map(f->object(Map.of("id",Map.of("const",f.id()),"version",Map.of("const",f.version()),"hash",Map.of("const",f.hash())))).toList();
            request.put("formulas",array(Map.of("anyOf",choices),0,16));
        }
        var variants=new ArrayList<Map<String,Object>>();
        if(!fields.isEmpty() && !sources.isEmpty()) {
            var detail=new LinkedHashMap<>(request);detail.put("kind",strings(List.of("DETAIL")));detail.put("source",strings(sources));
            detail.put("formulas",array(Map.of("type","null"),0,0));detail.put("analysisId",Map.of("type","null"));detail.put("metrics",array(Map.of("type","null"),0,0));detail.put("dimensions",array(strings(fields),1,Math.min(fields.size(),16)));detail.put("total",Map.of("const",false));variants.add(object(detail));
        }
        if(!metrics.isEmpty()) {
            var aggregate=new LinkedHashMap<>(request);aggregate.put("kind",strings(List.of("AGGREGATE")));aggregate.put("analysisId",Map.of("type","null"));
            aggregate.put("metrics",array(strings(metrics),1,Math.min(metrics.size(),16)));
            if(!prompt.formulas().isEmpty()){var choices=prompt.formulas().stream().filter(f->f.kind()==com.openstudy.domainlens.api.formula.FormulaDefinition.Kind.METRIC).map(SemanticPlanSchema::formula).toList();aggregate.put("formulas",choices.isEmpty()?array(Map.of("type","null"),0,0):array(Map.of("anyOf",choices),1,Math.min(choices.size(),16)));}
            variants.add(object(aggregate));
        }
        for(String id:analyses) {
            var analysis=new LinkedHashMap<>(request);analysis.put("kind",strings(List.of("REGISTERED_ANALYSIS")));analysis.put("analysisId",strings(List.of(id)));
            analysis.put("formulas",array(Map.of("type","null"),0,0));
            prompt.formulas().stream().filter(f->f.id().equals(id)).findFirst().ifPresent(f->analysis.put("formulas",array(formula(f),1,1)));
            analysis.put("metrics",array(Map.of("type","null"),0,0));analysis.put("dimensions",array(Map.of("type","null"),0,0));analysis.put("total",Map.of("const",false));variants.add(object(analysis));
        }
        Map<String,Object> requests=variants.isEmpty()?array(Map.of("type","null"),0,0):array(Map.of("anyOf",variants),0,8);
        var root=object(Map.of("version",Map.of("const",prompt.version()),"status",strings(List.of("READY","NEEDS_CLARIFICATION","UNSUPPORTED")),
            "reasonCode",nullable(strings(Arrays.stream(ReasonCode.values()).map(Enum::name).toList())),"reason",nullable(Map.of("type","string","maxLength",400)),"options",array(object(Map.of("id",Map.of("type","string"),"label",Map.of("type","string","maxLength",120))),0,3),
            "requests",requests,"unresolved",array(Map.of("type","string","maxLength",160),0,10)));
        var filter=(Map<String,Object>)((Map<?,?>)ContractSchemas.ir().get("$defs")).get("filter");
        root.put("$defs",new LinkedHashMap<>(Map.of("filter",filter)));
        return StructuredOutputSchema.planning(root);
    }
    static Map<String,Object> formula(com.openstudy.domainlens.api.formula.FormulaDefinition.Contract f){return object(Map.of("id",Map.of("const",f.id()),"version",Map.of("const",f.version()),"hash",Map.of("const",f.hash())));}
    static Map<String,Object> object(Map<String,Object> properties){return new LinkedHashMap<>(Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false));}
    static Map<String,Object> strings(List<String> values){return values.isEmpty()?Map.of("type","null"):Map.of("type","string","enum",values);}
    static Map<String,Object> nullable(Map<String,Object> value){return Map.of("anyOf",List.of(value,Map.of("type","null")));}
    static Map<String,Object> ids(List<String> values){return array(strings(values),0,values.isEmpty()?0:Math.min(values.size(),16));}
    static Map<String,Object> array(Map<String,Object> item,int min,int max){return Map.of("type","array","items",item,"minItems",min,"maxItems",max);}
}
