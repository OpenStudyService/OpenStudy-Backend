package com.openstudy.domainlens.ai;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.prompt.PromptPackage;
import java.util.*;

/** Narrows the structural generation contract to the already-approved prompt, never to a question's answer. */
final class PromptOutputSchema {
    private final Map<String,Object> schema;
    private final Map<String,Object> defs;
    private final Map<String,Object> query;
    private final Map<String,String> filters = new LinkedHashMap<>();
    private final Map<String,String> leaves = new LinkedHashMap<>();

    private PromptOutputSchema(PromptPackage prompt) {
        schema=copy(prompt.irSchema()); defs=map(schema.get("$defs")); query=map(defs.get("query"));
    }
    static Map<String,Object> from(PromptPackage prompt) { return new PromptOutputSchema(prompt).build(prompt); }

    private Map<String,Object> build(PromptPackage prompt) {
        var fields=new TreeMap<String,PromptPackage.Input>();
        var sources=new TreeMap<String,String>();
        var metrics=new TreeMap<String,List<String>>();
        for(var node:prompt.data().context()) {
            var a=node.attributes();
            switch(node.kind()) {
                case "entity" -> sources.put(node.id(),(String)a.get("grain"));
                case "field" -> {
                    var ops=new HashSet<Filter.Operator>();
                    for(Object op:(Collection<?>)a.getOrDefault("operations",List.of()))
                        for(var allowed:Filter.Operator.values())if(allowed.name().equals(op.toString()))ops.add(allowed);
                    fields.put(node.id(),new PromptPackage.Input(ValueType.valueOf((String)a.get("type")),ops));
                }
                case "metric" -> metrics.computeIfAbsent(a.get("source")+"\n"+a.get("grain"),ignored->new ArrayList<>()).add(node.id());
                default -> { }
            }
        }
        String ordinaryFilter=filter(fields);
        var variants=new ArrayList<Map<String,Object>>();
        if(!fields.isEmpty()) for(var source:sources.entrySet()) {
            var q=variant("DETAIL",source.getKey(),source.getValue(),null,ordinaryFilter);
            var properties=map(q.get("properties"));
            properties.put("dimensions",selections(fields.keySet(),1));
            properties.put("metrics",emptyArray());
            variants.add(q);
        }
        for(var group:metrics.entrySet()) {
            var source=group.getKey().split("\n",2);
            if(!sources.containsKey(source[0]))continue;
            var q=variant("AGGREGATE",source[0],source[1],null,ordinaryFilter);
            var properties=map(q.get("properties"));
            properties.put("dimensions",fields.isEmpty()?emptyArray():selections(fields.keySet(),0));
            properties.put("metrics",selections(group.getValue(),1)); variants.add(q);
        }
        if(variants.isEmpty() && !prompt.data().analyses().isEmpty()) {
            var components=new ArrayList<Map<String,Object>>();
            var originals=(List<?>)map(defs.get("component")).get("oneOf");
            for(int i=0;i<prompt.components().components().size();i++) {
                var definition=prompt.components().components().get(i);
                for(var analysis:prompt.data().analyses())for(var output:analysis.outputs().entrySet()) {
                    int rows=analysis.maxRows().getOrDefault(output.getKey(),100);
                    var bindings=new LinkedHashMap<String,Object>();boolean compatible=rows<=definition.limits().maxRows();
                    for(var slot:definition.dataSlots().entrySet()) {
                        var aliases=output.getValue().stream().filter(c->slot.getValue().types().contains(c.type())).map(TypedDataset.Column::alias).toList();
                        if(slot.getValue().required() && (aliases.isEmpty() || rows>slot.getValue().maxRows())){compatible=false;break;}
                        Object allowed=aliases.isEmpty()?Map.of("type","null"):slot.getValue().multiple()?Map.of("type","array","items",strings(aliases),"minItems",1,"maxItems",aliases.size()):strings(aliases);
                        bindings.put(slot.getKey(),allowed);
                    }
                    if(!compatible)continue;
                    var component=copy(map(originals.get(i)));var properties=map(component.get("properties"));
                    properties.put("datasetId",Map.of("const",output.getKey()));
                    properties.put("bindings",object(bindings));components.add(component);
                }
            }
            if(!components.isEmpty())defs.put("component",Map.of("oneOf",components));
        }
        for(var analysis:prompt.data().analyses()) {
            var q=variant("REGISTERED_ANALYSIS",analysis.source(),analysis.grain(),analysis.id(),filter(analysis.inputs()));
            var properties=map(q.get("properties"));
            // Query identity is independent of the registered output dataset IDs.
            // Pinning it here eliminates this branch as soon as a model names its query.
            properties.put("dimensions",emptyArray());properties.put("metrics",emptyArray());variants.add(q);
        }
        var root=map(schema.get("properties"));
        if(variants.isEmpty())root.put("queries",emptyArray());
        else defs.put("query",Map.of("anyOf",variants));
        root.put("contextRefs",prompt.contextRefs().isEmpty()?emptyArray():Map.of("type","array","items",strings(prompt.contextRefs())));
        var used=new HashSet<String>();var pending=new ArrayDeque<String>();
        schema.forEach((key,value)->{if(!key.equals("$defs"))references(value,pending);});
        while(!pending.isEmpty()){String id=pending.removeFirst();if(used.add(id))references(defs.get(id),pending);}
        defs.keySet().retainAll(used);
        return schema;
    }
    private static void references(Object value,Deque<String> pending){
        if(value instanceof Map<?,?> map){if(map.get("$ref") instanceof String ref && ref.startsWith("#/$defs/"))pending.add(ref.substring(8));map.values().forEach(v->references(v,pending));}
        else if(value instanceof Collection<?> list)list.forEach(v->references(v,pending));
    }
    private Map<String,Object> variant(String mode,String source,String grain,String analysisId,String filter) {
        var q=copy(query);var p=map(q.get("properties"));
        p.put("mode",Map.of("const",mode));p.put("source",Map.of("const",source));p.put("grain",Map.of("const",grain));
        p.put("analysisId",analysisId==null?Map.of("type","null"):Map.of("const",analysisId));
        p.put("filter",filter==null?Map.of("type","null"):Map.of("anyOf",List.of(ref(filter),Map.of("type","null"))));
        // Ordered constrained decoders must choose the operation before a source token
        // eliminates otherwise valid registered or aggregate branches.
        var ordered=new LinkedHashMap<String,Object>();
        for(String key:List.of("mode","analysisId","source","grain","id"))ordered.put(key,p.get(key));
        p.forEach(ordered::putIfAbsent);q.put("properties",ordered);
        return q;
    }
    private Map<String,Object> selections(Collection<String> ids,int minimum) {
        var selection=copy(map(defs.get("selection")));map(selection.get("properties")).put("field",strings(ids));
        return Map.of("type","array","items",selection,"minItems",minimum);
    }
    private String filter(Map<String,PromptPackage.Input> inputs) {
        String signature=ContractJson.canonical(inputs);
        if(filters.containsKey(signature))return filters.get(signature);
        var branches=new ArrayList<Map<String,Object>>();
        for(var input:new TreeMap<>(inputs).entrySet()) {
            var scalar=scalar(input.getValue().type());
            var ops=input.getValue().operators().stream()
                    .filter(op->!(input.getValue().type()==ValueType.TIMESTAMP && op==Filter.Operator.BETWEEN))
                    .map(Enum::name).sorted().toList();
            if(ops.isEmpty())continue;
            var leafProperties=new LinkedHashMap<String,Object>();
            leafProperties.put("kind",Map.of("const","leaf"));leafProperties.put("field",Map.of("const",input.getKey()));
            leafProperties.put("operator",strings(ops));leafProperties.put("values",Map.of("type","array","items",scalar,"maxItems",50));
            var leaf=object(leafProperties);
            String leafKey=ContractJson.canonical(leaf);
            String name=leaves.computeIfAbsent(leafKey,ignored->"approvedLeaf"+leaves.size());
            defs.put(name,leaf);branches.add(ref(name));
        }
        if(branches.isEmpty())return null;
        String name=filters.isEmpty()?"filter":"approvedFilter"+filters.size();
        filters.put(signature,name);
        // Every depth accepts the same leaf union. Share that union without dropping
        // approved fields/operators or changing the maximum group nesting depth.
        String leafUnion=name+"Leaves";
        defs.put(leafUnion,Map.of("anyOf",List.copyOf(branches)));
        for(int depth=4;depth>=1;depth--) {
            String current=depth==1?name:name+"Depth"+depth;
            var alternatives=new ArrayList<Map<String,Object>>();alternatives.add(ref(leafUnion));
            if(depth<4) {
                // Constrained decoders may enforce property order. Both branches must start
                // with their discriminator; Map.of iteration order can force a leaf instead.
                var groupProperties=new LinkedHashMap<String,Object>();
                groupProperties.put("kind",Map.of("const","group"));groupProperties.put("junction",strings(List.of("AND","OR")));
                groupProperties.put("children",Map.of("type","array","items",ref(name+"Depth"+(depth+1)),"minItems",1,"maxItems",16));
                alternatives.add(object(groupProperties));
            }
            defs.put(current,Map.of("anyOf",alternatives));
        }
        return name;
    }

    private static Map<String,Object> scalar(ValueType type) {
        return Map.of("type",switch(type){case INTEGER->"integer";case DECIMAL->"number";case BOOLEAN->"boolean";default->"string";});
    }
    private static Map<String,Object> emptyArray(){return Map.of("type","array","items",Map.of("type","string"),"maxItems",0);}
    private static Map<String,Object> strings(Collection<String> values){return Map.of("type","string","enum",values.stream().distinct().sorted().toList());}
    private static Map<String,Object> ref(String name){return Map.of("$ref","#/$defs/"+name);}
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false);}
    private static Map<String,Object> copy(Map<String,Object> input){return map(ContractJson.read(ContractJson.write(input),Map.class));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
}
