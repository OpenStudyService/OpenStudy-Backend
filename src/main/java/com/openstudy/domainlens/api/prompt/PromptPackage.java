package com.openstudy.domainlens.api.prompt;

import com.openstudy.domainlens.api.ir.*;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Outbound-only model input. Data text is never an instruction. No rows or token originals. */
public record PromptPackage(String promptVersion, String systemInstructions, Data data,
        Map<String,Object> irSchema, Map<String,Object> viewSchema, ComponentCatalog components) {
    public static final String VERSION = "1.1";
    public PromptPackage { irSchema=immutable(irSchema); viewSchema=immutable(viewSchema); }
    public record Data(String question, Instant asOf, String calendarZone, String displayZone,
            String snapshotVersion, List<Node> context, List<Analysis> analyses, List<Example> examples,
            @Nullable Previous previous,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Contract> formulas,@Nullable String planningVersion) {
        public Data { context=List.copyOf(context); analyses=List.copyOf(analyses); examples=List.copyOf(examples);formulas=formulas==null?List.of():List.copyOf(formulas); }
        public Data(String question,Instant asOf,String calendarZone,String displayZone,String snapshotVersion,List<Node> context,List<Analysis> analyses,List<Example> examples,Previous previous){this(question,asOf,calendarZone,displayZone,snapshotVersion,context,analyses,examples,previous,List.of());}
        public Data(String question,Instant asOf,String calendarZone,String displayZone,String snapshotVersion,List<Node> context,List<Analysis> analyses,List<Example> examples,Previous previous,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Contract> formulas){this(question,asOf,calendarZone,displayZone,snapshotVersion,context,analyses,examples,previous,formulas,null);}
    }
    public record Node(String id, String kind, Map<String,Object> attributes) {
        public Node { attributes=immutable(attributes); }
    }
    public record Analysis(String id, String version, String source, String grain,
            Map<String,List<TypedDataset.Column>> outputs, Map<String,Input> inputs, Map<String,Integer> maxRows,String description, List<com.openstudy.domainlens.api.formula.FormulaDefinition.Presentation> presentations, @Nullable Set<String> presentationEvents) {
        public Analysis(String id,String version,String source,String grain,Map<String,List<TypedDataset.Column>> outputs,Map<String,Input> inputs,Map<String,Integer> maxRows,String description,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Presentation> presentations){this(id,version,source,grain,outputs,inputs,maxRows,description,presentations,null);}
        public Analysis(String id,String version,String source,String grain,Map<String,List<TypedDataset.Column>> outputs,Map<String,Input> inputs,Map<String,Integer> maxRows,String description){this(id,version,source,grain,outputs,inputs,maxRows,description,List.of());}
        public Analysis(String id,String version,String source,String grain,Map<String,List<TypedDataset.Column>> outputs,Map<String,Input> inputs){this(id,version,source,grain,outputs,inputs,Map.of(),"");}
        public Analysis(String id,String version,String source,String grain,Map<String,List<TypedDataset.Column>> outputs,Map<String,Input> inputs,Map<String,Integer> maxRows){this(id,version,source,grain,outputs,inputs,maxRows,"");}
        public Analysis {
            var copy=new LinkedHashMap<String,List<TypedDataset.Column>>();
            outputs.forEach((dataset,columns)->copy.put(dataset,List.copyOf(columns)));outputs=Collections.unmodifiableMap(copy);
            presentations=presentations==null?List.of():List.copyOf(presentations);description=description==null?"":description;inputs=Map.copyOf(inputs);maxRows=maxRows==null?Map.of():Map.copyOf(maxRows);
            presentationEvents=presentationEvents==null?null:Set.copyOf(presentationEvents);
        }
    }
    public record Input(ValueType type, Set<Filter.Operator> operators) {
        public Input { operators=Set.copyOf(operators); }
    }
    public record Example(String id, String question, IrDocument ir) {
        public Example {
            if(ir.view()!=null) {
                var v=ir.view();var components=v.components().stream().map(c->new ViewSpec.Component(c.id(),c.componentRef(),c.datasetId(),immutable(c.bindings()),immutable(c.options()),c.events())).toList();
                ir=new IrDocument(ir.schemaVersion(),ir.status(),ir.clarification(),ir.clarificationOptions(),ir.unsupportedReason(),ir.queries(),new ViewSpec(v.catalogVersion(),components,v.layout()),ir.contextRefs(),ir.reasonCode());
            }
        }
    }
    public record Previous(UUID executionId, String datasetId, String source, String grain,
            @Nullable Filter filter, Instant asOf, List<String> semanticRefs, List<String> dimensions, List<String> metrics, @Nullable Boolean total) {
        public Previous(UUID executionId,String datasetId,String source,String grain,@Nullable Filter filter,Instant asOf,List<String> semanticRefs) {
            this(executionId,datasetId,source,grain,filter,asOf,semanticRefs,List.of(),List.of(),false);
        }
        public Previous(UUID executionId,String datasetId,String source,String grain,@Nullable Filter filter,Instant asOf,List<String> semanticRefs,List<String> dimensions,List<String> metrics) {
            this(executionId,datasetId,source,grain,filter,asOf,semanticRefs,dimensions,metrics,false);
        }
        public Previous { total=Boolean.TRUE.equals(total);semanticRefs=List.copyOf(semanticRefs);dimensions=dimensions==null?List.of():List.copyOf(dimensions);metrics=metrics==null?List.of():List.copyOf(metrics); }
    }
    public List<String> contextRefs() { return data.context().stream().map(Node::id).toList(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> immutable(Map<String,Object> source) {
        return (Map<String,Object>) freeze(source);
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> map) {
            var copy=new LinkedHashMap<String,Object>();
            map.forEach((key,item)->copy.put((String)key,freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof Collection<?> list)return list.stream().map(PromptPackage::freeze).toList();
        return value;
    }
    @Override public String toString() { return "PromptPackage[version="+promptVersion+"]"; }
}
