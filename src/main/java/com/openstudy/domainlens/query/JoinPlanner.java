package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.ExecutionPlan;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.security.*;
import java.util.*;
import java.time.Instant;

public final class JoinPlanner {
    private final SemanticRegistry registry;private final PolicyEngine policies;
    private final com.openstudy.domainlens.api.spi.AnalysisExecutor analyses;
    public JoinPlanner(SemanticRegistry registry,PolicyEngine policies){this(registry,policies,null);}
    public JoinPlanner(SemanticRegistry registry,PolicyEngine policies,com.openstudy.domainlens.api.spi.AnalysisExecutor analyses){this.registry=registry;this.policies=policies;this.analyses=analyses;}
    public ExecutionPlan plan(QuerySpec q,UserContext user,Instant asOf,Instant snapshotAt) {
        if(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS){
            if(analyses==null)throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Analysis executor absent");
            return analyses.plan(q,user,asOf,snapshotAt);
        }
        policies.query(q,user);
        if(q.pagination().page()<0 || q.pagination().size()<1 || q.pagination().size()>100 || q.limit()<1 || q.limit()>50)budget();
        if(asOf==null)throw new EngineException(EngineErrorCode.NOT_READY,"asOf required");
        var joins=new LinkedHashMap<String,ExecutionPlan.Join>();
        for(var m:q.metrics()) {
            var metric=registry.metrics().get(m.field());if(metric==null || !metric.grain().equals(q.grain()))invalid("Metric grain mismatch");
            addPath(route(q.source(),metric.source()),joins,true);
            var path=new ArrayList<SemanticRegistry.Relation>();String current=metric.source();
            for(String id:metric.path()){var rel=registry.relations().get(id);if(rel==null || !rel.source().equals(current))invalid("Metric path incomplete");path.add(rel);current=rel.target();}
            addPath(path,joins,true);
        }
        var semanticJoins=new HashSet<>(joins.keySet());
        for(var f:q.dimensions())addField(q.source(),registry.fields().get(f.field()),joins,!q.metrics().isEmpty());
        filters(q.source(),q.filter(),joins,!q.metrics().isEmpty());
        var flattened=projectionRelations(q);
        var requestedFields=new HashSet<String>();q.dimensions().forEach(selection->requestedFields.add(selection.field()));collectFields(q.filter(),requestedFields);
        for(String fieldId:requestedFields){
            String sourceId=registry.fields().get(fieldId).source();
            while(!sourceId.equals(q.source())){String target=sourceId;var incoming=joins.values().stream().filter(j->j.target().equals(target)).findFirst();if(incoming.isEmpty())break;semanticJoins.add(incoming.get().relationId());sourceId=incoming.get().source();}
        }
        // Projection internals cannot erase edges that are also part of an explicit business path.
        flattened.removeAll(semanticJoins);
        for(var selection:q.metrics()){var metric=registry.metrics().get(selection.field());if(!metric.path().isEmpty() && joins.keySet().stream().anyMatch(id->!metric.path().contains(id) && !flattened.contains(id)))invalid("Field conflicts with registered metric path");}
        if(joins.keySet().stream().filter(id->!flattened.contains(id)).count()>3)budget();
        if(!q.metrics().isEmpty() && joins.values().stream().anyMatch(ExecutionPlan.Join::many))invalid("Join amplifies metric grain");
        // DETAIL cannot silently duplicate root rows either; a registered analysis can model expansion.
        if(q.mode()==QuerySpec.Mode.DETAIL && joins.values().stream().anyMatch(ExecutionPlan.Join::many))invalid("DETAIL root grain expansion");
        var sources=new HashSet<String>();sources.add(q.source());joins.values().forEach(j->{sources.add(j.source());sources.add(j.target());});
        var source=registry.sources().get(q.source());
        return new ExecutionPlan(ExecutionPlan.VERSION,registry.version(),policies.version(),Identity.require(user).subjectId(),user.policyBinding().scope(),asOf,snapshotAt,q,List.copyOf(joins.values()),policies.scope(sources,user,q),source.keys(),5);
    }
    private Set<String> projectionRelations(QuerySpec q){
        var fields=new HashSet<String>();q.dimensions().forEach(s->fields.add(s.field()));collectFields(q.filter(),fields);
        var result=new HashSet<String>();
        for(String id:fields)if(registry.projectionIds().contains(id)){
            var field=registry.fields().get(id);String source=field.source();String[] parts=field.path().split("\\.");
            for(int i=0;i<parts.length-1;i++){String current=source,part=parts[i];var relation=registry.relations().values().stream().filter(r->r.source().equals(current)&&r.path().equals(part)).findFirst();
                if(relation.isEmpty())break;result.add(relation.get().id());source=relation.get().target();}
        }
        return result;
    }
    private void collectFields(Filter filter,Set<String> fields){if(filter instanceof Filter.Leaf l)fields.add(l.field());else if(filter instanceof Filter.Group g)g.children().forEach(f->collectFields(f,fields));}
    private void filters(String source,Filter filter,Map<String,ExecutionPlan.Join> joins,boolean aggregate) {
        if(filter instanceof Filter.Leaf l)addField(source,registry.fields().get(l.field()),joins,aggregate);
        else if(filter instanceof Filter.Group g)g.children().forEach(f->filters(source,f,joins,aggregate));
    }
    private void addField(String source,SemanticRegistry.Field field,Map<String,ExecutionPlan.Join> joins,boolean aggregate) {
        if(field==null)invalid("Field absent");if(!source.equals(field.source()) && !joins.values().stream().anyMatch(j->j.target().equals(field.source())))addPath(route(source,field.source()),joins,aggregate);
        if(field.path().contains(".")) {
            String[] parts=field.path().split("\\.");String current=field.source();
            for(int i=0;i<parts.length-1;i++){
                String from=current;String part=parts[i];var candidates=registry.relations().values().stream().filter(r->r.source().equals(from)&&r.path().equals(part)).toList();
                // Embedded attribute paths are columns, not joins.
                if(candidates.isEmpty())break;if(candidates.size()!=1)invalid("Ambiguous projection path");
                var rel=candidates.getFirst();addPath(List.of(rel),joins,aggregate);current=rel.target();
            }
        }
    }
    private void addPath(List<SemanticRegistry.Relation> path,Map<String,ExecutionPlan.Join> joins,boolean inner) {
        for(var rel:path) {
            if(joins.values().stream().anyMatch(j->j.target().equals(rel.target()) && !j.relationId().equals(rel.id())))invalid("Ambiguous target path");
            joins.putIfAbsent(rel.id(),new ExecutionPlan.Join(rel.id(),rel.source(),rel.target(),rel.path(),inner?ExecutionPlan.JoinType.INNER:ExecutionPlan.JoinType.LEFT,rel.many()));
        }
    }
    private List<SemanticRegistry.Relation> route(String source,String target) {
        if(source.equals(target))return List.of();var routes=new ArrayList<List<SemanticRegistry.Relation>>();
        routes(source,target,new HashSet<>(Set.of(source)),new ArrayList<>(),routes);
        if(routes.size()!=1)invalid(routes.isEmpty()?"No registered path":"Multiple registered paths");return routes.getFirst();
    }
    private void routes(String from,String target,Set<String> visited,List<SemanticRegistry.Relation> path,List<List<SemanticRegistry.Relation>> results) {
        if(path.size()>=3)return;
        for(var r:registry.relations().values())if(r.source().equals(from) && !visited.contains(r.target())) {
            var next=new ArrayList<>(path);next.add(r);
            if(r.target().equals(target))results.add(List.copyOf(next));
            else {var seen=new HashSet<>(visited);seen.add(r.target());routes(r.target(),target,seen,next,results);}
        }
    }
    private static void budget(){throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Query budget exceeded");}
    private static void invalid(String message){throw new EngineException(EngineErrorCode.JOIN_AMBIGUOUS,message);}
}
