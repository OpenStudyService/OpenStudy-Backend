package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.security.*;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import org.hibernate.Session;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import java.util.*;

/** Typed Criteria projection; all names are registry paths and all values are bound parameters. */
public final class JpaQueryExecutor implements QueryExecutor, ExportExecutor {
    private com.openstudy.domainlens.catalog.FormulaRegistry formulas;
    public JpaQueryExecutor formulas(com.openstudy.domainlens.catalog.FormulaRegistry value){formulas=value;return this;}
    private final EntityManagerFactory factory;private final SemanticRegistry registry;private final PolicyEngine policies;private final IrValidator validator;
    public JpaQueryExecutor(EntityManagerFactory factory,SemanticRegistry registry,PolicyEngine policies,IrValidator validator){this.factory=factory;this.registry=registry;this.policies=policies;this.validator=validator;}
    public TypedDataset execute(ExecutionPlan plan,UserContext user,Cancellation cancellation) {
        return execute(plan,user,cancellation,null);
    }
    public void stream(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> row){execute(plan,user,cancellation,row);}
    private TypedDataset execute(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> sink) {
        Identity.owner(user,plan.owner());cancellation.check();
        if(plan.formatVersion()!=ExecutionPlan.VERSION || !plan.registryVersion().equals(registry.version()))throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Stored plan version unsupported");
        validator.query(plan.query(),user);
        if(!plan.formulas().isEmpty()){
            ContractRules.require(formulas!=null,"Formula runtime unavailable");plan.formulas().forEach(formulas::requireRef);FormulaPlanning.validateInputs(formulas,plan.query());
            ContractRules.require(plan.formulas().stream().map(f->f.id()).collect(java.util.stream.Collectors.toSet()).equals(plan.query().metrics().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet())),"Stored formula selection mismatch");
        }
        var current=new JoinPlanner(registry,policies).plan(plan.query(),user,plan.asOf(),plan.snapshotAt());
        if(!current.joins().equals(plan.joins()) || !current.uniqueKeys().equals(plan.uniqueKeys()) || plan.timeoutSeconds()!=5 || !current.rowFilters().keySet().equals(plan.rowFilters().keySet()))throw new EngineException(EngineErrorCode.IR_INVALID,"Invalid stored plan");
        plan.rowFilters().values().forEach(validator::filter);
        try(var em=factory.createEntityManager()) {
            var session=em.unwrap(Session.class);session.setDefaultReadOnly(true);em.setFlushMode(FlushModeType.COMMIT);
            var transaction=em.getTransaction();transaction.begin();
            try(var hook=cancellation.register(session::cancelQuery)) {
                var builder=session.getCriteriaBuilder();JpaCriteriaQuery<Tuple> query=builder.createTupleQuery();
                var root=query.from(registry.sources().get(plan.query().source()).javaType());
                var from=new HashMap<String,From<?,?>>();from.put(plan.query().source(),root);
                var bindings=new LinkedHashMap<String,Object>();
                var where=new ArrayList<Predicate>();
                for(var j:plan.joins()) {
                    var sourceFrom=from.get(j.source());
                    var attr=factory.getMetamodel().entity(registry.sources().get(j.source()).javaType()).getAttribute(j.path());
                    if(attr.isAssociation()) {
                        var join=sourceFrom.join(j.path(),j.type()==ExecutionPlan.JoinType.INNER?JoinType.INNER:JoinType.LEFT);from.put(j.target(),join);
                        if(j.type()==ExecutionPlan.JoinType.LEFT)join.on(builder.and(predicate(plan.rowFilters().get(j.target()),builder,from,bindings),predicate(current.rowFilters().get(j.target()),builder,from,bindings)));
                    } else {
                        var joinRoot=query.from(registry.sources().get(j.target()).javaType());
                        from.put(j.target(),joinRoot);
                        where.add(builder.equal(sourceFrom.get(j.path()),joinRoot.get("id")));
                    }
                }
                where.add(predicate(plan.query().filter(),builder,from,bindings));
                for(String source:plan.rowFilters().keySet())if(source.equals(plan.query().source()) || !(from.get(source) instanceof Join<?,?>) || plan.joins().stream().anyMatch(j->j.target().equals(source)&&j.type()==ExecutionPlan.JoinType.INNER)) {
                    where.add(predicate(plan.rowFilters().get(source),builder,from,bindings));where.add(predicate(current.rowFilters().get(source),builder,from,bindings));
                }
                query.where(builder.and(where.toArray(Predicate[]::new)));
                // Criteria paths may be shared by multiple semantic projections. Aliasing a
                // path mutates it; map ordered tuple values to public aliases instead.
                var selections=new ArrayList<Selection<?>>();var expressions=new LinkedHashMap<String,Expression<?>>();var columns=new ArrayList<TypedDataset.Column>();
                for(var s:plan.query().dimensions()) {
                    var f=registry.fields().get(s.field());var expression=path(f,from);expressions.put(s.alias(),expression);selections.add(expression);columns.add(new TypedDataset.Column(s.alias(),f.type(),f.unit(),f.precision()));
                }
                for(var s:plan.query().metrics()) {
                    var m=registry.metrics().get(s.field());Expression<?> input=m.field()==null?attribute(from.get(m.source()),registry.sources().get(m.source()).keys().getFirst()):path(registry.fields().get(m.field()),from);
                    if(m.defaultFilter()!=null)input=builder.selectCase().when(predicate(m.defaultFilter(),builder,from,bindings),input).otherwise(builder.nullLiteral(input.getJavaType()));
                    Expression<?> expression;
                    if(formulas!=null && formulas.contains(m.id())) {
                        var formula=formulas.get(m.id());expression=FormulaMetricCompiler.compile(formula.expression(),builder,registry,from);

                    }else expression=aggregate(builder,m.aggregate(),input);expressions.put(s.alias(),expression);selections.add(expression);
                    ValueType type=m.aggregate()==SemanticRegistry.Aggregate.COUNT?ValueType.INTEGER:m.aggregate()==SemanticRegistry.Aggregate.AVG?ValueType.DECIMAL:registry.fields().get(m.field()).type();
                    columns.add(new TypedDataset.Column(s.alias(),type,m.unit(),m.precision()));
                }
                query.select(builder.tuple(selections.toArray(Selection[]::new)));
                if(plan.query().mode()==QuerySpec.Mode.AGGREGATE && !plan.query().dimensions().isEmpty())query.groupBy(plan.query().dimensions().stream().map(s->expressions.get(s.alias())).toArray(Expression[]::new));
                var order=new ArrayList<Order>();for(var o:plan.query().orderBy())order.add(o.direction()==QuerySpec.Direction.ASC?builder.asc(expressions.get(o.alias())):builder.desc(expressions.get(o.alias())));
                if(plan.query().mode()==QuerySpec.Mode.DETAIL)for(String key:plan.uniqueKeys())order.add(builder.asc(attribute(root,key)));
                else for(var s:plan.query().dimensions())order.add(builder.asc(expressions.get(s.alias())));
                query.orderBy(order);
                long total=1;
                if(plan.query().mode()!=QuerySpec.Mode.AGGREGATE || !plan.query().dimensions().isEmpty()) {
                    var count=em.createQuery(query.createCountQuery());bind(count,bindings);count.setHint("jakarta.persistence.query.timeout",5000);cancellation.check();total=count.getSingleResult();
                }
                var results=em.createQuery(query);bind(results,bindings);results.setHint("jakarta.persistence.query.timeout",5000);
                long offset=(long)plan.query().pagination().page()*plan.query().pagination().size();if(offset>Integer.MAX_VALUE)throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Page offset exceeded");
                if(sink!=null){
                    results.setHint("org.hibernate.fetchSize",100);
                    try(var stream=results.getResultStream()){var iterator=stream.iterator();while(iterator.hasNext()){cancellation.check();var tuple=iterator.next();var row=new LinkedHashMap<String,Object>();for(int i=0;i<columns.size();i++)row.put(columns.get(i).alias(),tuple.get(i));sink.accept(row);}}
                    cancellation.check();transaction.commit();return null;
                }
                results.setFirstResult((int)offset);results.setMaxResults(plan.query().mode()==QuerySpec.Mode.AGGREGATE?Math.min(plan.query().limit(),plan.query().pagination().size()):plan.query().pagination().size());
                cancellation.check();var rows=new ArrayList<Map<String,Object>>();
                for(var tuple:results.getResultList()){var row=new LinkedHashMap<String,Object>();for(int i=0;i<columns.size();i++)row.put(columns.get(i).alias(),tuple.get(i));rows.add(row);}
                cancellation.check();transaction.commit();return new TypedDataset(plan.query().id(),columns,rows,total,plan.query().pagination().page(),plan.query().pagination().size(),plan.snapshotAt(),plan.asOf());
            }catch(RuntimeException ex){if(transaction.isActive())transaction.rollback();cancellation.check();throw ex;}
            catch(Exception ex){if(transaction.isActive())transaction.rollback();throw new IllegalStateException("Execution cleanup failed",ex);}
        }
    }
    private static void bind(Query query,Map<String,Object> bindings){bindings.forEach(query::setParameter);}
    @SuppressWarnings({"unchecked","rawtypes"}) private static Object enumConstant(Class<?> type,String name){try{return Enum.valueOf((Class<? extends Enum>)type,name);}catch(IllegalArgumentException unknown){throw new EngineException(EngineErrorCode.IR_INVALID,"Filter value outside allowlist");}}
    private Path<?> path(SemanticRegistry.Field f,Map<String,From<?,?>> from) {
        Path<?> path=from.get(f.source());String[] parts=f.path().split("\\.");String source=f.source();
        for(String part:parts){String id=source;var rel=registry.relations().values().stream().filter(r->r.source().equals(id)&&r.path().equals(part)).findFirst();
            if(rel.isPresent() && from.containsKey(rel.get().target())){source=rel.get().target();path=from.get(source);}else path=path.get(part);}
        // A foreign-key field (payment.branch_id) whose relation is joined ends on the joined entity; the field's value
        // is that entity's key, so filters, grouping and selections compare the id, not the entity.
        if(path instanceof From<?,?> joined && joined!=from.get(f.source()))path=attribute(joined,registry.sources().get(source).keys().getFirst());
        return path;
    }
    private static Path<?> attribute(Path<?> root,String path){for(String part:path.split("\\."))root=root.get(part);return root;}
    @SuppressWarnings({"unchecked","rawtypes"}) private Expression<?> aggregate(CriteriaBuilder cb,SemanticRegistry.Aggregate op,Expression<?> input){return switch(op){case COUNT->cb.count(input);case SUM->cb.sum((Expression)input);case AVG->cb.avg((Expression)input);case MIN->cb.least((Expression)input);case MAX->cb.greatest((Expression)input);};}
    @SuppressWarnings({"unchecked","rawtypes"}) private Predicate predicate(Filter filter,CriteriaBuilder cb,Map<String,From<?,?>> from,Map<String,Object> bindings) {
        if(filter==null)return cb.conjunction();
        if(filter instanceof Filter.Group g){var children=g.children().stream().map(f->predicate(f,cb,from,bindings)).toArray(Predicate[]::new);return g.junction()==Filter.Junction.AND?cb.and(children):cb.or(children);}
        var leaf=(Filter.Leaf)filter;var field=registry.fields().get(leaf.field());var path=path(field,from);
        if(leaf.operator()==Filter.Operator.IS_NULL)return cb.isNull(path);
        var parameters=new ArrayList<ParameterExpression<?>>();for(Object value:leaf.values()) {
            Object typed=IrValidator.value(field.type(),value);if(path.getJavaType()==Integer.class && typed instanceof Long number)typed=Math.toIntExact(number);
            // TIMESTAMP values are UTC wall times; an entity mapped to Instant binds the instant itself.
            if(path.getJavaType()==java.time.Instant.class && typed instanceof java.time.LocalDateTime utc)typed=utc.toInstant(java.time.ZoneOffset.UTC);
            // STRING values of an enum-mapped attribute (payment.status) bind as that enum constant.
            if(path.getJavaType()!=null && path.getJavaType().isEnum() && typed instanceof String name)typed=enumConstant(path.getJavaType(),name);
            if(path.getJavaType()==Short.class && typed instanceof Long number){if(number<Short.MIN_VALUE || number>Short.MAX_VALUE)throw new EngineException(EngineErrorCode.IR_INVALID,"Integer overflow");typed=number.shortValue();}
            String name="p"+bindings.size();bindings.put(name,typed);parameters.add(cb.parameter(path.getJavaType(),name));
        }
        var first=parameters.getFirst();return switch(leaf.operator()) {
            case EQ->cb.equal(path,first);case NE->cb.notEqual(path,first);case IN->{CriteriaBuilder.In<Object> in=cb.in((Expression<Object>)path);parameters.forEach(in::value);yield in;}
            case GT->cb.greaterThan((Expression)path,(Expression)first);case GTE->cb.greaterThanOrEqualTo((Expression)path,(Expression)first);
            case LT->cb.lessThan((Expression)path,(Expression)first);case LTE->cb.lessThanOrEqualTo((Expression)path,(Expression)first);
            case BETWEEN->cb.between((Expression)path,(Expression)first,(Expression)parameters.get(1));case IS_NULL->throw new IllegalStateException();
        };
    }
}
