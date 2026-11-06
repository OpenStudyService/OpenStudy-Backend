package com.openstudy.domainlens.catalog;

import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/** Validated immutable snapshot. Construction fails atomically; physical paths never come from a request. */
public final class SemanticRegistry {
    public enum Aggregate { COUNT, SUM, AVG, MIN, MAX }
    public record Source(String id,Class<?> javaType,String jpaName,String grain,List<String> keys,
            String readPolicy,String rowPolicy,String fieldPolicy,SemanticMetadata metadata) {
        public Source {keys=List.copyOf(keys);Objects.requireNonNull(metadata);}
        public Source(String id,Class<?> javaType,String jpaName,String grain,List<String> keys,
                String readPolicy,String rowPolicy,String fieldPolicy) {
            this(id,javaType,jpaName,grain,keys,readPolicy,rowPolicy,fieldPolicy,SemanticMetadata.EMPTY);
        }
    }
    public record Field(String id,String source,String path,ValueType type,String unit,Integer precision,
            Set<String> operations,String policy,SemanticMetadata metadata) {
        public Field {operations=Set.copyOf(operations);Objects.requireNonNull(metadata);}
        public Field(String id,String source,String path,ValueType type,String unit,Integer precision,
                Set<String> operations,String policy) {
            this(id,source,path,type,unit,precision,operations,policy,SemanticMetadata.EMPTY);
        }
    }
    public record Metric(String id,String implementationRef,String source,String grain,Aggregate aggregate,
            String field,List<String> path,Filter defaultFilter,String version,String unit,Integer precision,Set<String> contextFields) {
        public Metric {path=List.copyOf(path);contextFields=contextFields==null?Set.of():Set.copyOf(contextFields);}
        public Metric(String id,String implementationRef,String source,String grain,Aggregate aggregate,String field,List<String> path,Filter defaultFilter,String version,String unit,Integer precision){this(id,implementationRef,source,grain,aggregate,field,path,defaultFilter,version,unit,precision,Set.of());}
    }
    public record Relation(String id,String source,String target,String path,boolean many,boolean optional,boolean owning,String joinUse,String direction,String meaning) {
        public Relation(String id,String source,String target,String path,boolean many,boolean optional,boolean owning,String joinUse) {
            this(id,source,target,path,many,optional,owning,joinUse,"","");
        }
    }
    public record Term(String id,String target,List<String> aliases) {public Term {aliases=List.copyOf(aliases);}}
    public record Example(String id,Set<String> references) {public Example {references=Set.copyOf(references);}}
    public record Edge(String from,String to,String type) {}
    private final List<Term> terms;
    private final List<Example> examples;
    public List<Term> terms(){return terms;}
    public List<Example> examples(){return examples;}
    private final String version;
    private final JpaModel model;
    private final Map<String,Source> sources;
    private final Map<String,Field> fields;
    private final Map<String,Metric> metrics;
    private final Map<String,Relation> relations;
    private final Set<String> projectionIds;
    public Set<String> projectionIds(){return projectionIds;}
    private final Set<String> nodes;
    private final List<Edge> edges;
    public SemanticRegistry(String version,JpaModel model,Map<String,String> grains,Set<String> policies,
            Set<String> implementations,List<Metric> metricDefinitions,List<Field> projections,
            List<Term> terms,List<Example> examples,Set<String> forbiddenTables) {
        this.projectionIds=projections.stream().map(Field::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.version=Objects.requireNonNull(version);this.model=model;this.terms=List.copyOf(terms);this.examples=List.copyOf(examples);
        var s=new LinkedHashMap<String,Source>();var f=new LinkedHashMap<String,Field>();var m=new LinkedHashMap<String,Metric>();var rel=new LinkedHashMap<String,Relation>();
        var ids=new HashSet<String>();var edges=new ArrayList<Edge>();
        if(model.diagnostics().stream().anyMatch(x->x.startsWith("EXPOSED_SENSITIVE") || x.startsWith("UNSUPPORTED"))) invalid("Unsafe or unsupported JPA mapping");
        // Hidden annotated fields reserve IDs and validate policy references without becoming public nodes.
        var declared=new HashSet<String>();
        for(var e:model.entities()) {
            if(!e.id().isBlank())unique(declared,e.id());
            for(var a:e.fields())if(!a.id().isBlank()) {unique(declared,a.id());policy(a.fieldPolicy(),policies);}
            for(var a:e.relations())if(!a.id().isBlank())unique(declared,a.id());
        }
        for(var e:model.entities())if(e.exposed()) {
            if(forbiddenTables.contains(e.table()) || e.id().isBlank()) invalid("Forbidden/missing source");
            if(!grains.containsKey(e.id()) || e.fields().stream().noneMatch(JpaModel.Field::key))invalid("Missing grain/key");
            policy(e.readPolicy(),policies);policy(e.rowPolicy(),policies);policy(e.fieldPolicy(),policies);
            unique(ids,e.id());s.put(e.id(),new Source(e.id(),e.javaType(),e.jpaName(),grains.get(e.id()),e.fields().stream().filter(JpaModel.Field::key).map(JpaModel.Field::path).toList(),e.readPolicy(),e.rowPolicy(),e.fieldPolicy(),e.metadata()));
            for(var attr:e.fields())if(attr.exposed()) {
                unique(ids,attr.id());String p=attr.fieldPolicy().isBlank()?e.fieldPolicy():attr.fieldPolicy();policy(p,policies);
                f.put(attr.id(),new Field(attr.id(),e.id(),attr.path(),valueType(attr.javaType()),attr.metadata().unit().isBlank()?null:attr.metadata().unit(),null,attr.operations(),p,attr.metadata()));
                edges.add(new Edge(e.id(),attr.id(),"HAS_FIELD"));
            }
        }
        for(var e:model.entities())if(e.exposed())for(var a:e.relations())if(!a.id().isBlank()) {
            var targetEntity=model.entities().stream().filter(x->x.javaType()==a.target()).findFirst().orElse(null);
            if(targetEntity!=null && !targetEntity.id().isBlank() && !targetEntity.exposed())continue;
            var target=s.values().stream().filter(x->x.javaType()==a.target()).findFirst().orElseThrow(()->error("Relation target not approved"));
            unique(ids,a.id());rel.put(a.id(),new Relation(a.id(),e.id(),target.id(),a.path(),a.amplifies(),a.optional(),a.owning(),a.joinUse(),a.direction(),a.meaning()));
            edges.add(new Edge(e.id(),target.id(),"RELATES_TO"));
            // A relationship is searchable metadata; its endpoints are one hop away.
            // These discovery edges do not create reverse executable join paths.
            edges.add(new Edge(a.id(),e.id(),"RELATION_ENDPOINT"));
            edges.add(new Edge(a.id(),target.id(),"RELATION_ENDPOINT"));
        }
        for(var p:projections) {
            unique(declared,p.id());unique(ids,p.id());if(!s.containsKey(p.source()))invalid("Projection source absent");policy(p.policy(),policies);
            if(valueType(path(model,s.get(p.source()).javaType(),p.path()))!=p.type())invalid("Projection type mismatch");
            var terminal=projectionField(model,s.get(p.source()).javaType(),p.path());
            if(!terminal.field().exposed() || !terminal.policy().equals(p.policy()))invalid("Projection cannot bypass field exposure/policy");
            if(!terminal.field().operations().containsAll(p.operations()) || !terminal.field().metadata().allowedValues().equals(p.metadata().allowedValues()))
                invalid("Projection cannot broaden field constraints");
            f.put(p.id(),p);edges.add(new Edge(p.source(),p.id(),"HAS_FIELD"));
        }
        var metricDependencies=new HashMap<String,Set<String>>();
        for(var metric:metricDefinitions) {
            unique(declared,metric.id());unique(ids,metric.id());if(!implementations.contains(metric.implementationRef()) || !s.containsKey(metric.source()) || !s.get(metric.source()).grain().equals(metric.grain()))invalid("Metric implementation/grain absent");
            if(metric.field()!=null && !f.containsKey(metric.field()))invalid("Metric field absent");
            // Approved execution dependencies are direct metadata edges of the metric.
            // Retrieving them takes one graph hop, without expanding the related sources.
            var dependencies=new TreeSet<String>();dependencies.add(metric.source());
            if(metric.field()!=null)dependencies.add(metric.field());
            String current=metric.source();for(String id:metric.path()){var edge=rel.get(id);if(edge==null || !edge.source().equals(current))invalid("Metric path absent");current=edge.target();dependencies.add(current);}
            var filterDependencies=new TreeSet<String>();filterFields(metric.defaultFilter(),filterDependencies);
            if(filterDependencies.stream().anyMatch(id->!f.containsKey(id)))invalid("Metric filter field absent");
            dependencies.addAll(filterDependencies);
            for(String id:metric.contextFields()){
                if(!f.containsKey(id))invalid("Metric context field absent");
                dependencies.add(id);dependencies.add(f.get(id).source());
            }
            m.put(metric.id(),metric);metricDependencies.put(metric.id(),Set.copyOf(dependencies));
            dependencies.forEach(id->edges.add(new Edge(metric.id(),id,"METRIC_USES")));
        }
        for(var term:terms){
            unique(declared,term.id());unique(ids,term.id());if(!ids.contains(term.target()))invalid("Term target absent");
            edges.add(new Edge(term.id(),term.target(),"ALIAS_OF"));
            // An alias for an approved metric carries exactly that metric's declared
            // dependencies, so a term seed also yields complete metadata in one hop.
            metricDependencies.getOrDefault(term.target(),Set.of()).stream().sorted()
                .forEach(id->edges.add(new Edge(term.id(),id,"METRIC_USES")));
        }
        for(var ex:examples){unique(declared,ex.id());unique(ids,ex.id());for(String id:ex.references()){if(!ids.contains(id))invalid("Example reference absent");edges.add(new Edge(ex.id(),id,"EXAMPLE_USES"));}}
        for(var source:s.values())for(String p:List.of(source.readPolicy(),source.rowPolicy(),source.fieldPolicy()))if(!p.isBlank()) {ids.add(p);edges.add(new Edge(source.id(),p,"GOVERNED_BY"));}
        for(var field:f.values()) {
            if(!field.metadata().allowedValues().isEmpty() && field.type()!=ValueType.STRING)invalid("Allowlist requires a string field");
            if(!field.policy().isBlank()){ids.add(field.policy());edges.add(new Edge(field.id(),field.policy(),"GOVERNED_BY"));}
        }
        this.sources=Map.copyOf(s);this.fields=Map.copyOf(f);this.metrics=Map.copyOf(m);this.relations=Map.copyOf(rel);this.nodes=Set.copyOf(ids);this.edges=List.copyOf(edges);
    }
    public String version(){return version;}
    private static void filterFields(Filter filter,Set<String> fields) {
        if(filter instanceof Filter.Leaf leaf)fields.add(leaf.field());
        else if(filter instanceof Filter.Group group)group.children().forEach(child->filterFields(child,fields));
    }
    public JpaModel model(){return model;}
    public Map<String,Source> sources(){return sources;}
    public Map<String,Field> fields(){return fields;}
    public Map<String,Metric> metrics(){return metrics;}
    public Map<String,Relation> relations(){return relations;}
    public Set<String> nodes(){return nodes;}
    public List<Edge> edges(){return edges;}
    public Set<String> publicNodeIds(){var ids=new HashSet<>(nodes);sources.values().forEach(s->{ids.remove(s.readPolicy());ids.remove(s.rowPolicy());ids.remove(s.fieldPolicy());});fields.values().forEach(f->ids.remove(f.policy()));return Set.copyOf(ids);}
    private record ProjectionTarget(JpaModel.Field field,String policy) {}
    private static ProjectionTarget projectionField(JpaModel model,Class<?> root,String path) {
        var entity=model.entities().stream().filter(e->e.javaType()==root && e.exposed()).findFirst().orElseThrow(()->error("Projection entity not approved"));
        var terminal=entity.fields().stream().filter(f->f.path().equals(path)).findFirst();
        if(terminal.isPresent()) {
            var field=terminal.get();
            return new ProjectionTarget(field,field.fieldPolicy().isBlank()?entity.fieldPolicy():field.fieldPolicy());
        }
        int dot=path.indexOf('.');if(dot<0)throw error("Unknown projection path");
        var relation=entity.relations().stream().filter(r->r.path().equals(path.substring(0,dot)) && !r.id().isBlank() && !r.amplifies()).findFirst().orElseThrow(()->error("Projection relation not approved or to-many"));
        return projectionField(model,relation.target(),path.substring(dot+1));
    }
    public static Class<?> path(JpaModel model,Class<?> root,String path) {
        String remaining=path;Class<?> current=root;
        while(true) {
            Class<?> type=current;var entity=model.entities().stream().filter(e->e.javaType()==type).findFirst().orElseThrow(()->error("Unknown path entity"));
            String rest=remaining;var field=entity.fields().stream().filter(f->f.path().equals(rest)).findFirst();if(field.isPresent())return field.get().javaType();
            int dot=remaining.indexOf('.');if(dot<0)throw error("Unknown JPA field/path");
            String first=remaining.substring(0,dot);var relation=entity.relations().stream().filter(r->r.path().equals(first)).findFirst().orElseThrow(()->error("Unknown JPA relation/path"));
            current=relation.target();remaining=remaining.substring(dot+1);
        }
    }
    public static ValueType valueType(Class<?> type) {
        if(type==String.class || type.isEnum())return ValueType.STRING;
        if(type==boolean.class || type==Boolean.class)return ValueType.BOOLEAN;
        if(type==BigDecimal.class || type==Double.class || type==Float.class || type==double.class || type==float.class)return ValueType.DECIMAL;
        if(Number.class.isAssignableFrom(type) || type==long.class || type==int.class || type==short.class)return ValueType.INTEGER;
        if(type==LocalDate.class)return ValueType.DATE;
        if(type==Instant.class || type==LocalDateTime.class || type==OffsetDateTime.class)return ValueType.TIMESTAMP;
        throw error("Unsupported field type");
    }
    private static void policy(String id,Set<String> policies){if(!id.isBlank() && !policies.contains(id))invalid("Unknown policy");}
    private static void unique(Set<String> ids,String id){if(id==null || id.isBlank() || !ids.add(id))invalid("Duplicate/missing semantic ID");}
    private static EngineException error(String message){return new EngineException(EngineErrorCode.NOT_READY,message);}
    private static void invalid(String message){throw error(message);}
}
