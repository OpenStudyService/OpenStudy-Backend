package com.openstudy.domainlens.api.formula;

import com.openstudy.domainlens.api.ir.*;
import java.util.*;

/** Immutable approved calculation, query lowering and UI bindings under one stable id. */
public record FormulaDefinition(String id,String version,Kind kind,String source,String grain,
        String description,Set<String> discoverySources,Set<String> baseSources,
        List<String> dependencies,Map<String,Input> inputs,OperationIr expression,
        List<OperationIr.Stage> stages,List<Output> outputs,List<Presentation> presentations,
        String narrativeTemplate,List<String> joinPath,Filter defaultFilter,List<String> aliases) {
    public static final String FORMAT="formula-ir-1", FORMAT_V2="formula-ir-2";
    public enum Kind { METRIC, ANALYSIS }
    public record Input(ValueType type,Set<Filter.Operator> operators,List<String> policyFields,List<String> values) {
        public Input {operators=Set.copyOf(operators);policyFields=List.copyOf(policyFields);values=List.copyOf(values);}
    }
    public record Output(String id,String grain,List<TypedDataset.Column> columns,OperationIr.Select query,
            boolean summary,int maxRows,List<String> uniqueKeys,Map<String,String> sortColumns,Boolean limitByQuery) {
        public Output {limitByQuery=Boolean.TRUE.equals(limitByQuery);columns=List.copyOf(columns);uniqueKeys=List.copyOf(uniqueKeys);sortColumns=Map.copyOf(sortColumns);}
        public Output(String id,String grain,List<TypedDataset.Column> columns,OperationIr.Select query,boolean summary,int maxRows,List<String> uniqueKeys,Map<String,String> sortColumns){this(id,grain,columns,query,summary,maxRows,uniqueKeys,sortColumns,false);}
    }
    public record Presentation(String component,String output,Map<String,Object> bindings,Map<String,OperationIr> derived) {
        public Presentation {bindings=Collections.unmodifiableMap(new LinkedHashMap<>(bindings));derived=derived==null?Map.of():Map.copyOf(derived);}
        public Presentation(String component,String output,Map<String,Object> bindings){this(component,output,bindings,Map.of());}
    }
    public FormulaDefinition {discoverySources=Set.copyOf(discoverySources);baseSources=Set.copyOf(baseSources);
        dependencies=List.copyOf(dependencies);inputs=Map.copyOf(inputs);stages=List.copyOf(stages);
        outputs=List.copyOf(outputs);presentations=List.copyOf(presentations);joinPath=joinPath==null?List.of():List.copyOf(joinPath);aliases=aliases==null?List.of():List.copyOf(aliases);}
    public FormulaDefinition(String id,String version,Kind kind,String source,String grain,String description,Set<String> discoverySources,Set<String> baseSources,List<String> dependencies,Map<String,Input> inputs,OperationIr expression,List<OperationIr.Stage> stages,List<Output> outputs,List<Presentation> presentations,String narrativeTemplate){this(id,version,kind,source,grain,description,discoverySources,baseSources,dependencies,inputs,expression,stages,outputs,presentations,narrativeTemplate,List.of(),null,List.of());}
    public FormulaDefinition(String id,String version,Kind kind,String source,String grain,String description,Set<String> discoverySources,Set<String> baseSources,List<String> dependencies,Map<String,Input> inputs,OperationIr expression,List<OperationIr.Stage> stages,List<Output> outputs,List<Presentation> presentations,String narrativeTemplate,List<String> joinPath,Filter defaultFilter){this(id,version,kind,source,grain,description,discoverySources,baseSources,dependencies,inputs,expression,stages,outputs,presentations,narrativeTemplate,joinPath,defaultFilter,List.of());}
    public FormulaDefinition withAliases(List<String> names){return new FormulaDefinition(id,version,kind,source,grain,description,discoverySources,baseSources,dependencies,inputs,expression,stages,outputs,presentations,narrativeTemplate,joinPath,defaultFilter,names);}
    /** Outbound projection has no physical paths, row data or executable expressions. */
    public record Contract(String id,String version,String hash,Kind kind,String grain,String description,
            Map<String,Input> inputs,List<OutputContract> outputs,List<Presentation> presentations,String narrativeTemplate,List<String> aliases) {
        public Contract {aliases=aliases==null?List.of():List.copyOf(aliases);}
        public Contract(String id,String version,String hash,Kind kind,String grain,String description,Map<String,Input> inputs,List<OutputContract> outputs,List<Presentation> presentations,String narrativeTemplate){this(id,version,hash,kind,grain,description,inputs,outputs,presentations,narrativeTemplate,List.of());}
    }
    public record OutputContract(String id,String grain,List<TypedDataset.Column> columns) {}
    public record Ref(String id,String version,String hash) {}
    public record DisplayValues(String datasetId,String slot,List<java.math.BigDecimal> values,Ref formula) {public DisplayValues{values=Collections.unmodifiableList(new ArrayList<>(values));}}
    public record Binding(String datasetId,String column,Ref formula) {}
}
