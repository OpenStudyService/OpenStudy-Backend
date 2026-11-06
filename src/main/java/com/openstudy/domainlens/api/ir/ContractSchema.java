package com.openstudy.domainlens.api.ir;

import java.math.BigDecimal;
import java.util.*;

/** Evaluates the deliberately small JSON Schema vocabulary used by our bundled contracts.
 * No remote references or consumer-supplied schemas are loaded. Business rules remain in ContractRules.
 */
final class ContractSchema {
    private ContractSchema() {}
    static void validate(Object value, Map<String,Object> schema) {
        ContractRules.require(matches(value, schema, schema), "JSON does not match contract schema");
    }
    @SuppressWarnings("unchecked")
    private static boolean matches(Object v, Map<String,Object> s, Map<String,Object> root) {
        if (s.containsKey("$ref")) {
            String ref=(String)s.get("$ref");
            if (!ref.startsWith("#/$defs/")) throw new IllegalArgumentException("Local schema references only");
            var target=((Map<String,Map<String,Object>>)root.get("$defs")).get(ref.substring(8));
            if(target==null)throw new IllegalArgumentException("Unknown schema reference");
            if(!matches(v,target,root))return false;
        }
        for(String key:List.of("anyOf","oneOf")) if(s.containsKey(key)) {
            long count=((List<Map<String,Object>>)s.get(key)).stream().filter(branch->matches(v,branch,root)).count();
            if(count==0 || (key.equals("oneOf") && count!=1))return false;
        }
        if(s.containsKey("const") && !Objects.equals(v,s.get("const")))return false;
        if(s.containsKey("enum") && !((List<?>)s.get("enum")).contains(v))return false;
        Object type=s.get("type");
        if(type instanceof String t && !type(v,t))return false;
        if(type instanceof List<?> ts && ts.stream().noneMatch(t->type(v,(String)t)))return false;
        if(v instanceof Map<?,?> m) {
            var properties=(Map<String,Map<String,Object>>)s.getOrDefault("properties",Map.of());
            if(!m.keySet().containsAll((List<?>)s.getOrDefault("required",List.of())))return false;
            for(var e:m.entrySet()) {
                var property=properties.get(e.getKey());
                if(property!=null) {if(!matches(e.getValue(),property,root))return false;}
                else if(Boolean.FALSE.equals(s.get("additionalProperties")))return false;
                else if(s.get("additionalProperties") instanceof Map<?,?> extra && !matches(e.getValue(),(Map<String,Object>)extra,root))return false;
            }
        }
        if(v instanceof List<?> list) {
            if(list.size()<number(s,"minItems",0) || list.size()>number(s,"maxItems",Integer.MAX_VALUE))return false;
            if(Boolean.TRUE.equals(s.get("uniqueItems")) && new HashSet<>(list).size()!=list.size())return false;
            if(s.get("items") instanceof Map<?,?> item)for(Object x:list)if(!matches(x,(Map<String,Object>)item,root))return false;
        }
        if(v instanceof String str) {
            int length=str.codePointCount(0,str.length());
            if(length<number(s,"minLength",0) || length>number(s,"maxLength",Integer.MAX_VALUE))return false;
            if(s.get("pattern") instanceof String pattern && !java.util.regex.Pattern.compile(pattern).matcher(str).find())return false;
        }
        if(v instanceof Number n) {
            BigDecimal decimal;
            try {decimal=new BigDecimal(n.toString());}catch(NumberFormatException ex){return false;}
            if(s.get("minimum") instanceof Number min && decimal.compareTo(new BigDecimal(min.toString()))<0)return false;
            if(s.get("maximum") instanceof Number max && decimal.compareTo(new BigDecimal(max.toString()))>0)return false;
        }
        return true;
    }
    private static int number(Map<String,Object> s,String name,int fallback){return s.get(name) instanceof Number n?n.intValue():fallback;}
    private static boolean type(Object v,String type) {
        return switch(type) {
            case "null" -> v==null;
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger;
            default -> throw new IllegalArgumentException("Unsupported schema type: "+type);
        };
    }
}
