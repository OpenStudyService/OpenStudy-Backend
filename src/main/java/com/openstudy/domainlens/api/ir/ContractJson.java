package com.openstudy.domainlens.api.ir;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import com.openstudy.domainlens.api.error.*;
import java.util.*;

/** General persistence codec plus explicit, strict public-contract entry points. */
public final class ContractJson {
    private static final JsonMapper MAPPER=JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final JsonMapper STRICT=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private ContractJson() {}
    public static String write(Object value) {
        if(value instanceof IrDocument ir && IrDocument.LEGACY_VERSION.equals(ir.schemaVersion()) && ir.reasonCode()==null) {
            // Preserve the exact legacy root shape when replaying captured provider contracts.
            var fields=new LinkedHashMap<String,Object>();
            fields.put("schemaVersion",ir.schemaVersion());fields.put("status",ir.status());fields.put("clarification",ir.clarification());fields.put("clarificationOptions",ir.clarificationOptions());fields.put("unsupportedReason",ir.unsupportedReason());fields.put("queries",ir.queries());fields.put("view",ir.view());fields.put("contextRefs",ir.contextRefs());
            return MAPPER.writeValueAsString(fields);
        }
        return MAPPER.writeValueAsString(value);
    }
    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json,type); }
        catch (RuntimeException ex) { throw invalid(); }
    }
    /** Strict resource/persistence decoding with exact decimal operands, without a public wire schema. */
    public static <T> T readExact(String json,Class<T> type){try{return STRICT.readValue(json,type);}catch(RuntimeException ex){throw invalid();}}
    private static <T> T contract(String json,Class<T> type,Map<String,Object> schema) {
        try {
            Object raw=STRICT.readValue(json,Object.class);
            ContractSchema.validate(raw,schema);
            return STRICT.readValue(json,type);
        }catch(EngineException ex){throw ex;}
        catch(RuntimeException ex){throw invalid();}
    }
    /** Strict object decoding against a server-owned schema; rejects duplicate and trailing tokens. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(String json, Map<String,Object> schema) {
        return contract(json,Map.class,schema);
    }
    public static IrDocument document(String json) {
        Map<?,?> raw=readExact(json,Map.class);
        if(raw==null)throw invalid();
        var schema=IrDocument.LEGACY_VERSION.equals(raw.get("schemaVersion"))?ContractSchemas.legacyIr():ContractSchemas.ir();
        var result=contract(json,IrDocument.class,schema);ContractRules.validate(result);return result;
    }
    public static ViewSpec view(String json) {
        var result=contract(json,ViewSpec.class,ContractSchemas.view());ContractRules.viewStructure(result);return result;
    }
    public static ComponentCatalog catalog(String json) {return contract(json,ComponentCatalog.class,ContractSchemas.catalog());}
    public static ResultEnvelope result(String json) {
        try {
            var raw=contract(json,ResultEnvelope.class,ContractSchemas.result());
            var decoded=new ResultEnvelope(raw.requestId(),raw.executionId(),raw.status(),raw.datasets().stream().map(ResultRules::decode).toList(),
                    raw.view(),raw.warnings(),raw.errorCode(),raw.asOf(),raw.snapshotVersion(),raw.policyVersion(),raw.formulaBindings(),raw.displayValues(),raw.notice());
            ResultRules.envelope(decoded);return decoded;
        }catch(EngineException ex){throw ex;}
        catch(RuntimeException ex){throw invalid();}
    }
    /** Stable representation for contract activation fingerprints, including unordered sets and all metadata. */
    public static String canonical(Object value) {return write(ordered(value));}
    private static Object ordered(Object value) {
        if(value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();map.forEach((key,item)->sorted.put(key.toString(),ordered(item)));return sorted;
        }
        if(value instanceof Set<?> set)return set.stream().map(ContractJson::ordered).sorted(Comparator.comparing(ContractJson::write)).toList();
        if(value instanceof Collection<?> list)return list.stream().map(ContractJson::ordered).toList();
        if(value!=null && value.getClass().isRecord()) {
            var fields=new TreeMap<String,Object>();
            for(var field:value.getClass().getRecordComponents())try{fields.put(field.getName(),ordered(field.getAccessor().invoke(value)));}
            catch(ReflectiveOperationException ex){throw new IllegalArgumentException("Unreadable contract",ex);}
            return fields;
        }
        return value;
    }
    private static EngineException invalid(){return new EngineException(EngineErrorCode.IR_INVALID,"Invalid JSON contract");}
}
