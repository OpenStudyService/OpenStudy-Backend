package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.prompt.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import java.time.Instant;
import java.util.*;

/** Selects only already-visible context and checks candidates with the execution planner, without executing. */
public final class QueryGrounding {
    public static final String VERSION="grounding-10";
    private static final String RULES="""
            The server ALWAYS applies the current authenticated row scope, including on follow-ups.
            "Allowed stores" or "accessible stores" means that server scope, not an approval/status field.
            An absent store predicate in previous conditions means no EXTRA user store filter; server scope still applies.
            Metric requestedDimensions, requestedPeriod and requestedOrder describe this request's resolved
            intent, not built-in metric defaults. Emit that intent explicitly in the matching AGGREGATE query:
            - requestedDimensions: use exactly these field IDs in query.dimensions, each with an output alias.
              Do not add display labels, entity IDs or timestamp fields as extra grouping dimensions.
            - Nonempty requestedPeriod: copy this predicate into query.filter. The server does not automatically
              add this request's period when you select the metric. Preserve its fields, operators and values.
              If other requested filters apply, combine them with this predicate using AND, without duplication.
              An empty requestedPeriod object means no resolved period; never emit {} as an IR filter.
              Keep other requested filters, or use filter=null if no filter was requested.
            - requestedOrder: use the selected metric's output alias, or requestedOrderField's alias if set.
              Keep that direction in query.orderBy; never semantic IDs or different fields.
            In query.metrics[].field use the selected METRIC node's ID, not its metadata field attribute.
            That metadata field is the underlying measure; the server owns its registered aggregation.
            Do not emit an aggregate function or substitute the underlying field for the metric ID.
            For AGGREGATE pagination, offset is page * size and the returned row cap is min(limit, size).
            limit must be between 1 and 50. When no page, page size or result limit is requested, use
            page=0, size=10, limit=10. A component's maxRows is not the query limit; do not copy it.
            Keep explicit page/size/limit requests within the supplied contract.
            approvedPath identifies the server planner's registered business path. It is not an IR field:
            do not emit joins, SQL or a replacement path. Use the supplied metric source, grain and field IDs.
            Do not replace a forbidden request with a different allowed query and claim it was fulfilled.
            Explicit requests to bypass scope, use a prohibited path, duplicate aggregate rows or bind
            another dataset's columns are UNSUPPORTED. Missing business capability is UNSUPPORTED,
            not a request for more parameters. A missing metric/topic requires clarification.
            """;
    public record Candidate(String metric,Set<String> dimensions,List<String> relations) {
        public Candidate {dimensions=Set.copyOf(dimensions);relations=List.copyOf(relations);}
    }
    private final SemanticRegistry registry;
    private final JoinPlanner planner;
    public QueryGrounding(SemanticRegistry registry,JoinPlanner planner){this.registry=registry;this.planner=planner;}

    public PromptPackage prepare(PromptPackage prompt,QueryIntent intent,UserContext user) {
        prompt=new PromptPackage(prompt.promptVersion(),prompt.systemInstructions()+"\n"+RULES,prompt.data(),prompt.irSchema(),prompt.viewSchema(),prompt.components());
        if(!intent.analyses().isEmpty()) {
            var chosen=prompt.data().analyses().stream().filter(a->intent.analyses().contains(a.id())).toList();
            if(chosen.size()==intent.analyses().size()) {
                var d=prompt.data();
                var required=new HashSet<String>();
                chosen.forEach(a->{required.add(a.source());required.addAll(a.inputs().keySet());});
                if(d.previous()!=null)required.addAll(d.previous().semanticRefs());
                // Analyses own their joins and output schemas. Keep visible entity anchors for discovery
                // and execution dependencies, but omit unrelated fields, metrics, terms and relations.
                // Virtual analysis inputs stay in the approved analysis contract; never add new nodes.
                var context=d.context().stream().filter(n->n.kind().equals("entity") || required.contains(n.id())).toList();
                return new PromptPackage(prompt.promptVersion(),prompt.systemInstructions(),new PromptPackage.Data(d.question(),d.asOf(),d.calendarZone(),d.displayZone(),d.snapshotVersion(),context,chosen,List.of(),d.previous()),prompt.irSchema(),prompt.viewSchema(),prompt.components());
            }
        }
        var visible=new HashSet<>(prompt.contextRefs());
        if(intent.metrics().size()!=1 || intent.dimensions().isEmpty() || !visible.containsAll(intent.metrics()) || !visible.containsAll(intent.dimensions()))return prompt;
        String metricId=intent.metrics().iterator().next();var metric=registry.metrics().get(metricId);
        if(metric==null)return prompt;
        var dims=intent.dimensions().stream().sorted().map(id->new QuerySpec.Selection(id,"d"+Math.abs(id.hashCode()))).toList();
        var query=new QuerySpec("grounding",metric.source(),metric.grain(),QuerySpec.Mode.AGGREGATE,dims,
                List.of(new QuerySpec.Selection(metricId,"measure")),intent.period(),List.of(),new QuerySpec.Page(0,10),10,null);
        var plan=planner.plan(query,user,prompt.data().asOf(),Instant.now());
        var candidate=new Candidate(metricId,intent.dimensions(),plan.joins().stream().map(j->j.relationId()).toList());
        var required=new HashSet<>(intent.dimensions());required.add(metricId);required.add(metric.source());
        if(metric.field()!=null)required.add(metric.field());required.addAll(metric.contextFields());
        fields(metric.defaultFilter(),required);fields(intent.period(),required);
        for(var join:plan.joins()){required.add(join.source());required.add(join.target());required.add(join.relationId());}
        // Projection implementation internals need not be model-visible, but business dependencies must be.
        new ContextGate(registry).requireMetric(metricId,visible);
        if(prompt.data().previous()!=null)required.addAll(prompt.data().previous().semanticRefs());
        var context=new ArrayList<PromptPackage.Node>();
        for(var node:prompt.data().context())if(required.contains(node.id())) {
            var attributes=new LinkedHashMap<>(node.attributes());
            if(node.id().equals(metricId)) {
                attributes.put("requestedDimensions",candidate.dimensions().stream().sorted().toList());
                attributes.put("approvedPath",candidate.relations().stream().filter(visible::contains).toList());
                attributes.put("requestedPeriod",intent.period()==null?Map.of():ContractJson.read(ContractJson.write(intent.period()),Map.class));
                if(intent.order()!=null){attributes.put("requestedOrder",intent.order().name());if(intent.orderField()!=null)attributes.put("requestedOrderField",intent.orderField());}
                if(intent.total())attributes.put("requestedTotal",true);
            }
            context.add(new PromptPackage.Node(node.id(),node.kind(),attributes));
        }
        var data=prompt.data();
        // An explicit simple-aggregate candidate needs no unrelated registered-analysis schemas or examples.
        return new PromptPackage(prompt.promptVersion(),prompt.systemInstructions(),new PromptPackage.Data(data.question(),data.asOf(),data.calendarZone(),data.displayZone(),data.snapshotVersion(),context,List.of(),List.of(),data.previous()),prompt.irSchema(),prompt.viewSchema(),prompt.components());
    }

    public void validate(QueryIntent intent,IrDocument ir) {
        if(ir.status()!=IrStatus.READY)return;
        if(!intent.analyses().isEmpty() && (ir.queries().size()!=intent.analyses().size()
                || ir.queries().stream().anyMatch(q->q.mode()!=QuerySpec.Mode.REGISTERED_ANALYSIS)
                || !ir.queries().stream().map(QuerySpec::analysisId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()).equals(intent.analyses())))invalid();
        if(!intent.metrics().isEmpty() && ir.queries().stream().noneMatch(q->q.metrics().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet()).containsAll(intent.metrics())))invalid();
        if(intent.total()){
            var selected=ir.queries().stream().filter(q->q.mode()==QuerySpec.Mode.AGGREGATE && q.metrics().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet()).equals(intent.metrics())).toList();
            var grouped=selected.stream().filter(q->!q.dimensions().isEmpty()).toList();var totals=selected.stream().filter(q->q.dimensions().isEmpty()).toList();
            if(grouped.size()!=1 || totals.size()!=1 || !Objects.equals(grouped.getFirst().filter(),totals.getFirst().filter()))invalid();
        }
        if(!intent.dimensions().isEmpty()) {
            var grouped=ir.queries().stream().filter(q->!q.dimensions().isEmpty()).toList();
            if(grouped.isEmpty())invalid();
            for(var q:grouped)if(!new HashSet<>(q.dimensions().stream().map(QuerySpec.Selection::field).toList()).equals(intent.dimensions()))invalid();
        }
        for(var query:ir.queries()) {
            if(intent.period()!=null) {
                var expected=new ArrayList<Filter.Leaf>();leaves(intent.period(),expected);
                var actual=new ArrayList<Filter.Leaf>();conjunction(query.filter(),actual);
                if(expected.stream().anyMatch(e->actual.stream().noneMatch(a->equivalent(a,e))))invalid();
                var constrainedFields=expected.stream().map(Filter.Leaf::field).collect(java.util.stream.Collectors.toSet());
                var all=new ArrayList<Filter.Leaf>();leaves(query.filter(),all);
                if(all.stream().anyMatch(l->constrainedFields.contains(l.field())&&expected.stream().noneMatch(e->equivalent(l,e))))invalid();
            }
            if(intent.order()!=null && !query.dimensions().isEmpty()) {
                var aliases=intent.orderField()==null?query.metrics().stream().filter(m->intent.metrics().contains(m.field())).map(QuerySpec.Selection::alias).toList():
                    java.util.stream.Stream.concat(query.dimensions().stream(),query.metrics().stream()).filter(s->s.field().equals(intent.orderField())).map(QuerySpec.Selection::alias).toList();
                if(query.orderBy().isEmpty() || !aliases.contains(query.orderBy().getFirst().alias()) || query.orderBy().getFirst().direction()!=intent.order())invalid();
            }
        }
    }
    private boolean equivalent(Filter.Leaf left,Filter.Leaf right) {
        if(!left.field().equals(right.field()) || left.operator()!=right.operator())return false;
        var field=registry.fields().get(left.field());
        if(field==null || field.type()!=ValueType.TIMESTAMP)return left.values().equals(right.values());
        return left.values().stream().map(QueryGrounding::timestamp).toList().equals(right.values().stream().map(QueryGrounding::timestamp).toList());
    }
    private static Object timestamp(Object value){try{return java.time.OffsetDateTime.parse(value.toString()).toInstant();}catch(java.time.DateTimeException invalid){return value;}}
    private static void conjunction(Filter filter,List<Filter.Leaf> leaves){
        if(filter instanceof Filter.Leaf leaf)leaves.add(leaf);
        else if(filter instanceof Filter.Group group && group.junction()==Filter.Junction.AND)group.children().forEach(f->conjunction(f,leaves));
    }
    private static void leaves(Filter f,List<Filter.Leaf> result){if(f instanceof Filter.Leaf l)result.add(l);else if(f instanceof Filter.Group g)g.children().forEach(c->leaves(c,result));}
    private static void fields(Filter f,Set<String> result){if(f instanceof Filter.Leaf l)result.add(l.field());else if(f instanceof Filter.Group g)g.children().forEach(c->fields(c,result));}
    private static void invalid(){throw new EngineException(EngineErrorCode.IR_INVALID,"Query does not preserve requested metric, grouping, period or order");}
}
