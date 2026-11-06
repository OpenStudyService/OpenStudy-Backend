package com.openstudy.domainlens.api.ir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON Schema artifacts for server validation and downstream model/UI adapters. */
public final class ContractSchemas {
    private ContractSchemas() {}
    public static Map<String,Object> ir(){return load("ir-1.1.schema.json");}
    public static Map<String,Object> legacyIr(){return load("ir-1.0.schema.json");}
    public static Map<String,Object> view(){return load("view-1.0.schema.json");}
    public static Map<String,Object> catalog(){return load("components-1.0.schema.json");}
    public static Map<String,Object> result(){return load("result-1.1.schema.json");}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> load(String name) {
        try(var stream=ContractSchemas.class.getResourceAsStream("/contracts/"+name)) {
            if(stream==null)throw new IllegalStateException("Missing contract schema: "+name);
            return ContractJson.read(new String(stream.readAllBytes(),StandardCharsets.UTF_8),Map.class);
        }catch(IOException ex){throw new IllegalStateException("Cannot load contract schema",ex);}
    }
    /** Standard JSON Schema; provider-specific Structured Outputs adaptation belongs to the gateway. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> model(ComponentCatalog catalog) {
        ContractRules.require(!catalog.components().isEmpty(),"Empty model catalog");
        var schema=ir();
        // Playback and existing IR producers may still emit 1.0; the document decoder enforces each version.
        var rootRequired=new ArrayList<String>((List<String>)schema.get("required"));rootRequired.remove("reasonCode");schema.put("required",rootRequired);
        ((Map<String,Object>)schema.get("properties")).put("schemaVersion",Map.of("enum",List.of("1.0","1.1")));
        var defs=(Map<String,Object>)schema.get("$defs");
        var variants=new ArrayList<Map<String,Object>>();
        for(var definition:catalog.components()) {
            var component=ContractJson.read(ContractJson.write(defs.get("component")),Map.class);
            var properties=(Map<String,Object>)component.get("properties");
            properties.put("componentRef",Map.of("const",Map.of("id",definition.id(),"version",definition.version())));
            properties.put("options",definition.optionsSchema().jsonSchema());
            var slots=new TreeMap<String,Object>();var required=new ArrayList<String>();
            definition.dataSlots().forEach((id,slot)->{
                slots.put(id,slot.multiple()?Map.of("type","array","items",Map.of("type","string"),"minItems",1,"uniqueItems",true):Map.of("type","string"));
                if(slot.required())required.add(id);
            });
            properties.put("bindings",Map.of("type","object","properties",slots,"required",required.stream().sorted().toList(),"additionalProperties",false));
            var events=definition.events().stream().sorted(Comparator.comparing(ViewSpec.Event::event).thenComparing(ViewSpec.Event::actionId))
                    .map(e->Map.of("event",e.event(),"actionId",e.actionId())).toList();
            properties.put("events",events.isEmpty()?Map.of("type","array","maxItems",0):Map.of("type","array","items",Map.of("enum",events),"uniqueItems",true));
            variants.add(component);
        }
        // Share repeated property schemas without changing public values or validator constraints.
        // Keep variant/property containers inline for downstream output narrowing.
        var counts=new HashMap<String,Integer>();
        for(var variant:variants)((Map<String,Object>)variant.get("properties")).values().forEach(value->counts.merge(ContractJson.canonical(value),1,Integer::sum));
        var shared=new LinkedHashMap<String,String>();
        for(var variant:variants){
            var properties=(Map<String,Object>)variant.get("properties");
            for(var key:List.copyOf(properties.keySet())){
                var value=properties.get(key);String canonical=ContractJson.canonical(value);
                if(canonical.length()<64 || counts.get(canonical)<2)continue;
                String name=shared.get(canonical);
                if(name==null){name="uiProperty"+shared.size();shared.put(canonical,name);defs.put(name,value);}
                properties.put(key,Map.of("$ref","#/$defs/"+name));
            }
        }
        defs.put("component",Map.of("oneOf",variants));
        var view=(Map<String,Object>)defs.get("view");
        var versions=new TreeSet<>(catalog.compatibleVersions());versions.add(catalog.catalogVersion());
        ((Map<String,Object>)view.get("properties")).put("catalogVersion",versions.size()==1?Map.of("const",catalog.catalogVersion()):Map.of("enum",List.copyOf(versions)));
        return schema;
    }
}
