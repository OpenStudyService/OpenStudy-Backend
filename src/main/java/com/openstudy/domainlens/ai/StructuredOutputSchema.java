package com.openstudy.domainlens.ai;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.prompt.PromptPackage;
import java.util.*;

/** Provider wire schema only. The public IR schema remains the final structural authority. */
final class StructuredOutputSchema {
    private StructuredOutputSchema() {}

    static Map<String,Object> planning(Map<String,Object> schema) {
        @SuppressWarnings("unchecked") var ordered=(Map<String,Object>)ContractJson.read(ContractJson.canonical(schema),Map.class);
        return shortReferences(compact(adapt(ordered)));
    }

    static Map<String,Object> from(PromptPackage prompt) {
        return shortReferences(compact(adapt(PromptOutputSchema.from(prompt))));
    }

    /** Only private definition identifiers change; public IR properties and enum values stay intact. */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> shortReferences(Map<String,Object> schema) {
        var defs=(Map<String,Object>)schema.get("$defs");var names=new LinkedHashMap<String,String>();
        int index=0;
        for(String name:new TreeSet<>(defs.keySet()))if(name.length()>8) {
            String shortName;do{shortName="w"+index++;}while(defs.containsKey(shortName));
            names.put(name,shortName);
        }
        return (Map<String,Object>)renameReferences(schema,names);
    }
    private static Object renameReferences(Object value,Map<String,String> names) {
        if(value instanceof Map<?,?> map) {
            var copy=new LinkedHashMap<String,Object>();
            map.forEach((rawKey,child)->{
                String key=(String)rawKey;
                if(key.equals("$ref") && child instanceof String ref && ref.startsWith("#/$defs/"))
                    copy.put(key,"#/$defs/"+names.getOrDefault(ref.substring(8),ref.substring(8)));
                else if(key.equals("$defs") && child instanceof Map<?,?> defs) {
                    var renamed=new LinkedHashMap<String,Object>();
                    defs.forEach((name,node)->renamed.put(names.getOrDefault((String)name,(String)name),renameReferences(node,names)));
                    copy.put(key,renamed);
                } else copy.put(key,renameReferences(child,names));
            });return copy;
        }
        if(value instanceof List<?> list)return list.stream().map(child->renameReferences(child,names)).toList();
        return value;
    }

    /** Share repeated schema nodes so semantic repair retains room in the outbound byte budget. */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> compact(Map<String,Object> schema) {
        var counts=new HashMap<String,Integer>();countNodes(schema,counts);
        var shared=new LinkedHashMap<String,Object>();var names=new HashMap<String,String>();
        var result=(Map<String,Object>)shareNodes(schema,counts,names,shared,true);
        ((Map<String,Object>)result.get("$defs")).putAll(shared);return result;
    }
    private static boolean reusable(Map<?,?> map){return map.containsKey("type") || map.containsKey("anyOf");}
    private static void countNodes(Object value,Map<String,Integer> counts){
        if(value instanceof Map<?,?> map){if(reusable(map)){String key=ContractJson.canonical(map);if(key.length()>=64)counts.merge(key,1,Integer::sum);}map.values().forEach(child->countNodes(child,counts));}
        else if(value instanceof List<?> list)list.forEach(child->countNodes(child,counts));
    }
    private static Object shareNodes(Object value,Map<String,Integer> counts,Map<String,String> names,Map<String,Object> shared,boolean root){
        if(value instanceof Map<?,?> map){
            String key=!root && reusable(map)?ContractJson.canonical(map):"";
            if(counts.getOrDefault(key,0)>1){
                String name=names.get(key);
                if(name==null){name="wireShared"+names.size();names.put(key,name);shared.put(name,shareNodes(value,counts,names,shared,true));}
                return Map.of("$ref","#/$defs/"+name);
            }
            var copy=new LinkedHashMap<String,Object>();map.forEach((k,v)->copy.put((String)k,shareNodes(v,counts,names,shared,false)));return copy;
        }
        if(value instanceof List<?> list)return list.stream().map(child->shareNodes(child,counts,names,shared,false)).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> adapt(Map<String,Object> source) {
        if (source.containsKey("const")) return literal(source.get("const"));
        if (source.get("enum") instanceof List<?> values && values.stream().anyMatch(Map.class::isInstance))
            return Map.of("anyOf", values.stream().map(StructuredOutputSchema::literal).toList());
        var result = new LinkedHashMap<String,Object>();
        source.forEach((key,value) -> {
            switch (key) {
                // Unsupported uniqueness is still checked against the original contract after decoding.
                case "$schema", "$id", "uniqueItems", "required" -> { }
                case "$defs", "properties" -> {
                    var entries = new LinkedHashMap<String,Object>();
                    ((Map<String,Map<String,Object>>)value).forEach((name,schema) -> entries.put(name,adapt(schema)));
                    result.put(key,entries);
                }
                case "oneOf", "anyOf" -> result.put("anyOf", ((List<Map<String,Object>>)value).stream().map(StructuredOutputSchema::adapt).toList());
                case "items" -> result.put(key,adapt((Map<String,Object>)value));
                default -> result.put(key,value);
            }
        });
        if ("object".equals(source.get("type"))) {
            var properties = (Map<String,Object>)result.computeIfAbsent("properties", ignored -> new LinkedHashMap<>());
            var required = (List<String>)source.getOrDefault("required",List.of());
            properties.replaceAll((name,schema) -> required.contains(name) ? schema : Map.of("anyOf",List.of(schema,Map.of("type","null"))));
            result.put("required",new ArrayList<>(properties.keySet()));
            result.put("additionalProperties",false);
        }
        if ("array".equals(source.get("type")) && !result.containsKey("items")) {
            // The catalog's no-events array is constrained to zero elements.
            ContractRules.require(Objects.equals(source.get("maxItems"),0),"Unbounded model array schema");
            result.put("items",Map.of("type","string"));
        }
        if (!result.containsKey("type") && source.get("enum") instanceof List<?> values && !values.isEmpty())
            result.put("type",type(values.getFirst()));
        return result;
    }

    private static Map<String,Object> literal(Object value) {
        if (value instanceof Map<?,?> map) {
            var properties = new LinkedHashMap<String,Object>();
            map.forEach((key,item) -> properties.put((String)key,literal(item)));
            return Map.of("type","object","properties",properties,"required",new ArrayList<>(properties.keySet()),"additionalProperties",false);
        }
        return Map.of("type",type(value),"enum",List.of(value));
    }

    private static String type(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        throw new IllegalArgumentException("Unsupported model schema literal");
    }

    static IrDocument decode(String json, Map<String,Object> schema, ComponentCatalog catalog) {
        // Old captured IRs lack reasonCode. Only the legacy version may fill that new nullable wire slot.
        var raw=ContractJson.readExact(json,Map.class);
        if(IrDocument.LEGACY_VERSION.equals(raw.get("schemaVersion")) && !raw.containsKey("reasonCode")
            && schema.get("properties") instanceof Map<?,?> properties && properties.containsKey("reasonCode"))raw.put("reasonCode",null);
        var root = ContractJson.object(ContractJson.write(raw),schema);
        restoreOptionalFields(root,catalog);
        return ContractJson.document(ContractJson.write(root));
    }

    @SuppressWarnings("unchecked")
    private static void restoreOptionalFields(Map<String,Object> root, ComponentCatalog catalog) {
        if (!(root.get("view") instanceof Map<?,?> view)) return;
        for (var item : (List<Map<String,Object>>)view.get("components")) {
            var ref = (Map<String,Object>)item.get("componentRef");
            var definition = catalog.components().stream()
                    .filter(d -> d.id().equals(ref.get("id")) && d.version().equals(ref.get("version")))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown model component"));
            var options = (Map<String,Object>)item.get("options");
            definition.optionsSchema().properties().keySet().stream()
                    .filter(key -> !definition.optionsSchema().required().contains(key))
                    .forEach(key -> { if (options.get(key)==null) options.remove(key); });
            var bindings = (Map<String,Object>)item.get("bindings");
            definition.dataSlots().forEach((key,slot) -> {
                if (!slot.required() && bindings.get(key)==null) bindings.remove(key);
            });
        }
    }
}
