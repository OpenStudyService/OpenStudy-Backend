package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.catalog.*;
import java.util.*;
import java.time.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Lowers approved operation IR to parameter-bound HQL. Physical paths come only from metadata. */
public final class FormulaQueryCompiler {
    private final SemanticRegistry registry;
    private final FormulaDefinition definition;
    private final Filter input;
    private final Instant asOf;
    private final Map<String,Filter> saved,current;
    private final Map<String,Object> bindings=new LinkedHashMap<>();
    private final Set<String> stageNames;
    public FormulaQueryCompiler(SemanticRegistry registry,FormulaDefinition definition,Filter input,Instant asOf,Map<String,Filter> saved,Map<String,Filter> current){
        this.registry=registry;this.definition=definition;this.input=input;this.asOf=asOf;this.saved=saved;this.current=current;
        stageNames=definition.stages().stream().map(OperationIr.Stage::id).collect(java.util.stream.Collectors.toSet());
    }
    public Map<String,Object> bindings(){return Collections.unmodifiableMap(bindings);}
    public String compile(FormulaDefinition.Output output,List<QuerySpec.Order> requested,boolean count){
        bindings.clear();var prefix=new ArrayList<String>();for(var stage:definition.stages())prefix.add(stage.id()+" as ("+select(stage.query(),Map.of(),List.of(),false)+")");
        String body=select(output.query(),Map.of(),requested,count);
        if(count)body="select count(*) from ("+body+") counted";
        return (prefix.isEmpty()?"":"with "+String.join(",",prefix)+" ")+body;
    }
    private String select(OperationIr.Select q,Map<String,String> outer,List<QuerySpec.Order> requested,boolean count){
        var scope=new LinkedHashMap<>(outer);scope.put(q.alias(),q.source());String from=" from "+source(q.source())+" "+q.alias();
        var constraints=new ArrayList<String>();constraints.add(scopes(q.alias(),q.source(),scope,q));
        for(var join:q.joins()){
            String joinedSource;
            boolean scalarRelation=false;
            String scalarCondition=null;
            if(join.relation()!=null){
                var r=registry.relations().get(join.relation());joinedSource=r.target();scope.put(join.alias(),joinedSource);
                String fromSource=scope.get(join.from());
                scalarRelation=fromSource!=null && registry.sources().containsKey(fromSource) && registry.model().entities().stream().filter(e->e.javaType()==registry.sources().get(fromSource).javaType()).flatMap(e->e.fields().stream()).anyMatch(f->f.path().equals(r.path()));
                if(scalarRelation){
                    from+=(join.left()?" left join ":" join ")+source(joinedSource)+" "+join.alias();
                    scalarCondition=join.from()+"."+r.path()+" = "+join.alias()+".id";
                } else {
                    from+=(join.left()?" left join ":" join ")+join.from()+"."+r.path()+" "+join.alias();
                }
            }
            else {joinedSource=join.source();scope.put(join.alias(),joinedSource);from+=(join.left()?" left join ":" join ")+source(joinedSource)+" "+join.alias();}
            String condition=join.on()==null?"1=1":expression(join.on(),scope,q);
            if(scalarCondition!=null)condition="1=1".equals(condition)?scalarCondition:"("+scalarCondition+") and ("+condition+")";
            String scoped=scopes(join.alias(),joinedSource,scope,q);
            if(join.left() || join.relation()==null || scalarRelation)from+=" on ("+condition+") and ("+scoped+")";
            else {constraints.add(condition);constraints.add(scoped);}
        }
        if(q.where()!=null)constraints.add(expression(q.where(),scope,q));
        var columns=new ArrayList<String>();for(var c:q.columns())columns.add(expression(c.expression(),scope,q)+" as "+c.alias());
        String result="select "+String.join(",",columns)+from+" where "+String.join(" and ",constraints.stream().map(x->"("+x+")").toList());
        if(!q.group().isEmpty())result+=" group by "+String.join(",",q.group().stream().map(e->expression(e,scope,q)).toList());
        if(!count){var order=new ArrayList<String>();for(var o:requested){var column=q.columns().stream().filter(c->c.alias().equals(o.alias())).findFirst();if(column.isPresent())order.add(expression(column.get().expression(),scope,q)+(o.direction()==QuerySpec.Direction.ASC?" asc":" desc"));}
            for(var o:q.order())order.add(expression(o.expression(),scope,q)+(o.direction()==QuerySpec.Direction.ASC?" asc":" desc"));
            if(!order.isEmpty())result+=" order by "+String.join(",",order);}
        return result;
    }
    private String source(String id){return stageNames.contains(id)?id:registry.sources().get(id).jpaName();}
    private String scopes(String alias,String source,Map<String,String> scope,OperationIr.Select q){
        if(stageNames.contains(source))return "1=1";
        var mapping=new HashMap<String,OperationIr>();for(var field:registry.fields().values())if(field.source().equals(source))mapping.put(field.id(),new OperationIr(OperationIr.Op.FIELD,List.of(),alias,field.id(),null,null,null,null));
        return "("+filter(saved.get(source),mapping,scope,q)+") and ("+filter(current.get(source),mapping,scope,q)+")";
    }
    private String filter(Filter f,Map<String,OperationIr> mapping,Map<String,String> scope,OperationIr.Select q){
        if(f==null)return "1=1";
        if(f instanceof Filter.Group g)return "("+String.join(g.junction()==Filter.Junction.AND?" and ":" or ",g.children().stream().map(x->filter(x,mapping,scope,q)).toList())+")";
        var l=(Filter.Leaf)f;var e=mapping.get(l.field());require(e!=null,"Missing formula filter binding");String column=expression(e,scope,q);
        var values=l.values().stream().map(v->{var inputDefinition=definition.inputs().get(l.field());ValueType type=inputDefinition==null?registry.fields().get(l.field()).type():inputDefinition.type();Object typed=IrValidator.value(type,v);if(typed instanceof Instant instant)typed=LocalDateTime.ofInstant(instant,ZoneOffset.UTC);return bind(typed);}).toList();
        return switch(l.operator()){
            case IS_NULL->column+" is null";case IN->column+" in ("+String.join(",",values)+")";
            case BETWEEN->column+" between "+values.get(0)+" and "+values.get(1);
            default->column+switch(l.operator()){case EQ->" = ";case NE->" <> ";case GT->" > ";case GTE->" >= ";case LT->" < ";case LTE->" <= ";default->throw new IllegalStateException();}+values.getFirst();
        };
    }
    private static Filter bound(Filter input,Set<String> fields){
        if(input==null)return null;
        if(input instanceof Filter.Leaf leaf)return fields.contains(leaf.field())?leaf:null;
        var group=(Filter.Group)input;require(group.junction()==Filter.Junction.AND,"Bound formula filters require conjunctions");
        var children=group.children().stream().map(f->bound(f,fields)).filter(Objects::nonNull).toList();
        return children.isEmpty()?null:new Filter.Group(Filter.Junction.AND,children);
    }
    private String bind(Object value){String name="v"+bindings.size();bindings.put(name,value);return ":"+name;}
    private String expression(OperationIr e,Map<String,String> scope,OperationIr.Select q){
        if(e.op()==OperationIr.Op.FIELD)return e.scope()+"."+registry.fields().get(e.ref()).path();
        if(e.op()==OperationIr.Op.COLUMN)return e.scope()+"."+e.ref();
        if(e.op()==OperationIr.Op.ENTITY)return e.scope();
        if(e.op()==OperationIr.Op.RELATION)return e.scope()+"."+registry.relations().get(e.ref()).path();
        if(e.op()==OperationIr.Op.LITERAL){if(e.type()==ValueType.INTEGER)return new java.math.BigDecimal(e.value().toString()).longValueExact()+"L";if(e.type()==ValueType.DECIMAL)return new java.math.BigDecimal(e.value().toString()).toPlainString()+"BD";if(e.type()==ValueType.BOOLEAN)return Boolean.TRUE.equals(e.value())?"true":"false";return "cast("+bind(e.value())+" as String)";}
        if(e.op()==OperationIr.Op.AS_OF){String name="asOf";bindings.put(name,LocalDateTime.ofInstant(asOf,ZoneOffset.UTC).toString());return "cast(:asOf as LocalDateTime)";}
        if(e.op()==OperationIr.Op.INPUT_FILTER)return filter(input,q.inputs(),scope,q);
        if(e.op()==OperationIr.Op.INPUT_FILTER_BOUND)return filter(bound(input,q.inputs().keySet()),q.inputs(),scope,q);
        if(e.op()==OperationIr.Op.EXISTS)return "exists("+select(e.query(),scope,List.of(),false)+")";
        var a=e.args().stream().map(x->expression(x,scope,q)).toList();
        return switch(e.op()){
            case SUM,MIN,MAX,AVG->e.op().name().toLowerCase(Locale.ROOT)+"("+a.getFirst()+")";
            case COUNT->"count("+a.getFirst()+")";case COUNT_DISTINCT->"count(distinct "+a.getFirst()+")";
            case ADD,SUBTRACT,MULTIPLY->"("+a.get(0)+switch(e.op()){case ADD->" + ";case SUBTRACT->" - ";default->" * ";}+a.get(1)+")";
            case DIVIDE->"(cast("+a.get(0)+" as BigDecimal) / nullif("+a.get(1)+",0))";
            case EQ,NE,GT,GTE,LT,LTE->"("+a.get(0)+switch(e.op()){case EQ->" = ";case NE->" <> ";case GT->" > ";case GTE->" >= ";case LT->" < ";default->" <= ";}+a.get(1)+")";
            case AND,OR->"("+String.join(e.op()==OperationIr.Op.AND?" and ":" or ",a)+")";
            case NOT->"not ("+a.getFirst()+")";case IS_NULL->a.getFirst()+" is null";
            case COALESCE->"coalesce("+String.join(",",a)+")";
            case CASE->{String result="case";for(int i=0;i<a.size()-1;i+=2)result+=" when "+a.get(i)+" then "+a.get(i+1);yield result+" else "+a.getLast()+" end";}
            case CAST_STRING->"cast("+a.getFirst()+" as String)";
            case ADD_DAYS->"timestampadd(day,"+a.get(1)+",cast("+a.get(0)+" as Timestamp))";
            default->throw new IllegalArgumentException("Unsupported operation");
        };
    }
}
