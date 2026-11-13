package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.planning.*;
import com.openstudy.domainlens.api.prompt.*;
import com.openstudy.domainlens.catalog.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic assembly. Missing proof abstains before the existing executable IR validators. */
public final class PlanCompiler {
    public static final String VERSION="plan-compiler-18";
    private final SemanticRegistry registry;
    public PlanCompiler(SemanticRegistry registry){this.registry=registry;}
    public enum Retry { TERMINAL, CORRECTABLE }
    public record Compilation(IrDocument ir,Retry retry) {}
    /** Internal orchestration decision; never inferred from the model's explanation. */
    public Compilation compileForPipeline(SemanticPlan plan,PromptPackage prompt,QueryIntent intent) {
        var ir=compile(plan,prompt,intent);
        return new Compilation(ir,plan.status()==IrStatus.READY && ir.status()==IrStatus.NEEDS_CLARIFICATION?Retry.CORRECTABLE:Retry.TERMINAL);
    }
    public IrDocument compile(SemanticPlan plan,PromptPackage prompt,QueryIntent intent) {
        boolean formulaMode=!prompt.data().formulas().isEmpty();
        boolean mixed=PlanningPrompt.version(prompt.data()).equals(SemanticPlan.MIXED_VERSION);
        if(!(mixed?Set.of(SemanticPlan.MIXED_VERSION):formulaMode?Set.of(SemanticPlan.FORMULA_VERSION,"semantic-formula-plan-1"):Set.of(SemanticPlan.VERSION,"semantic-plan-1")).contains(plan.version()))invalid("Unknown semantic plan version");
        plan.validateReason();
        if(formulaMode)for(var request:plan.requests()) {
            var expected=new TreeSet<String>(request.metrics());if(request.analysisId()!=null)expected.add(request.analysisId());
            if(mixed)expected.retainAll(prompt.data().formulas().stream().map(f->f.id()).toList());
            var selected=new HashSet<String>();for(var ref:request.formulas()) {
                var contract=prompt.data().formulas().stream().filter(f->f.id().equals(ref.id()) && f.version().equals(ref.version()) && f.hash().equals(ref.hash())).findFirst();
                if(contract.isEmpty() || !selected.add(ref.id()))invalid("Unapproved formula reference");
            }
            if(!selected.equals(expected))invalid("Formula selection does not match query");
        }
        if(plan.status()!=IrStatus.READY) {
            if(!plan.requests().isEmpty())invalid("Non-ready plan contains requests");
            if(plan.reason()==null || plan.reason().isBlank())invalid("Abstention reason missing");
            var options=plan.options().stream().filter(o->prompt.contextRefs().contains(o.id()) || prompt.data().analyses().stream().anyMatch(a->a.id().equals(o.id()))).toList();
            ReasonCode code=plan.reasonCode();
            if(code==null)return new IrDocument(IrDocument.LEGACY_VERSION,plan.status(),plan.status()==IrStatus.NEEDS_CLARIFICATION?plan.reason():null,options,plan.status()==IrStatus.UNSUPPORTED?plan.reason():null,List.of(),null,List.of(),null);
            return confirmModelReason(stop(plan.status(),code,plan.reason(),options),prompt);
        }
        if(plan.requests().isEmpty())invalid("READY plan must contain a request");
        if(!matches(prompt.data().question(),"아닌|제외|빼고|말고|않|not |without "))for(var entry:Map.of("LineChart","선\\s*차트|line chart","Heatmap","히트맵|heatmap","ScatterPlot","산점도|scatter plot").entrySet())
            if(matches(prompt.data().question(),entry.getValue()) && prompt.components().components().stream().noneMatch(c->c.id().equals(entry.getKey())))return stop(IrStatus.UNSUPPORTED,ReasonCode.CAPABILITY_UNSUPPORTED,"요청한 화면 표현은 등록된 기능에 없습니다.",List.of());
        if(!plan.unresolved().isEmpty())return stop(IrStatus.NEEDS_CLARIFICATION,"추가 조건을 지정해 주세요: "+String.join(", ",plan.unresolved()),List.of());
        if(plan.reason()!=null || !plan.options().isEmpty())invalid("READY plan contains abstention fields");
        var ids=Set.copyOf(prompt.contextRefs());var queries=new ArrayList<QuerySpec>();
        var views=new ArrayList<ViewSpec.Component>();var deferredTotalKpis=new ArrayList<QuerySpec>();var used=new TreeSet<String>();
        for(var selectedRequest:plan.requests()) {
            // Unknown component IDs remain invalid even when an unrequested format is discarded.
            for(String format:selectedRequest.presentations())if(!explicitPresentation(prompt.data().question()) && prompt.components().components().stream().noneMatch(c->c.id().equals(canonicalComponent(format,prompt.components()))))
                return stop(IrStatus.UNSUPPORTED,ReasonCode.CAPABILITY_UNSUPPORTED,"선택한 화면 표현은 등록되어 있지 않습니다.",List.of());
            // An independently resolved requested grouping is a server input, like the resolved period.
            var r=resolveFirstRows(defaultOrder(defaultPresentations(selectedRequest,prompt),prompt,intent),prompt);
            // A requested table also owns its independently resolved population total.
            // Only repair the scalar's unsupported KPI choice; all semantic proofs still run.
            if(explicitPopulationTotal(r,plan.requests(),intent) && tableOnlyTotal(prompt)
                    && r.presentations().equals(List.of("KpiGroup")))
                r=new SemanticPlan.Request(r.kind(),r.source(),r.analysisId(),r.metrics(),r.dimensions(),r.filter(),r.order(),r.page(),r.size(),r.limit(),r.total(),List.of("DataTable"),r.evidence(),r.formulas());
            if(!explicitPopulationTotal(r,plan.requests(),intent) && r.kind()==QuerySpec.Mode.AGGREGATE && r.dimensions().isEmpty() && !r.metrics().isEmpty() && intent.metrics().containsAll(r.metrics()) && !intent.dimensions().isEmpty())
                r=new SemanticPlan.Request(r.kind(),r.source(),r.analysisId(),r.metrics(),intent.dimensions().stream().sorted().toList(),r.filter(),r.order(),r.page(),r.size(),r.limit(),r.total(),r.presentations(),r.evidence(),r.formulas());
            final var request=r;
            var requested=new LinkedHashSet<String>();requested.addAll(r.metrics());requested.addAll(r.dimensions());
            if(r.kind()==QuerySpec.Mode.DETAIL && r.source()!=null)requested.add(r.source());
            if(r.kind()==QuerySpec.Mode.REGISTERED_ANALYSIS && r.analysisId()!=null)requested.add(r.analysisId());
            for(String id:requested) {
                boolean supplied=ids.contains(id) || prompt.data().analyses().stream().anyMatch(a->a.id().equals(id));
                if(!supplied)return stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_MISSING,"승인된 데이터 또는 분석 근거가 없습니다.",List.of());
                if(!selectionProof(r,id,prompt,intent))return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"사용할 지표·항목·분석을 명확히 지정해 주세요.",choices(requested,prompt));
            }
            String source,grain;PromptPackage.Analysis analysis=null;
            if(r.kind()==QuerySpec.Mode.AGGREGATE) {
                if(r.metrics().isEmpty())return stop(IrStatus.NEEDS_CLARIFICATION,"집계할 지표를 지정해 주세요.",List.of());
                var m=registry.metrics().get(r.metrics().getFirst());if(m==null)invalid("Unknown metric");source=m.source();grain=m.grain();
                for(String id:r.metrics()){var candidate=registry.metrics().get(id);if(candidate==null || !candidate.source().equals(source) || !candidate.grain().equals(grain))return stop(IrStatus.UNSUPPORTED,"서로 다른 집계 단위의 지표는 함께 조회할 수 없습니다.",List.of());new ContextGate(registry).requireMetric(id,ids);}
                if(r.analysisId()!=null)invalid("Aggregate has analysis ID");
            }else if(r.kind()==QuerySpec.Mode.REGISTERED_ANALYSIS) {
                analysis=prompt.data().analyses().stream().filter(a->a.id().equals(request.analysisId())).findFirst().orElse(null);
                if(analysis==null)return stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_MISSING,"승인된 분석이 없습니다.",List.of());
                if(!r.metrics().isEmpty() || !r.dimensions().isEmpty() || r.total())invalid("Registered analysis has ordinary selections");
                source=analysis.source();grain=analysis.grain();
            }else {
                var s=registry.sources().get(r.source());if(s==null || !ids.contains(r.source()))return stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_MISSING,"승인된 조회 대상이 없습니다.",List.of());
                if(r.dimensions().isEmpty())return stop(IrStatus.NEEDS_CLARIFICATION,"조회할 항목을 지정해 주세요.",List.of());
                if(!r.metrics().isEmpty() || r.total() || r.analysisId()!=null)invalid("Detail contains aggregate selections");source=s.id();grain=s.grain();
            }
            if(r.source()!=null && !r.source().equals(source))invalid("Source conflicts with approved metric/analysis");
            for(String id:r.dimensions())if(!ids.contains(id) || !registry.fields().containsKey(id))invalid("Unapproved dimension");
            var requestedFilterIds=new HashSet<String>();PromptService.filterReferences(r.filter(),requestedFilterIds);
            var approvedInputs=new HashSet<>(ids);if(analysis!=null)approvedInputs.addAll(analysis.inputs().keySet());
            if(!approvedInputs.containsAll(requestedFilterIds))return stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_MISSING,"필터에 사용할 승인된 데이터 항목이 없습니다.",List.of());
            // A missing model slot can use an independently resolved user condition, never search rank.
            Filter filter=r.filter()==null?intent.period():r.filter();
            boolean explicitPeriod=CalendarPeriod.explicit(prompt.data().question());
            if(filter==null && prompt.data().previous()!=null && repeats(prompt.data().question()) && !explicitPeriod)filter=prompt.data().previous().filter();
            if(filter==null && explicitPeriod)return stop(IrStatus.NEEDS_CLARIFICATION,CalendarPeriod.specified(prompt.data().question())?ReasonCode.SEMANTIC_GROUNDING_FAILED:ReasonCode.REQUIRED_INPUT_MISSING,"요청한 기간의 시작·종료 조건을 확정하지 못했습니다.",List.of());
            if(r.filter()!=null && !filterProof(r,prompt,intent))return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"요청한 필터 조건과 기간의 근거를 검증하지 못했습니다.",List.of());
            if(formulaMode && r.kind()==QuerySpec.Mode.AGGREGATE && r.metrics().stream().allMatch(id->prompt.data().formulas().stream().anyMatch(f->f.id().equals(id))))for(String metricId:r.metrics()) {
                var contract=prompt.data().formulas().stream().filter(f->f.id().equals(metricId)).findFirst().orElseThrow();
                var leaves=new ArrayList<Filter.Leaf>();leaves(filter,leaves);
                for(var leaf:leaves){var input=contract.inputs().get(leaf.field());if(input==null || !input.operators().contains(leaf.operator()))invalid("Unapproved formula input");}
            }
            boolean resolvedTotal=r.kind()==QuerySpec.Mode.AGGREGATE && intent.total() && new HashSet<>(r.metrics()).equals(intent.metrics());
            boolean includeTotal=(r.total() || resolvedTotal) && !r.dimensions().isEmpty();
            // Explicit scalar requests remain independently validated and own their presentations.
            // Never synthesize another population query when the plan already contains the same one.
            final Filter populationFilter=filter;
            boolean hasExplicitTotal=includeTotal && plan.requests().stream().anyMatch(other->
                    explicitPopulationTotal(other,plan.requests(),intent) && samePopulation(request,other,intent,populationFilter));
            if(r.total() && !resolvedTotal && !optionProof(r,"total",prompt))return stop(IrStatus.NEEDS_CLARIFICATION,"전체 합계가 필요한지 확인해 주세요.",List.of());
            for(String slot:List.of("page","size","limit","order","presentation")) {
                boolean set=switch(slot){case "page"->r.page()!=null;case "size"->r.size()!=null;case "limit"->r.limit()!=null;case "order"->!r.order().isEmpty();default->!r.presentations().isEmpty();};
                if(set && !optionProof(r,slot,prompt,intent))return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"정렬·페이지·표현 요구의 근거를 검증하지 못했습니다.",List.of());
            }
            int page=r.page()==null?0:r.page(),size=r.size()==null?10:r.size(),limit=r.limit()==null?10:r.limit();
            var dimensions=selections(r.dimensions(),"d");var metrics=selections(r.metrics(),"m");
            var aliases=new HashMap<String,String>();dimensions.forEach(s->aliases.put(s.field(),s.alias()));metrics.forEach(s->aliases.put(s.field(),s.alias()));
            var orders=new ArrayList<QuerySpec.Order>();
            for(var sort:r.order()){String alias=aliases.get(sort.field());if(alias==null)invalid("Sort does not select a requested field");orders.add(new QuerySpec.Order(alias,sort.direction()));}
            if(orders.isEmpty() && intent.order()!=null && !metrics.isEmpty() && !dimensions.isEmpty())orders.add(new QuerySpec.Order(intent.orderField()==null?metrics.getFirst().alias():Objects.requireNonNull(aliases.get(intent.orderField()),"Requested order field must be selected"),intent.order()));
            String queryId="q"+queries.size();var q=new QuerySpec(queryId,source,grain,r.kind(),dimensions,metrics,filter,orders,new QuerySpec.Page(page,size),limit,r.analysisId());
            if(analysis!=null) {
                // Every original request has passed its own proofs before sharing execution.
                var prior=queries.stream().filter(existing->existing.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS && Objects.equals(existing.analysisId(),request.analysisId())).findFirst();
                if(prior.isPresent()) {
                    if(!sameAnalysisInputs(prior.get(),q))invalid("Conflicting duplicate registered analysis");
                    q=prior.get();
                }else queries.add(q);
            }else queries.add(q);
            if(ids.contains(source))used.add(source);used.addAll(r.metrics());used.addAll(r.dimensions());
            var filterIds=new HashSet<String>();PromptService.filterReferences(filter,filterIds);filterIds.stream().filter(ids::contains).forEach(used::add);
            if(analysis!=null) {
                var fulfilled=new HashSet<String>();
                for(var output:analysis.outputs().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    var approved=prompt.data().formulas().stream().filter(f->f.id().equals(request.analysisId())).findFirst().orElse(null);
                    var registered=approved==null?analysis.presentations():approved.presentations();
                    List<String> formats=r.presentations().isEmpty()?(registered.isEmpty()?List.of("PaginatedDataTable"):registered.stream().filter(p->p.output().equals(output.getKey())).map(p->p.component()).distinct().toList()):r.presentations();
                    boolean displayed=false;
                    for(String format:formats) {
                        var candidates=registered.isEmpty()?Collections.singletonList((com.openstudy.domainlens.api.formula.FormulaDefinition.Presentation)null):registered.stream().filter(p->p.output().equals(output.getKey()) && p.component().equals(canonicalComponent(format,prompt.components()))).toList();
                        for(var presentation:candidates){
                            var component=registered.isEmpty()?component(format,output.getKey(),output.getValue(),views.size(),prompt.components(),analysis.maxRows().getOrDefault(output.getKey(),100)<=1):registeredComponent(presentation,output.getKey(),views.size(),prompt.components(),analysis.presentationEvents());
                            if(component!=null){
                                final var proposed=component;
                                if(views.stream().noneMatch(existing->existing.datasetId().equals(proposed.datasetId()) && existing.componentRef().equals(proposed.componentRef()) && existing.bindings().equals(proposed.bindings())))views.add(component);
                                displayed=true;fulfilled.add(format);
                            }
                        }
                    }
                    if(!displayed && !r.presentations().isEmpty())continue;
                }
                if(!fulfilled.containsAll(r.presentations()))return stop(IrStatus.UNSUPPORTED,"요청한 표현을 승인된 분석 출력에 연결할 수 없습니다.",List.of());
            }else {
                var schema=QueryPipeline.schema(registry,q);
                if(formulaMode && r.kind()==QuerySpec.Mode.AGGREGATE && r.metrics().stream().allMatch(id->prompt.data().formulas().stream().anyMatch(f->f.id().equals(id))))for(String metricId:r.metrics()) {
                    var contract=prompt.data().formulas().stream().filter(f->f.id().equals(metricId)).findFirst().orElseThrow();
                    var formats=new ArrayList<>(r.presentations().isEmpty()?List.of("PaginatedDataTable"):r.presentations());if(includeTotal && !hasExplicitTotal)formats.add(tableOnlyTotal(prompt)?"DataTable":"KpiGroup");
                    if(formats.stream().anyMatch(format->contract.presentations().stream().noneMatch(p->p.component().equals(canonicalComponent(format,prompt.components())))))return stop(IrStatus.UNSUPPORTED,"공식에 등록된 화면 표현이 아닙니다.",List.of());
                }
                int groupViewStart=views.size();
                for(String format:r.presentations().isEmpty()?List.of("PaginatedDataTable"):r.presentations()) {
                    if(format.equals("KpiGroup") && includeTotal && !dimensions.isEmpty())continue;
                    var component=component(format,queryId,schema,views.size(),prompt.components(),r.kind()==QuerySpec.Mode.AGGREGATE && dimensions.isEmpty());
                    if(component==null)return stop(IrStatus.UNSUPPORTED,"요청한 표현과 조회 결과의 열 형식이 맞지 않습니다.",List.of());views.add(component);
                }
                if(views.size()==groupViewStart && !dimensions.isEmpty()) {
                    var table=component("PaginatedDataTable",queryId,schema,views.size(),prompt.components(),false);
                    if(table==null)return stop(IrStatus.UNSUPPORTED,"집계 결과에 사용할 승인된 표가 없습니다.",List.of());views.add(table);
                }
                if(includeTotal && hasExplicitTotal && r.presentations().contains("KpiGroup"))deferredTotalKpis.add(q);
                if(includeTotal && !hasExplicitTotal) {
                    String totalId="q"+queries.size();var total=new QuerySpec(totalId,source,grain,QuerySpec.Mode.AGGREGATE,List.of(),metrics,filter,List.of(),new QuerySpec.Page(0,1),1,null);queries.add(total);
                    var component=component(tableOnlyTotal(prompt)?"DataTable":"KpiGroup",totalId,QueryPipeline.schema(registry,total),views.size(),prompt.components(),true);
                    if(component==null)component=component("PaginatedDataTable",totalId,QueryPipeline.schema(registry,total),views.size(),prompt.components(),true);
                    if(component==null)invalid("No total presentation available");views.add(component);
                }
            }
        }
        // A grouped request may ask for a KPI whose explicit scalar dataset is later in the plan.
        for(var grouped:deferredTotalKpis) {
            var total=queries.stream().filter(q->q.mode()==QuerySpec.Mode.AGGREGATE && q.dimensions().isEmpty()
                    && q.source().equals(grouped.source()) && q.grain().equals(grouped.grain())
                    && new HashSet<>(q.metrics().stream().map(QuerySpec.Selection::field).toList()).equals(new HashSet<>(grouped.metrics().stream().map(QuerySpec.Selection::field).toList())) && Objects.equals(q.filter(),grouped.filter())).findFirst().orElseThrow();
            if(views.stream().noneMatch(c->c.datasetId().equals(total.id()) && c.componentRef().id().equals("KpiGroup"))) {
                var component=component("KpiGroup",total.id(),QueryPipeline.schema(registry,total),views.size(),prompt.components(),true);
                if(component==null)invalid("No total KPI presentation available");views.add(component);
            }
        }
        for(var component:views)if(presentationExcluded(component.componentRef().id(),prompt.data().question()))
            return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"요청에서 제외한 화면 표현은 사용할 수 없습니다.",List.of());
        if(views.isEmpty())return stop(IrStatus.UNSUPPORTED,"요청한 결과에 사용할 승인된 화면 구성이 없습니다.",List.of());
        var view=new ViewSpec(prompt.components().catalogVersion(),views,Layout.stack(views.stream().map(c->Layout.leaf(c.id())).toList()));
        return new IrDocument(IrDocument.VERSION,IrStatus.READY,null,List.of(),null,queries,view,List.copyOf(used));
    }
    private static boolean sameAnalysisInputs(QuerySpec left,QuerySpec right) {
        return left.source().equals(right.source()) && left.grain().equals(right.grain())
                && Objects.equals(left.filter(),right.filter()) && left.orderBy().equals(right.orderBy())
                && left.pagination().equals(right.pagination()) && left.limit()==right.limit();
    }
    /** A business distribution/summary is not an explicit choice of chart/KPI component. */
    private static boolean explicitPresentation(String question) {
        return matches(question,"(?<!지)표(?:로|를|와|에|만|는|가| 형태| 형식|\\s*(?:말고|제외|아닌)|$)|table|목록|트리맵|treemap|순위|rank|워터폴|waterfall|퍼널|funnel|캘린더|calendar|agenda|줄씩|행씩|차트|chart|그래프|graph|막대|도넛|donut|비중|KPI|히트맵|heatmap|산점도|scatter|상세\\s*카드|detail card|첫\\s*[0-9]+\\s*행");
    }
    private static SemanticPlan.Request defaultPresentations(SemanticPlan.Request request,PromptPackage prompt) {
        if(request.presentations().isEmpty() || explicitPresentation(prompt.data().question()))return request;
        return new SemanticPlan.Request(request.kind(),request.source(),request.analysisId(),request.metrics(),request.dimensions(),request.filter(),request.order(),request.page(),request.size(),request.limit(),request.total(),List.of(),request.evidence(),request.formulas());
    }
    /** A model's unsolicited aggregate ranking must not become the user's requested order. */
    private static SemanticPlan.Request defaultOrder(SemanticPlan.Request request,PromptPackage prompt,QueryIntent intent) {
        if(request.kind()!=QuerySpec.Mode.AGGREGATE || request.order().isEmpty() || intent.order()!=null
                || matches(prompt.data().question(),"정렬|오름|내림|높은|낮은|큰|작은|많은|적은|최대|최소|상위|하위|최고|최저|순위|순서|[가-힣]순|top\\b|bottom\\b|rank|sort|order|ascend|descend|highest|lowest|largest|smallest|most|least"))return request;
        var selected=new HashSet<>(request.metrics());selected.addAll(request.dimensions());
        // Unselected/unknown fields remain subject to the existing evidence and ID validation.
        if(request.order().stream().anyMatch(sort->!selected.contains(sort.field())))return request;
        return new SemanticPlan.Request(request.kind(),request.source(),request.analysisId(),request.metrics(),request.dimensions(),request.filter(),List.of(),request.page(),request.size(),request.limit(),request.total(),request.presentations(),request.evidence(),request.formulas());
    }
    /** Conservatively require a positive table request without another presentation or negation. */
    private static boolean tableOnlyTotal(PromptPackage prompt) {
        String question=prompt.data().question();
        if(prompt.data().previous()!=null && prompt.data().previous().total() && repeats(question) && !explicitPresentation(question))return true;
        return matches(question,"(?<!지)표(?:로|를|와|에| 형태| 형식|$)|table")
                && !matches(question,"KPI|요약|차트|chart|막대|도넛|donut|비중|히트맵|heatmap|산점도|scatter|상세\\s*카드|detail card|아닌|제외|빼고|말고|않|not |without ");
    }
    /** A scalar paired with the resolved grouping is intentional, not a missing grouping slot. */
    private static boolean explicitPopulationTotal(SemanticPlan.Request request,List<SemanticPlan.Request> requests,QueryIntent intent) {
        return intent.total() && !intent.dimensions().isEmpty() && request.kind()==QuerySpec.Mode.AGGREGATE
                && request.dimensions().isEmpty() && new HashSet<>(request.metrics()).equals(intent.metrics())
                && requests.stream().anyMatch(grouped->grouped.kind()==QuerySpec.Mode.AGGREGATE
                    && new HashSet<>(grouped.metrics()).equals(intent.metrics())
                    && new HashSet<>(grouped.dimensions()).equals(intent.dimensions()));
    }
    private boolean samePopulation(SemanticPlan.Request grouped,SemanticPlan.Request total,QueryIntent intent,Filter filter) {
        var metric=registry.metrics().get(grouped.metrics().getFirst());
        return new HashSet<>(grouped.metrics()).equals(new HashSet<>(total.metrics()))
                && (total.source()==null || total.source().equals(metric.source()))
                && Objects.equals(filter,total.filter()==null?intent.period():total.filter());
    }
    private List<QuerySpec.Selection> selections(List<String> ids,String prefix){if(new HashSet<>(ids).size()!=ids.size())invalid("Duplicate selection");var out=new ArrayList<QuerySpec.Selection>();for(String id:ids)out.add(new QuerySpec.Selection(id,prefix+out.size()));return out;}
    private static String canonicalComponent(String format,ComponentCatalog catalog){return format.equals("DataTable") && catalog.components().stream().anyMatch(d->d.id().equals("PaginatedDataTable"))?"PaginatedDataTable":format;}
    private ViewSpec.Component component(String format,String dataset,List<TypedDataset.Column> columns,int index,ComponentCatalog catalog,boolean scalar) {
        String selectedFormat=canonicalComponent(format,catalog);
        var definition=catalog.components().stream().filter(d->d.id().equals(selectedFormat)).findFirst().orElse(null);
        if(definition==null && format.equals("PaginatedDataTable"))definition=catalog.components().stream().filter(d->d.id().equals("DataTable")).findFirst().orElse(null);if(definition==null)return null;
        if(format.equals("KpiGroup") && !scalar)return null;
        var bindings=new LinkedHashMap<String,Object>();
        for(var entry:definition.dataSlots().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var choices=columns.stream().filter(c->entry.getValue().types().contains(c.type())).map(TypedDataset.Column::alias).toList();
            if(choices.isEmpty()){if(entry.getValue().required())return null;continue;}
            if(!entry.getValue().multiple() && choices.size()!=1)return null;
            bindings.put(entry.getKey(),entry.getValue().multiple()?choices:choices.getFirst());
        }
        var events=definition.events().stream().filter(e->Set.of("pageChange","sortChange","export").contains(e.event())).sorted(Comparator.comparing(ViewSpec.Event::event)).toList();
        return new ViewSpec.Component("c"+index,new ViewSpec.Ref(definition.id(),definition.version()),dataset,bindings,Map.of(),events);
    }
    private ViewSpec.Component registeredComponent(com.openstudy.domainlens.api.formula.FormulaDefinition.Presentation presentation,String dataset,int index,ComponentCatalog catalog,Set<String> allowedEvents) {
        if(presentation==null)return null;
        var definition=catalog.components().stream().filter(d->d.id().equals(presentation.component())).findFirst().orElse(null);
        if(definition==null)return null;
        var events=definition.events().stream().filter(e->allowedEvents==null || allowedEvents.contains(e.event())).sorted(Comparator.comparing(ViewSpec.Event::event)).toList();
        return new ViewSpec.Component("c"+index,new ViewSpec.Ref(definition.id(),definition.version()),dataset,presentation.bindings(),Map.of(),events);
    }
    private boolean selectionProof(SemanticPlan.Request request,String id,PromptPackage prompt,QueryIntent intent) {
        var question=prompt.data().question();
        if(labels(id,prompt).stream().anyMatch(label->Pattern.compile(Pattern.quote(label)+"\\s*(?:은|는|을|를)?\\s*(?:제외|빼고|말고|조회하지|보여주지)|(?:without|not)\\s+"+Pattern.quote(label),Pattern.CASE_INSENSITIVE).matcher(question).find()))return false;
        // Consumer-resolved user meaning is independent proof, like the resolved period above.
        if(intent.metrics().contains(id) || intent.dimensions().contains(id) || intent.analyses().contains(id))return true;
        return request.evidence().stream().anyMatch(e->e.slot().equals(id) && switch(e.origin()) {
            case QUESTION -> quote(prompt,e) && (intent.metrics().contains(id) || intent.dimensions().contains(id) || intent.analyses().contains(id) || labels(id,prompt).stream().anyMatch(label->contains(e.quote(),label)));
            case PREVIOUS -> prompt.data().previous()!=null && repeats(prompt.data().question()) && (prompt.data().previous().dimensions().contains(id) || prompt.data().previous().metrics().contains(id) || prompt.data().previous().source().equals(id));
            case CONTRACT -> false; // Availability never establishes the user's selected meaning.
        });
    }
    private List<String> labels(String id,PromptPackage prompt) {
        var labels=new ArrayList<String>();labels.add(id);
        // Human-approved business aliases are data; a shared alias does not resolve an ambiguous formula.
        prompt.data().formulas().stream().filter(f->f.id().equals(id)).flatMap(f->f.aliases().stream())
            .filter(alias->prompt.data().formulas().stream().filter(f->f.aliases().stream().anyMatch(name->name.equalsIgnoreCase(alias))).count()==1).forEach(labels::add);
        prompt.data().context().stream().filter(n->n.id().equals(id)).forEach(n->{Object name=n.attributes().get("name");if(name instanceof String text && !text.isBlank())labels.add(text);Object aliases=n.attributes().get("aliases");if(aliases instanceof Collection<?> c)c.forEach(a->labels.add(a.toString()));});
        prompt.data().context().stream().filter(n->n.kind().equals("term") && id.equals(n.attributes().get("target"))).forEach(n->{if(n.attributes().get("aliases") instanceof Collection<?> c)c.forEach(a->labels.add(a.toString()));});
        return labels;
    }
    private boolean optionProof(SemanticPlan.Request r,String slot,PromptPackage prompt){return optionProof(r,slot,prompt,QueryIntent.unknown());}
    private boolean optionProof(SemanticPlan.Request r,String slot,PromptPackage prompt,QueryIntent intent) {
        if(slot.equals("order") && intent.order()!=null && intent.orderField()!=null)
            return r.order().size()==1 && r.order().getFirst().field().equals(intent.orderField()) && r.order().getFirst().direction()==intent.order();
        if(slot.equals("total") && matches(prompt.data().question(),"(?:전체\\s*(?:매출|합계)|총\\s*합계|grand\\s*total|population\\s*total).{0,16}(?:제외|빼고|말고|보여주지|조회하지|원하지)|(?:not|without)\\s+(?:grand\\s*|population\\s*)?total"))return false;
        if(slot.equals("presentation"))return r.presentations().stream().allMatch(format->presentationProof(format,r,prompt));
        Integer firstRows=firstRows(prompt.data().question());
        if(firstRows!=null && Set.of("page","size","limit").contains(slot))return switch(slot){case "page"->Objects.equals(r.page(),0);case "size"->Objects.equals(r.size(),firstRows);default->Objects.equals(r.limit(),firstRows);};
        Integer perPage=rowsPerPage(prompt.data().question());
        if(perPage!=null && slot.equals("size"))return Objects.equals(r.size(),perPage);
        Integer firstPage=firstPageSize(prompt.data().question());
        if(firstPage!=null && Set.of("page","size").contains(slot))return slot.equals("page")?Objects.equals(r.page(),0):Objects.equals(r.size(),firstPage);
        return r.evidence().stream().anyMatch(e->e.slot().equals(slot) && e.origin()==SemanticPlan.Origin.QUESTION && quote(prompt,e) && switch(slot){
            case "total"->matches(e.quote(),"전체|총합|총액|합계|total");
            case "presentation"->false;
            case "order"->matches(e.quote(),"정렬|오름|내림|작은|큰|낮은|높은|ascending|descending|sort") && r.order().stream().allMatch(s->s.direction()==QuerySpec.Direction.ASC?matches(e.quote(),"오름|작은|낮은|ascending"):matches(e.quote(),"내림|큰|높은|descending"));
            default->{Integer v=switch(slot){case "page"->r.page();case "size"->r.size();default->r.limit();};yield v!=null && matches(e.quote(),switch(slot){case "page"->"페이지|page";case "size"->"페이지|행|개|size";default->"최대|건|행|개|limit|top";}) && Pattern.compile("(?<![0-9])"+v+"(?![0-9])").matcher(e.quote()).find();}
        });
    }
    private boolean presentationProof(String format,SemanticPlan.Request r,PromptPackage prompt) {
        String question=prompt.data().question();
        if(presentationExcluded(format,question))return false;
        if(format.equals("BarChart") && !matches(question,"막대|bar chart") && matches(question,"도넛|donut|선\\s*차트|line chart|히트맵|heatmap"))return false;
        if(Set.of("DataTable","PaginatedDataTable").contains(format) && rowsPerPage(question)!=null)return true;
        String expression=switch(format) {
            case "BarChart"->"막대|bar chart|(?<![가-힣A-Za-z])차트(?:[와를로에\\s]|$)";
            case "DonutChart"->"도넛|비중|donut";
            case "LineChart"->"선\\s*차트|line chart";
            case "AreaChart"->"영역\\s*차트|area chart";
            case "KpiGroup"->"KPI|요약";
            case "DataTable","PaginatedDataTable"->"(?<!지)표(?:로|를|와|에|만| 형태| 형식|$)|table|목록|첫\\s*[0-9]+\\s*행";
            case "Treemap"->"트리맵|treemap";
            case "RankList"->"순위|rank";
            case "WaterfallChart"->"워터폴|waterfall";
            case "EvidenceList"->"근거|evidence";
            case "Funnel"->"퍼널|funnel";
            case "ActionQueue"->"미반납.*목록|조회.*목록";
            case "StackedBar100"->"100%|누적.*막대|stacked";
            case "CauseCards"->"원인.*카드|장르별.*연체";
            case "Calendar"->"캘린더|calendar";
            case "Agenda"->"일정|agenda";
            case "DetailCard"->"상세 카드|detail card";
            default->"(?!)";
        };
        if(format.equals("KpiGroup") && r.kind()==QuerySpec.Mode.REGISTERED_ANALYSIS
                && matches(question,"합계|총합|총액|전체|total|summary")
                && r.presentations().stream().anyMatch(f->Set.of("DataTable","PaginatedDataTable").contains(f) && presentationProof(f,r,prompt))
                && prompt.data().formulas().stream().anyMatch(f->f.id().equals(r.analysisId()) && f.presentations().stream().anyMatch(p->p.component().equals("KpiGroup"))))return true;
        return matches(question,expression) || r.evidence().stream().anyMatch(e->e.slot().equals("presentation") && e.origin()==SemanticPlan.Origin.QUESTION && quote(prompt,e) && matches(e.quote(),expression));
    }
    private static boolean presentationExcluded(String format,String question) {
        if(!Set.of("DataTable","PaginatedDataTable").contains(format) && matches(question,"(?<!지)표\\s*만|only\\s+(?:a\\s+)?table|table\\s+only"))return true;
        // Reject exclusion of this format, not an unrelated approved business exclusion.
        String excluded=switch(format){case "BarChart"->"막대|bar chart|차트";case "DonutChart"->"도넛|donut";case "LineChart"->"선\\s*차트|line chart";case "AreaChart"->"영역\\s*차트|area chart";case "KpiGroup"->"KPI|요약";case "DataTable","PaginatedDataTable"->"(?<!지)표(?!시)|table|첫\\s*[0-9]+\\s*행";case "Treemap"->"트리맵|treemap";
            case "RankList"->"순위|rank";
            case "WaterfallChart"->"워터폴|waterfall";
            case "EvidenceList"->"근거|evidence";
            case "Funnel"->"퍼널|funnel";
            case "ActionQueue"->"미반납.*목록|조회.*목록";
            case "StackedBar100"->"100%|누적.*막대|stacked";
            case "CauseCards"->"원인.*카드|장르별.*연체";
            case "Calendar"->"캘린더|calendar";
            case "Agenda"->"일정|agenda";
            case "DetailCard"->"상세 카드|detail card";default->"(?!)";};
        return matches(question,"(?:"+excluded+").{0,8}(?:아닌|제외|빼고|말고|않|보여주지|조회하지|하지\\s*마)|(?:not|without)\\s+(?:"+excluded+")");
    }
    private static Integer firstRows(String question) {
        if(matches(question,"아닌|제외|빼고|말고|않|not |without "))return null;
        var match=Pattern.compile("첫\\s*([0-9]+)\\s*행").matcher(question);if(!match.find())return null;
        try {int value=Integer.parseInt(match.group(1));return value>0 && value<=100 && !match.find()?value:null;}
        catch(NumberFormatException invalid){return null;}
    }
    /** One explicit first table page. It specifies page size, never a population limit. */
    private static Integer firstPageSize(String question) {
        if(matches(question,"아닌|제외|빼고|말고|않|not |without |(?:[2-9][0-9]*|두|세|둘째|셋째)\\s*(?:번째\\s*)?페이지")
                || !matches(question,"(?<!지)표|table"))return null;
        var match=Pattern.compile("첫\\s*페이지\\s*([0-9]+)\\s*(?:개|행|건)").matcher(question);if(!match.find())return null;
        try {int value=Integer.parseInt(match.group(1));return value>0 && value<=100 && !match.find()?value:null;}
        catch(NumberFormatException invalid){return null;}
    }
    /** A row count per page does not specify a population limit or a page number. */
    private static Integer rowsPerPage(String question) {
        if(matches(question,"아닌|제외|빼고|말고|않|not |without "))return null;
        var match=Pattern.compile("(?<![0-9가-힣])([0-9]+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(?:행|줄)씩").matcher(question);
        if(!match.find())return null;
        var words=List.of("","한","두","세","네","다섯","여섯","일곱","여덟","아홉","열");
        try {int value=words.contains(match.group(1))?words.indexOf(match.group(1)):Integer.parseInt(match.group(1));return value>0 && value<=100 && !match.find()?value:null;}
        catch(NumberFormatException invalid){return null;}
    }
    private static SemanticPlan.Request resolveFirstRows(SemanticPlan.Request r,PromptPackage prompt) {
        Integer rows=firstRows(prompt.data().question());Integer count=rows==null?firstPageSize(prompt.data().question()):rows;boolean perPage=false;if(count==null){count=rowsPerPage(prompt.data().question());perPage=true;}if(count==null)return r;
        // Only fill omitted slots. A conflicting model value must still fail the evidence check.
        var formats=new ArrayList<>(r.presentations());
        if(formats.stream().noneMatch(f->Set.of("DataTable","PaginatedDataTable").contains(f)))formats.add("PaginatedDataTable");
        if(matches(prompt.data().question(),"KPI") && !formats.contains("KpiGroup"))formats.add("KpiGroup");
        return new SemanticPlan.Request(r.kind(),r.source(),r.analysisId(),r.metrics(),r.dimensions(),r.filter(),r.order(),r.page()==null&&!perPage?Integer.valueOf(0):r.page(),r.size()==null?count:r.size(),r.limit()==null&&rows!=null?count:r.limit(),r.total(),formats,r.evidence(),r.formulas());
    }
    private boolean filterProof(SemanticPlan.Request r,PromptPackage prompt,QueryIntent intent) {
        if(intent.period()!=null && sameConjunction(r.filter(),intent.period()))return true;
        if(prompt.data().previous()!=null && repeats(prompt.data().question()) && ContractJson.canonical(r.filter()).equals(ContractJson.canonical(prompt.data().previous().filter())))return true;
        var proof=r.evidence().stream().filter(e->e.slot().equals("filter") && e.origin()==SemanticPlan.Origin.QUESTION && quote(prompt,e)).findFirst();if(proof.isEmpty())return false;
        var leaves=new ArrayList<Filter.Leaf>();leaves(r.filter(),leaves);
        var months=CalendarPeriod.months(prompt.data().question(),ZoneId.of(prompt.data().calendarZone()));
        if(months!=null && leaves.size()==2 && leaves.stream().allMatch(l->registry.fields().containsKey(l.field()) && registry.fields().get(l.field()).type()==ValueType.TIMESTAMP)) {
            var field=leaves.getFirst().field();
            return sameConjunction(r.filter(),new Filter.Group(Filter.Junction.AND,List.of(new Filter.Leaf(field,Filter.Operator.GTE,List.of(months.startInclusive().toString())),new Filter.Leaf(field,Filter.Operator.LT,List.of(months.endExclusive().toString())))));
        }
        var annual=CalendarPeriod.year(prompt.data().question(),ZoneId.of(prompt.data().calendarZone()));
        if(annual!=null && leaves.size()==2 && leaves.stream().allMatch(l->registry.fields().containsKey(l.field()) && registry.fields().get(l.field()).type()==ValueType.TIMESTAMP)) {
            var field=leaves.getFirst().field();
            var expected=new Filter.Group(Filter.Junction.AND,List.of(new Filter.Leaf(field,Filter.Operator.GTE,List.of(annual.startInclusive().toString())),new Filter.Leaf(field,Filter.Operator.LT,List.of(annual.endExclusive().toString()))));
            return sameConjunction(r.filter(),expected);
        }
        var month=Pattern.compile("(?<![0-9])(20[0-9]{2})[-년/]\\s*(0?[1-9]|1[0-2])(?:월|(?=[^0-9]|$))").matcher(proof.get().quote());
        if(month.find() && leaves.size()==2 && leaves.stream().allMatch(l->registry.fields().containsKey(l.field()) && registry.fields().get(l.field()).type()==ValueType.TIMESTAMP)) {
            try {var ym=YearMonth.of(Integer.parseInt(month.group(1)),Integer.parseInt(month.group(2)));var zone=ZoneId.of(prompt.data().calendarZone());String first=ym.atDay(1).atStartOfDay(zone).toInstant().toString(),next=ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toString();
                return r.filter() instanceof Filter.Group g && g.junction()==Filter.Junction.AND && leaves.stream().anyMatch(l->l.operator()==Filter.Operator.GTE && l.values().equals(List.of(first))) && leaves.stream().anyMatch(l->l.operator()==Filter.Operator.LT && l.values().equals(List.of(next))) && leaves.getFirst().field().equals(leaves.getLast().field());
            }catch(DateTimeException ex){return false;}
        }
        return !leaves.isEmpty() && leaves.stream().allMatch(l->operatorProof(l,proof.get().quote()) && labels(l.field(),prompt).stream().anyMatch(label->contains(proof.get().quote(),label)) && l.values().stream().allMatch(v->proof.get().quote().contains(v.toString())));
    }
    /** The same instant written with an offset (+09:00) or in UTC (Z) is the same bound. */
    private static Object instant(Object value){if(!(value instanceof String text))return value;try{return java.time.OffsetDateTime.parse(text).toInstant().toString();}catch(java.time.DateTimeException notTimestamp){return value;}}
    private static boolean sameConjunction(Filter actual,Filter expected) {
        var left=new ArrayList<String>();var right=new ArrayList<String>();
        if(!conjunction(actual,left) || !conjunction(expected,right))return false;
        Collections.sort(left);Collections.sort(right);return left.equals(right);
    }
    private static boolean conjunction(Filter filter,List<String> leaves) {
        if(filter instanceof Filter.Leaf leaf){leaves.add(ContractJson.canonical(new Filter.Leaf(leaf.field(),leaf.operator(),leaf.values().stream().map(PlanCompiler::instant).toList())));return true;}
        return filter instanceof Filter.Group group && group.junction()==Filter.Junction.AND && !group.children().isEmpty() && group.children().stream().allMatch(child->conjunction(child,leaves));
    }
    private static boolean operatorProof(Filter.Leaf leaf,String quote){return matches(quote,switch(leaf.operator()) {
        case EQ -> "같은|일치|=|인 |은 |is |equals";case NE -> "아닌|제외|빼고|!=|not";case IN -> "중|목록|또는|하나|in ";
        case GTE -> "이상|>=|부터|at least";case GT -> "초과|>|after";case LTE -> "이하|<=|at most";case LT -> "미만|<|before";
        case BETWEEN -> "사이|between";case IS_NULL -> "없는|없음|null";
    });}
    private static void leaves(Filter f,List<Filter.Leaf> out){if(f instanceof Filter.Leaf l)out.add(l);else if(f instanceof Filter.Group g)g.children().forEach(c->leaves(c,out));}
    private static boolean quote(PromptPackage p,SemanticPlan.Evidence e){return e.quote()!=null && !e.quote().isBlank() && p.data().question().contains(e.quote());}
    private static boolean contains(String text,String label){return !label.isBlank() && text.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT));}
    private static boolean matches(String text,String regex){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(text).find();}
    private static boolean repeats(String ask){return matches(ask,"방금|이전|같은|그대로|다시|previous|same");}
    private List<ClarificationOption> choices(Set<String> ids,PromptPackage prompt){return ids.stream().filter(prompt.contextRefs()::contains).limit(3).map(id->new ClarificationOption(id,labels(id,prompt).stream().filter(l->!l.equals(id)).findFirst().orElse(id))).toList();}
    /** Shared by semantic planning and direct IR generation; policy refusal requires server evidence. */
    public static IrDocument confirmModelReason(IrDocument ir,PromptPackage prompt){
        if(ir.reasonCode()==ReasonCode.KNOWLEDGE_MISSING && (!prompt.data().context().isEmpty() || !prompt.data().analyses().isEmpty() || !prompt.data().formulas().isEmpty()))
            return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"제공된 업무 정의와 질문의 연결을 검증하지 못했습니다.",List.of());
        if(ir.reasonCode()==ReasonCode.FORMULA_CONFLICT)
            return stop(IrStatus.NEEDS_CLARIFICATION,ReasonCode.SEMANTIC_GROUNDING_FAILED,"요청과 승인 공식의 관계를 검증하지 못했습니다.",List.of());
        return ir;
    }
    public static IrDocument stop(IrStatus status,String reason,List<ClarificationOption> options){return stop(status,ReasonCode.defaultFor(status),reason,options);}
    public static IrDocument stop(IrStatus status,ReasonCode code,String reason,List<ClarificationOption> options){return new IrDocument(IrDocument.VERSION,status,status==IrStatus.NEEDS_CLARIFICATION?reason:null,status==IrStatus.NEEDS_CLARIFICATION?options:List.of(),status==IrStatus.UNSUPPORTED?reason:null,List.of(),null,List.of(),code);}
    private static void invalid(String reason){throw new EngineException(EngineErrorCode.IR_INVALID,reason);}
}
