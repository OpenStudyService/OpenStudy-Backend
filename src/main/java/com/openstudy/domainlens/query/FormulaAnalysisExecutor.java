package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.*;
import com.openstudy.domainlens.security.*;
import jakarta.persistence.*;
import org.hibernate.Session;
import java.time.*;
import java.util.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Domain-independent execution of registered relational formulas, including saved output actions. */
public class FormulaAnalysisExecutor implements AnalysisExecutor {
    private final EntityManagerFactory factory;
    private final SemanticRegistry semantic;
    private final FormulaRegistry formulas;
    private final PolicyEngine policies;
    private final IrValidator fields;
    public FormulaAnalysisExecutor(EntityManagerFactory factory,SemanticRegistry semantic,FormulaRegistry formulas,PolicyEngine policies,DateNormalizer dates){this.factory=factory;this.semantic=semantic;this.formulas=formulas;this.policies=policies;this.fields=new IrValidator(semantic,policies,dates);}
    @Override public List<FormulaDefinition.Presentation> presentations(String id){return formulas.get(id).presentations();}
    @Override public Set<String> presentationEvents(String id){return Set.of("pageChange","sortChange","export");}
    public FormulaRegistry formulas(){return formulas;}
    public Set<String> analysisIds(){return formulas.definitions().stream().filter(f->f.kind()==FormulaDefinition.Kind.ANALYSIS).map(FormulaDefinition::id).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    public boolean supports(String id){return analysisIds().contains(id);}
    public Optional<Definition> definition(String id){if(!supports(id))return Optional.empty();var f=formulas.get(id);var outputs=new LinkedHashMap<String,List<TypedDataset.Column>>();f.outputs().forEach(o->outputs.put(o.id(),o.columns()));return Optional.of(new Definition(id,f.version(),f.source(),f.grain(),outputs,true));}
    public Set<String> baseSources(String id){return formulas.get(id).baseSources();}
    public Set<String> discoverySources(String id){return formulas.get(id).discoverySources();}
    public String description(String id){return formulas.get(id).description();}
    public Map<String,InputField> inputFields(String id){var result=new LinkedHashMap<String,InputField>();formulas.get(id).inputs().forEach((key,value)->result.put(key,new InputField(value.type(),value.operators(),value.policyFields())));return Map.copyOf(result);}
    private FormulaDefinition.Output output(String id,String dataset){return formulas.get(id).outputs().stream().filter(o->o.id().equals(dataset)).findFirst().orElse(formulas.get(id).outputs().getFirst());}
    public boolean isSummaryOutput(String id,String dataset){return output(id,dataset).summary();}
    public int outputMaxRows(String id,String dataset){return output(id,dataset).maxRows();}
    public void validate(QuerySpec q,UserContext user){
        Identity.require(user);require(supports(q.analysisId()),"Formula analysis absent");var f=formulas.get(q.analysisId());
        require(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS && q.source().equals(f.source()) && q.grain().equals(f.grain()) && q.metrics().isEmpty() && q.dimensions().isEmpty(),"Formula source/grain mismatch");
        require(q.pagination()!=null && q.pagination().page()>=0 && q.pagination().size()>0 && q.pagination().size()<=100 && q.limit()>0 && q.limit()<=50,"Formula page budget");
        var source=semantic.sources().containsKey(q.source())?q.source():f.discoverySources().stream().sorted().findFirst().orElseThrow();
        policies.query(new QuerySpec(q.id(),source,q.grain(),q.mode(),List.of(),List.of(),policyFilter(q.filter()),List.of(),q.pagination(),q.limit(),q.analysisId()),user);
        f.baseSources().forEach(s->policies.source(s,user));for(String field:f.dependencies())for(String op:List.of("select","output","group"))policies.field(field,op,user);
        require(filter(q.filter(),f,user,0)<=16,"Formula filter budget");
        var permitted=f.outputs().stream().flatMap(o->o.columns().stream()).map(TypedDataset.Column::alias).collect(java.util.stream.Collectors.toSet());for(var order:q.orderBy()){require(permitted.contains(order.alias()) && order.direction()!=null,"Formula sort absent");f.dependencies().forEach(field->policies.field(field,"sort",user));}
    }
    private Filter policyFilter(Filter input) {
        if(input instanceof Filter.Leaf leaf)return semantic.fields().containsKey(leaf.field())?leaf:null;
        if(input instanceof Filter.Group group) {
            var children=group.children().stream().map(this::policyFilter).filter(Objects::nonNull).toList();
            return children.isEmpty()?null:new Filter.Group(group.junction(),children);
        }
        return null;
    }
    private int filter(Filter filter,FormulaDefinition f,UserContext user,int depth){
        if(filter==null)return 0;require(depth<4,"Formula filter depth");if(filter instanceof Filter.Group g){require(!g.children().isEmpty() && g.junction()!=null,"Invalid formula filter");return g.children().stream().mapToInt(c->filter(c,f,user,depth+1)).sum();}
        var l=(Filter.Leaf)filter;var input=f.inputs().get(l.field());require(input!=null && input.operators().contains(l.operator()),"Formula input absent");int n=l.values().size();require(switch(l.operator()){case IS_NULL->n==0;case BETWEEN->n==2;case IN->n>0 && n<=50;default->n==1;},"Formula input arity");
        input.policyFields().forEach(field->policies.field(field,"filter",user));for(Object value:l.values()){IrValidator.value(input.type(),value);if(!input.values().isEmpty())require(input.values().contains(value),"Formula input value absent");}
        if(semantic.fields().containsKey(l.field()))fields.filter(l);return 1;
    }
    public ExecutionPlan plan(QuerySpec q,UserContext user,Instant asOf,Instant snapshotAt){validate(q,user);require(asOf!=null && snapshotAt!=null,"Formula clock absent");var scope=policies.scope(baseSources(q.analysisId()),user);return new ExecutionPlan(ExecutionPlan.VERSION,semantic.version(),policies.version(),user.subjectId(),user.policyBinding().scope(),asOf,snapshotAt,q,List.of(),scope,formulas.get(q.analysisId()).outputs().getFirst().uniqueKeys(),5).withFormulas(List.of(formulas.ref(q.analysisId())));}
    public List<TypedDataset> executeAll(ExecutionPlan plan,UserContext user,Cancellation cancellation){return execute(plan,user,cancellation,null);}
    public void stream(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> sink){execute(plan,user,cancellation,sink);}
    private List<TypedDataset> execute(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> sink){
        validate(plan.query(),user);Identity.owner(user,plan.owner());require(plan.registryVersion().equals(semantic.version()),"Stored formula registry unavailable");require(!plan.formulas().isEmpty(),"Stored formula provenance absent");plan.formulas().forEach(formulas::requireRef);
        require(plan.formulas().equals(List.of(formulas.ref(plan.query().analysisId()))),"Stored formula selection mismatch");var current=policies.scope(baseSources(plan.query().analysisId()),user);
        require(plan.rowFilters().keySet().equals(current.keySet()),"Stored formula scope mismatch");
        var f=formulas.get(plan.query().analysisId());var compiler=new FormulaQueryCompiler(semantic,f,plan.query().filter(),plan.asOf(),plan.rowFilters(),current);
        try(var em=factory.createEntityManager()){
            var session=em.unwrap(Session.class);session.setDefaultReadOnly(true);em.setFlushMode(FlushModeType.COMMIT);var tx=em.getTransaction();tx.begin();long deadline=System.nanoTime()+5_000_000_000L;
            try(var hook=cancellation.register(session::cancelQuery)){
                var result=new ArrayList<TypedDataset>();
                for(var o:f.outputs()){
                    if(sink!=null && !o.id().equals(plan.query().id()))continue;
                    var ordering=plan.query().orderBy().stream().filter(order->o.columns().stream().anyMatch(c->c.alias().equals(order.alias()))).toList();
                    long count=o.summary()?0:((Number)rows(em,compiler.compile(o,List.of(),true),compiler.bindings(),0,1,deadline,cancellation).getFirst()[0]).longValue();
                    String hql=compiler.compile(o,ordering,false);
                    int page=o.summary()?0:plan.query().pagination().page();int size=o.summary()?o.maxRows():Math.min(o.maxRows(),plan.query().pagination().size());if(o.limitByQuery())size=Math.min(size,plan.query().limit());
                    if(sink!=null){var query=query(em,hql,compiler.bindings(),deadline,cancellation);query.setHint("org.hibernate.fetchSize",100);try(var stream=query.getResultStream()){var iterator=stream.iterator();while(iterator.hasNext()){remaining(deadline,cancellation);sink.accept(row(o,iterator.next()));}}continue;}
                    var rows=rows(em,hql,compiler.bindings(),page,size,deadline,cancellation);var values=rows.stream().map(r->row(o,r)).toList();
                    result.add(new TypedDataset(o.id(),o.columns(),values,o.summary()?values.size():count,page,size,plan.snapshotAt(),plan.asOf()));
                }
                remaining(deadline,cancellation);tx.commit();return result;
            }catch(RuntimeException ex){if(tx.isActive())tx.rollback();cancellation.check();for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState()))throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Formula deadline exceeded");throw ex;}catch(Exception ex){if(tx.isActive())tx.rollback();throw new IllegalStateException("Formula cleanup failed",ex);}
        }
    }
    private static Map<String,Object> row(FormulaDefinition.Output output,Object[] values){require(values.length==output.columns().size(),"Formula result arity");var row=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i++){var column=output.columns().get(i);Object value=values[i];if(value instanceof java.math.BigDecimal n && column.type()==ValueType.INTEGER)value=n.longValueExact();if(value instanceof Number n && column.type()==ValueType.DECIMAL)value=new java.math.BigDecimal(n.toString());row.put(column.alias(),value);}return row;}
    private static int remaining(long deadline,Cancellation cancellation){cancellation.check();long ms=(deadline-System.nanoTime())/1_000_000;if(ms<=0)throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Formula deadline exceeded");return (int)ms;}
    private static TypedQuery<Object[]> query(EntityManager em,String hql,Map<String,Object> bindings,long deadline,Cancellation cancellation){var query=em.unwrap(Session.class).createQuery(hql,Object[].class);for(var p:query.getParameters()){if(p.getName().equals("asOf"))query.setParameter("asOf",(String)bindings.get("asOf"),org.hibernate.type.StandardBasicTypes.STRING);else query.setParameter(p.getName(),bindings.get(p.getName()));}int budget=remaining(deadline,cancellation);query.setHint("jakarta.persistence.query.timeout",budget);return query;}
    private static List<Object[]> rows(EntityManager em,String hql,Map<String,Object> bindings,int page,int size,long deadline,Cancellation cancellation){long offset=(long)page*size;if(offset>Integer.MAX_VALUE)throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Formula page offset");return query(em,hql,bindings,deadline,cancellation).setFirstResult((int)offset).setMaxResults(size).getResultList();}
}
