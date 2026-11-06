package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.security.Identity;
import com.openstudy.domainlens.security.SensitiveTokens;
import com.openstudy.domainlens.api.prompt.*;
import java.util.*;
import java.time.Instant;
/** Synchronous core used by playback and future request orchestration. */
public final class QueryPipeline {
    private com.openstudy.domainlens.catalog.FormulaRegistry formulas;
    public QueryPipeline formulas(com.openstudy.domainlens.catalog.FormulaRegistry value){formulas=value;validator.formulas(value);return this;}
    private Set<String> retainedContext;
    private boolean omitExamples;
    /** Explicit evaluation-only removal. Cannot add nodes, contracts or instructions. */
    public QueryPipeline promptAblation(Set<String> retained,boolean examples){retainedContext=retained==null?null:Set.copyOf(retained);omitExamples=examples;return this;}
    private PromptPackage ablate(PromptPackage p){
        if(retainedContext==null&&!omitExamples)return p;
        var d=p.data();var nodes=retainedContext==null?d.context():d.context().stream().filter(n->retainedContext.contains(n.id())).toList();
        var ids=nodes.stream().map(PromptPackage.Node::id).toList();
        var kept=omitExamples?List.<PromptPackage.Example>of():d.examples().stream().filter(e->ids.containsAll(e.ir().contextRefs())).toList();
        var data=new PromptPackage.Data(d.question(),d.asOf(),d.calendarZone(),d.displayZone(),d.snapshotVersion(),nodes,d.analyses(),kept,d.previous(),d.formulas(),d.planningVersion());
        return new PromptPackage(p.promptVersion(),p.systemInstructions(),data,p.irSchema(),p.viewSchema(),p.components());
    }
    private int planningAttempts=2;
    public QueryPipeline planningAttempts(int value){if(value<1 || value>10)throw new IllegalArgumentException("Planning attempts must be 1..10");planningAttempts=value;return this;}
    private java.util.function.Consumer<IrDocument> compiledObserver=ir->{};
    private java.util.function.Consumer<PromptPackage> planningObserver=p->{};
    public QueryPipeline planningObservations(java.util.function.Consumer<PromptPackage> prompts,java.util.function.Consumer<IrDocument> compiled){planningObserver=prompts;compiledObserver=compiled;return this;}

    private java.util.function.Consumer<String> ingressObserver=s->{};
    private java.util.function.BiConsumer<String,UserContext> ingressPolicy=(s,u)->{};
    /** Request-local observation of protected text, followed by policy, before external work. */
    public QueryPipeline ingress(java.util.function.Consumer<String> observer,java.util.function.BiConsumer<String,UserContext> policy){
        ingressObserver=Objects.requireNonNull(observer);ingressPolicy=Objects.requireNonNull(policy);return this;
    }
    private String ingress(String ask,SensitiveTokens tokens,UserContext user){
        String safe=tokens.protect(ask);ingressObserver.accept(safe);ingressPolicy.accept(safe,user);return safe;
    }
    private QueryIntent.Analyzer intentAnalyzer=QueryIntent.Analyzer.NONE;
    public QueryPipeline grounding(QueryIntent.Analyzer analyzer){intentAnalyzer=Objects.requireNonNull(analyzer);return this;}
    private TraceSpanCollector trace=TraceSpanCollector.NOOP;private String traceMode="live";
    public QueryPipeline tracing(TraceSpanCollector trace,String mode){this.trace=trace;this.traceMode=mode;return this;}
    private java.util.function.Consumer<String> observer=stage->{};
    public QueryPipeline observing(java.util.function.Consumer<String> observer) {
        var copy=new QueryPipeline(gateway,validator,planner,executor,binder,clock,registry,policyVersion,activation,examples,promptLimits);
        copy.retainedContext=retainedContext;copy.omitExamples=omitExamples;copy.planningAttempts=planningAttempts;copy.formulas=formulas;copy.compiledObserver=compiledObserver;copy.planningObserver=planningObserver;copy.ingressObserver=ingressObserver;copy.ingressPolicy=ingressPolicy;copy.intentAnalyzer=intentAnalyzer;copy.trace=trace;copy.traceMode=traceMode;copy.observer=Objects.requireNonNull(observer);return copy;
    }
    public record Outcome(IrDocument ir,List<ExecutionPlan> plans,ResultEnvelope result,List<ModelUsage> usage){
        public Outcome(IrDocument ir,List<ExecutionPlan> plans,ResultEnvelope result){this(ir,plans,result,List.of());}
        public Outcome {plans=List.copyOf(plans);usage=List.copyOf(usage);}
    }
    private final com.openstudy.domainlens.catalog.CatalogActivation activation;
    private final PromptExamples examples;
    private final PromptLimits promptLimits;
    private final ModelGateway gateway;private final IrValidator validator;private final JoinPlanner planner;private final QueryExecutor executor;private final ViewBinder binder;private final ClockPort clock;private final SemanticRegistry registry;private final String policyVersion;
    public QueryPipeline(ModelGateway gateway,IrValidator validator,JoinPlanner planner,QueryExecutor executor,ViewBinder binder,ClockPort clock,SemanticRegistry registry,String policyVersion){this(gateway,validator,planner,executor,binder,clock,registry,policyVersion,null);}
    public QueryPipeline(ModelGateway gateway,IrValidator validator,JoinPlanner planner,QueryExecutor executor,ViewBinder binder,ClockPort clock,SemanticRegistry registry,String policyVersion,com.openstudy.domainlens.catalog.CatalogActivation activation){this(gateway,validator,planner,executor,binder,clock,registry,policyVersion,activation,PromptExamples.empty(),PromptLimits.defaults());}
    public QueryPipeline(ModelGateway gateway,IrValidator validator,JoinPlanner planner,QueryExecutor executor,ViewBinder binder,ClockPort clock,SemanticRegistry registry,String policyVersion,com.openstudy.domainlens.catalog.CatalogActivation activation,PromptExamples examples,PromptLimits promptLimits){this.examples=examples;this.promptLimits=promptLimits;this.activation=activation;this.gateway=gateway;this.validator=validator;this.planner=planner;this.executor=executor;this.binder=binder;this.clock=clock;this.registry=registry;this.policyVersion=policyVersion;}
    public Outcome run(String ask,Set<String> allowed,UserContext user,Cancellation cancellation) {
        return run(ask,allowed,user,cancellation,null,null);
    }
    /** Direct approved-context entry point, also used by deterministic playback. */
    public Outcome run(String ask,Set<String> allowed,UserContext user,Cancellation cancellation,
            PreviousReferenceResolver.Request reference,PreviousReferenceResolver resolver) {
        String snapshotVersion=checkReady(user,cancellation);promptLimits.question(ask);
        try(var tokens=new SensitiveTokens(user)) {
            String safe=ingress(ask,tokens,user);
            var previous=reference==null?null:Objects.requireNonNull(resolver,"Reference resolver required").resolve(reference,user);
            return generate(safe,allowed.stream().sorted().toList(),previous,tokens,user,cancellation,snapshotVersion);
        }
    }
    private String checkReady(UserContext user,Cancellation cancellation) {
        Identity.require(user);cancellation.check();
        if(activation!=null){var active=activation.require();if(active.registry()!=registry || !active.policyVersion().equals(policyVersion) || (binder!=null && !active.components().equals(binder.catalog())))throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.NOT_READY,"Pipeline snapshot is stale");return active.version();}
        return registry.version();
    }
    private Outcome generate(String ask,List<String> selected,PreviousReferenceResolver.Conditions previous,
            SensitiveTokens tokens,UserContext user,Cancellation cancellation,String snapshotVersion) {
        requireSnapshot(snapshotVersion,user,cancellation);
        var intent=intentAnalyzer.analyze(ask,previous);
        if(intent.status()!=IrStatus.READY)return stopped(intent);
        var grounding=new QueryGrounding(registry,planner);
        var prompts=new PromptService(registry,validator,binder.catalog(),clock,examples,promptLimits);
        var budget=GenerationBudget.planning(gateway.requestTimeout(),planningAttempts);
        IrDocument ir=null;PromptPackage prompt;IrDocument generated=null;com.openstudy.domainlens.api.planning.SemanticPlan semantic=null;
        var plans=new ArrayList<ExecutionPlan>();
        Thread generationThread=Thread.currentThread();
        try(var onCancel=cancellation.register(()->{tokens.close();if(Thread.currentThread()!=generationThread)generationThread.interrupt();})) {
            cancellation.check();var assembled=intentAnalyzer==QueryIntent.Analyzer.NONE?prompts.assemble(ask,selected,snapshotVersion,previous,tokens,user):grounding.prepare(prompts.assemble(ask,selected,snapshotVersion,previous,tokens,user,intent.analyses().isEmpty()?null:intent.analyses()),intent,user);
            boolean resolved=!intent.analyses().isEmpty() || intent.metrics().size()==1 && !intent.dimensions().isEmpty()
                    && assembled.contextRefs().containsAll(intent.metrics()) && assembled.contextRefs().containsAll(intent.dimensions());
            prompt=ablate(formulas==null?assembled:FormulaPlanning.supply(assembled,formulas,validator,user,!resolved));
            final PromptPackage validationPrompt=prompt;
            prompts.limits().outbound(ContractJson.write(prompt));
            if(prompt.data().context().isEmpty() && prompt.data().analyses().isEmpty() && prompt.data().formulas().isEmpty()) {
                var refused=PlanCompiler.stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_MISSING,"승인된 데이터·지표·분석 정보가 제공되지 않아 실행할 수 없습니다.",List.of());
                compiledObserver.accept(refused);
                return new Outcome(refused,List.of(),new ResultEnvelope(UUID.randomUUID().toString(),UUID.randomUUID().toString(),refused.status().name(),List.of(),null,List.of(),refused.reasonCode().name(),clock.asOf(),registry.version(),policyVersion).withNotice(OutcomeNotice.from(refused)),List.of());
            }
            String failure=null,lastRejected=null;
            PlanCompiler.Retry compileRetry=PlanCompiler.Retry.TERMINAL;
            for(int attempt=0;attempt<planningAttempts;attempt++) {
                boolean plannedThisAttempt=false;
                try {
                    cancellation.check();observer.accept("generating");
                    String modelStage=traceMode.equals("live")?"model":traceMode.equals("canned")?"canned":"registeredAction";
                    trace.start(modelStage);trace.attribute("mode",traceMode);trace.attribute("attempt",Integer.toString(attempt+1));
                    try{
                        if(gateway.supportsPlanning()) {
                            planningObserver.accept(prompt);
                            semantic=gateway.plan(com.openstudy.domainlens.api.planning.PlanningPrompt.from(prompt),budget,semantic,failure);plannedThisAttempt=true;
                            var compilation=new PlanCompiler(registry).compileForPipeline(semantic,prompt,intent);
                            generated=compilation.ir();compileRetry=compilation.retry();
                            compiledObserver.accept(generated);
                        }else generated=attempt==0?gateway.generate(prompt,budget):gateway.repair(prompt,PromptService.repairCandidate(generated),failure,budget);
                    }
                    catch(RuntimeException ex){trace.failure(ex instanceof com.openstudy.domainlens.api.error.EngineException e?e.code().name():"MODEL_FAILED");throw ex;}
                    finally{trace.attribute("usage",ContractJson.write(budget.usages()));trace.end(modelStage);}
                    cancellation.check();observer.accept("validating");final IrDocument candidate=generated;ir=trace.measure("validation",()->{grounding.validate(intent,candidate);return prompts.restore(candidate,validationPrompt,tokens,user);});
                    if(!gateway.supportsPlanning())ir=PlanCompiler.confirmModelReason(ir,prompt);
                    requireSnapshot(snapshotVersion,user,cancellation);
                    var allowed=Set.copyOf(prompt.contextRefs());
                    if(activation!=null)for(var query:ir.queries())for(var metric:query.metrics())new ContextGate(registry).requireMetric(metric.field(),allowed);
                    if(ir.status()!=IrStatus.READY){
                        if(formulas!=null && semantic!=null && compileRetry==PlanCompiler.Retry.CORRECTABLE && gateway.supportsRepair() && attempt+1<planningAttempts){
                            failure=ir.clarification();
                            String rejected=planningFailure(semantic,ir.reasonCode()+":"+failure);
                            if(rejected.equals(lastRejected))break;
                            lastRejected=rejected;continue;
                        }
                        break;
                    }
                    var schemas=new LinkedHashMap<String,List<TypedDataset.Column>>();plans.clear();Instant snapshot=Instant.now();
                    for(var q:ir.queries()) {
                        var planned=trace.measure("authorization",()->planner.plan(q,user,prompt.data().asOf(),snapshot));
                        if(formulas!=null && q.mode()==QuerySpec.Mode.AGGREGATE && q.metrics().stream().allMatch(m->formulas.contains(m.field())))planned=planned.withFormulas(q.metrics().stream().map(m->formulas.ref(m.field())).toList());
                        plans.add(planned);
                        if(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS)schemas.putAll(validator.analysisDefinition(q.analysisId()).outputs());
                        else schemas.put(q.id(),schema(q));
                    }
                    var proposedView=ir.view();trace.measure("binding",()->{binder.prepare(proposedView,schemas);return null;});break;
                }catch(com.openstudy.domainlens.api.error.EngineException ex){
                    boolean correctable=ex.code()==com.openstudy.domainlens.api.error.EngineErrorCode.IR_INVALID || formulas!=null && planningAttempts>2 && gateway.supportsPlanning() && Set.of(com.openstudy.domainlens.api.error.EngineErrorCode.MODEL_TIMEOUT,com.openstudy.domainlens.api.error.EngineErrorCode.MODEL_TRUNCATED,com.openstudy.domainlens.api.error.EngineErrorCode.MODEL_INCOMPLETE).contains(ex.code());
                    if(attempt+1>=planningAttempts || !gateway.supportsRepair() || !correctable)throw ex;
                    failure=ex.getMessage();
                    if(ex.code()==com.openstudy.domainlens.api.error.EngineErrorCode.IR_INVALID && plannedThisAttempt){
                        String rejected=planningFailure(semantic,ex.code()+":"+failure);
                        if(rejected.equals(lastRejected)){
                            ir=PlanCompiler.stop(IrStatus.UNSUPPORTED,ReasonCode.KNOWLEDGE_INSUFFICIENT,"조회 계획의 동일한 검증 실패가 반복되어 요청을 종료했습니다.",List.of());break;
                        }
                        lastRejected=rejected;
                    }
                }
            }
        }catch(com.openstudy.domainlens.api.error.EngineException ex){throw new com.openstudy.domainlens.api.error.EngineException(ex.code(),ex.getMessage(),budget.usages());}
        catch(RuntimeException ex){throw ex;}
        catch(Exception ex){throw new IllegalStateException("Cannot release generation cancellation hook");}
        finally{tokens.close();}
        requireSnapshot(snapshotVersion,user,cancellation);
        String request=UUID.randomUUID().toString();String execution=UUID.randomUUID().toString();Instant asOf=prompt.data().asOf();
        ir=refuseUnresolved(ir);
        if(ir.status()!=IrStatus.READY)return new Outcome(ir,List.of(),new ResultEnvelope(request,execution,ir.status().name(),List.of(),null,List.of(),ir.reasonCode()==null?null:ir.reasonCode().name(),asOf,registry.version(),policyVersion).withNotice(OutcomeNotice.from(ir)),budget.usages());
        var datasets=new ArrayList<TypedDataset>();var savedPlans=new ArrayList<ExecutionPlan>();
        try {
        observer.accept("executing");
        for(var plan:plans){
            cancellation.check();var outputs=trace.measure("jpa",()->executor.executeAll(plan,user,cancellation));datasets.addAll(outputs);
            for(var ds:outputs){var q=plan.query();
                // A registered invocation can return detail, totals and distribution.
                // Persist only ordering defined on this output's columns, so rereading
                // a summary never applies the detail output's sort aliases.
                var aliases=ds.columns().stream().map(TypedDataset.Column::alias).collect(java.util.stream.Collectors.toSet());
                var order=q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS?q.orderBy().stream().filter(o->aliases.contains(o.alias())).toList():q.orderBy();
                var savedQuery=new QuerySpec(ds.id(),q.source(),q.grain(),q.mode(),q.dimensions(),q.metrics(),q.filter(),order,q.pagination(),q.limit(),q.analysisId());
                savedPlans.add(new ExecutionPlan(plan.formatVersion(),plan.registryVersion(),plan.policyVersion(),plan.owner(),plan.effectiveScope(),plan.asOf(),plan.snapshotAt(),savedQuery,plan.joins(),plan.rowFilters(),plan.uniqueKeys(),plan.timeoutSeconds(),plan.formulas()));}
        }
        var finalView=ir.view();var binding=trace.measure("binding",()->binder.bind(finalView,datasets));
        return new Outcome(ir,savedPlans,new ResultEnvelope(request,execution,"COMPLETED",datasets,binding.view(),binding.warnings(),null,asOf,registry.version(),policyVersion,FormulaPlanning.bindings(savedPlans,datasets),formulas==null?List.of():FormulaDisplay.render(formulas,savedPlans,datasets)),budget.usages());
        }catch(com.openstudy.domainlens.api.error.EngineException ex){throw new com.openstudy.domainlens.api.error.EngineException(ex.code(),ex.getMessage(),budget.usages());}
        catch(RuntimeException ex){
            boolean timeout=false;Throwable cause=ex;
            for(int i=0;cause!=null && i<8;i++,cause=cause.getCause())if(cause instanceof jakarta.persistence.QueryTimeoutException || cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState()))timeout=true;
            throw new com.openstudy.domainlens.api.error.EngineException(timeout?com.openstudy.domainlens.api.error.EngineErrorCode.QUERY_TIMEOUT:com.openstudy.domainlens.api.error.EngineErrorCode.INTERNAL_ERROR,timeout?"Query deadline exceeded":"Query execution failed",budget.usages());
        }
    }
    /** Ignore explanation text and evidence ordering; preserve all execution-affecting values. */
    private static String planningFailure(com.openstudy.domainlens.api.planning.SemanticPlan plan,String failure) {
        var requests=plan.requests().stream().map(r->{
            var normalized=new com.openstudy.domainlens.api.planning.SemanticPlan.Request(r.kind(),r.source(),r.analysisId(),r.metrics(),r.dimensions(),r.filter(),r.order(),r.page(),r.size(),r.limit(),r.total(),r.presentations(),r.evidence().stream().sorted(Comparator.comparing(ContractJson::canonical)).toList(),r.formulas());
            return ContractJson.canonical(normalized);
        }).toList();
        return ContractJson.canonical(Map.of("status",plan.status(),"requests",requests,"unresolved",plan.unresolved().stream().sorted().toList(),"failure",failure));
    }
    public Outcome run(String ask,UserContext user,Cancellation cancellation,RetrievalService retrieval) {
        return run(ask,user,cancellation,retrieval,null,null);
    }
    public Outcome run(String ask,UserContext user,Cancellation cancellation,RetrievalService retrieval,
            PreviousReferenceResolver.Request reference,PreviousReferenceResolver resolver) {
        if(activation==null)throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.NOT_READY,"Activation required");
        String snapshotVersion=checkReady(user,cancellation);promptLimits.question(ask);
        try(var tokens=new SensitiveTokens(user)) {
            String safe=ingress(ask,tokens,user);
            var previous=reference==null?null:Objects.requireNonNull(resolver,"Reference resolver required").resolve(reference,user);
            if(previous!=null)retrieval.requireVisible(previous.semanticRefs(),user);
            var preflight=intentAnalyzer.analyze(safe,previous);
            if(preflight.status()!=IrStatus.READY)return stopped(preflight);
            observer.accept("retrieving");
            var context=retrieve(safe,user,tokens,cancellation,retrieval,previous);
            requireSnapshot(snapshotVersion,user,cancellation);
            if(previous!=null) {
                var nodes=new ArrayList<>(context.nodes());
                var ids=new HashSet<>(context.ids());
                for(String id:previous.semanticRefs().stream().sorted().toList())if(ids.add(id))nodes.add(new Retriever.Hit(id,1,"verified-reference"));
                if(nodes.size()>40)throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.BUDGET_EXCEEDED,"Reference context budget exceeded");
                var seeds=context.seeds().isEmpty()?List.of(new Retriever.Hit(previous.source(),1,"verified-reference")):context.seeds();
                context=new Retriever.Context(context.sanitizedAsk(),seeds,nodes,context.graphVersion(),context.indexVersion(),context.truncated());
            }
            retrieval.requireBudget(context.ids());
            var decision=new ContextGate(registry).inspect(context);
            if(decision.status()==IrStatus.READY)return generate(context.sanitizedAsk(),context.nodes().stream().map(Retriever.Hit::id).toList(),previous,tokens,user,cancellation,snapshotVersion);
            var ir=refuseUnresolved(new IrDocument(IrDocument.VERSION,decision.status(),decision.status()==IrStatus.NEEDS_CLARIFICATION?decision.reason():null,decision.options(),decision.status()==IrStatus.UNSUPPORTED?decision.reason():null,List.of(),null,List.copyOf(decision.contextIds()),decision.reasonCode()));
            return new Outcome(ir,List.of(),new ResultEnvelope(UUID.randomUUID().toString(),UUID.randomUUID().toString(),ir.status().name(),List.of(),null,List.of(),ir.reasonCode()==null?null:ir.reasonCode().name(),clock.asOf(),registry.version(),policyVersion).withNotice(OutcomeNotice.from(ir)));
        }
    }
    private Outcome stopped(QueryIntent intent){
        var ir=refuseUnresolved(new IrDocument(IrDocument.VERSION,intent.status(),intent.status()==IrStatus.NEEDS_CLARIFICATION?intent.reason():null,List.of(),intent.status()==IrStatus.UNSUPPORTED?intent.reason():null,List.of(),null,List.of(),intent.reasonCode()));
        return new Outcome(ir,List.of(),new ResultEnvelope(UUID.randomUUID().toString(),UUID.randomUUID().toString(),ir.status().name(),List.of(),null,List.of(),ir.reasonCode()==null?null:ir.reasonCode().name(),clock.asOf(),registry.version(),policyVersion).withNotice(OutcomeNotice.from(ir)));
    }
    /** Current single-turn UI cannot resume clarification. Retain model diagnostics and publish a typed refusal. */
    private static IrDocument refuseUnresolved(IrDocument ir){
        if(ir.status()!=IrStatus.NEEDS_CLARIFICATION)return ir;
        var code=ir.reasonCode()==ReasonCode.REQUIRED_INPUT_MISSING?ReasonCode.REQUEST_INCOMPLETE:ReasonCode.KNOWLEDGE_INSUFFICIENT;
        return new IrDocument(IrDocument.VERSION,IrStatus.UNSUPPORTED,null,List.of(),ir.clarification(),List.of(),null,ir.contextRefs(),code);
    }
    private void requireSnapshot(String expected,UserContext user,Cancellation cancellation) {
        if(!expected.equals(checkReady(user,cancellation)))throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.NOT_READY,"Pipeline snapshot changed");
    }
    private Retriever.Context retrieve(String ask,UserContext user,SensitiveTokens tokens,Cancellation cancellation,RetrievalService retrieval,PreviousReferenceResolver.Conditions previous) {
        try(var onCancel=cancellation.register(tokens::close)) {
            cancellation.check();var result=retrieval.prepareContext(ask,user,tokens,promptLimits,text->{
                var intent=intentAnalyzer.analyze(text,previous);
                if(formulas==null)return validator.analysisDiscoverySources(intent.analyses());var ids=new HashSet<String>(intent.metrics());ids.addAll(intent.dimensions());PromptService.filterReferences(intent.period(),ids);
                ids.addAll(validator.analysisDiscoverySources(intent.analyses()));
                ids.retainAll(registry.publicNodeIds());return Set.copyOf(ids);
            });cancellation.check();return result;
        }catch(RuntimeException ex){cancellation.check();throw ex;}
        catch(Exception ex){throw new IllegalStateException("Cannot release retrieval cancellation hook");}
    }
    public List<TypedDataset.Column> schema(QuerySpec q) { return schema(registry,q); }
    static List<TypedDataset.Column> schema(SemanticRegistry registry,QuerySpec q) {
        var columns=new ArrayList<TypedDataset.Column>();for(var s:q.dimensions()){var f=registry.fields().get(s.field());columns.add(new TypedDataset.Column(s.alias(),f.type(),f.unit(),f.precision()));}
        for(var s:q.metrics()){var m=registry.metrics().get(s.field());ValueType type=m.aggregate()==SemanticRegistry.Aggregate.COUNT?ValueType.INTEGER:m.aggregate()==SemanticRegistry.Aggregate.AVG?ValueType.DECIMAL:registry.fields().get(m.field()).type();columns.add(new TypedDataset.Column(s.alias(),type,m.unit(),m.precision()));}
        return List.copyOf(columns);
    }
}
