package com.openstudy.domainlens.security;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import java.util.*;

/** Single policy entrypoint for queries, pages and restored executions. */
public final class PolicyEngine {
    private final SemanticRegistry registry;
    private final String version;
    private final Map<String,ReadPolicy> reads;
    private final Map<String,FieldPolicy> fields;
    private final Map<String,RowPolicy> rows;
    private final ActionAllowlist actions;
    private final java.util.function.BiPredicate<String,UserContext> metricAccess;
    private final java.util.function.BiConsumer<QuerySpec,UserContext> queryRule;
    public void metric(String id,UserContext user){var m=registry.metrics().get(id);if(m==null || !metricAccess.test(id,user))denied();source(m.source(),user);}
    public PolicyEngine(SemanticRegistry registry,String version,List<ReadPolicy> reads,List<FieldPolicy> fields,List<RowPolicy> rows,ActionAllowlist actions) {
        this(registry,version,reads,fields,rows,actions,(id,u)->true);
    }
    public PolicyEngine(SemanticRegistry registry,String version,List<ReadPolicy> reads,List<FieldPolicy> fields,List<RowPolicy> rows,ActionAllowlist actions,java.util.function.BiPredicate<String,UserContext> metricAccess) {
        this(registry,version,reads,fields,rows,actions,metricAccess,(q,u)->{});
    }
    public PolicyEngine(SemanticRegistry registry,String version,List<ReadPolicy> reads,List<FieldPolicy> fields,List<RowPolicy> rows,ActionAllowlist actions,java.util.function.BiPredicate<String,UserContext> metricAccess,java.util.function.BiConsumer<QuerySpec,UserContext> queryRule) {
        this.queryRule=java.util.Objects.requireNonNull(queryRule);
        this.metricAccess=java.util.Objects.requireNonNull(metricAccess);
        this.registry=registry;this.version=version;this.reads=index(reads,ReadPolicy::id);this.fields=index(fields,FieldPolicy::id);this.rows=index(rows,RowPolicy::id);this.actions=actions;
    }
    private static <T> Map<String,T> index(List<T> items,java.util.function.Function<T,String> id) {var map=new HashMap<String,T>();for(T item:items)if(map.put(id.apply(item),item)!=null)throw new IllegalArgumentException("Duplicate policy");return Map.copyOf(map);}
    public boolean bindingsValid(){return registry.sources().values().stream().allMatch(s->reads.containsKey(s.readPolicy()) && rows.containsKey(s.rowPolicy()) && fields.containsKey(s.fieldPolicy())) && registry.fields().values().stream().allMatch(f->fields.containsKey(f.policy()));}
    public String version(){return version;}
    public void source(String id,UserContext user) {
        Identity.require(user);var source=registry.sources().get(id);if(source==null)denied();
        var p=reads.get(source.readPolicy());if(p==null || !p.allows(user,id))denied();
    }
    public void field(String id,String operation,UserContext user) {
        var f=registry.fields().get(id);if(f==null)denied();source(f.source(),user);
        var p=fields.get(f.policy());if(p==null || !p.allows(user,id,operation))denied();
    }
    public void action(String action,UserContext user){Identity.require(user);if(actions==null || !actions.allows(user,action))denied();}
    public void query(QuerySpec query,UserContext user) {
        source(query.source(),user);queryRule.accept(query,user);
        for(var s:query.dimensions()) {field(s.field(),"select",user);field(s.field(),"output",user);if(query.mode()==QuerySpec.Mode.AGGREGATE)field(s.field(),"group",user);}
        for(var m:query.metrics()) {
            metric(m.field(),user);var metric=registry.metrics().get(m.field());
            if(metric.field()!=null){field(metric.field(),"select",user);field(metric.field(),"output",user);}
            filter(metric.defaultFilter(),user);
        }
        filter(query.filter(),user);
        for(var o:query.orderBy()) {
            var selection=java.util.stream.Stream.concat(query.dimensions().stream(),query.metrics().stream()).filter(s->s.alias().equals(o.alias())).findFirst().orElseThrow(()->error());
            var metric=registry.metrics().get(selection.field());field(metric==null?selection.field():metric.field()==null?registry.sources().get(metric.source()).keys().stream().map(k->registry.fields().values().stream().filter(f->f.source().equals(metric.source())&&f.path().equals(k)).findFirst().orElseThrow(()->error()).id()).findFirst().orElseThrow(()->error()):metric.field(),"sort",user);
        }
    }
    private void filter(Filter filter,UserContext user) {
        if(filter instanceof Filter.Leaf l)field(l.field(),"filter",user);
        else if(filter instanceof Filter.Group g)g.children().forEach(c->filter(c,user));
    }
    public Map<String,Filter> scope(Set<String> sources,UserContext user) {return scope(sources,user,null);}
    public Map<String,Filter> scope(Set<String> sources,UserContext user,QuerySpec query) {
        var result=new LinkedHashMap<String,Filter>();
        for(String id:sources){source(id,user);var s=registry.sources().get(id);var p=rows.get(s.rowPolicy());if(p==null)denied();
            if(p.unrestricted(user,id,query))continue;
            var filter=p.predicate(user,id);if(filter==null)denied();validateScope(filter,id);result.put(id,filter);}
        return Map.copyOf(result);
    }
    private void validateScope(Filter filter,String source) {
        if(filter instanceof Filter.Leaf l) {
            var f=registry.fields().get(l.field());if(f==null || !f.source().equals(source) || l.values().isEmpty())denied();
            if(l.operator()!=Filter.Operator.EQ && l.operator()!=Filter.Operator.IN)denied();
        } else if(filter instanceof Filter.Group g) {if(g.children().isEmpty() || g.junction()!=Filter.Junction.AND)denied();g.children().forEach(c->validateScope(c,source));}
        else denied();
    }
    private static EngineException error(){return new EngineException(EngineErrorCode.SCOPE_DENIED,"Policy denied");}
    private static void denied(){throw error();}
}
