package com.openstudy.domainlens.catalog;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Activated consumer formulas. Validation and fingerprints contain no domain-specific rules. */
public final class FormulaRegistry {
    private final SemanticRegistry semantic;
    private final boolean mixedPlanning;
    private final Map<String,FormulaDefinition> definitions;
    private final Map<String,String> hashes;
    private final List<com.openstudy.domainlens.api.prompt.PromptPackage.Example> examples;
    public FormulaRegistry(SemanticRegistry semantic,List<FormulaDefinition> formulas) {
        this(semantic,formulas,List.of());
    }
    public FormulaRegistry(SemanticRegistry semantic,List<FormulaDefinition> formulas,List<com.openstudy.domainlens.api.prompt.PromptPackage.Example> examples) {
        this(semantic,formulas,examples,false);
    }
    public FormulaRegistry(SemanticRegistry semantic,List<FormulaDefinition> formulas,List<com.openstudy.domainlens.api.prompt.PromptPackage.Example> examples,boolean mixedPlanning) {
        this.mixedPlanning=mixedPlanning;this.semantic=semantic;var all=new LinkedHashMap<String,FormulaDefinition>();var digests=new LinkedHashMap<String,String>();
        for(var f:formulas){require(f.id()!=null && f.version()!=null && !f.version().isBlank() && all.put(f.id(),f)==null,"Duplicate/missing formula");
            validate(f);digests.put(f.id(),SearchText.hash(ContractJson.canonical(f)));}
        definitions=Map.copyOf(all);hashes=Map.copyOf(digests);this.examples=examples==null?List.of():List.copyOf(examples);
        var exampleIds=new HashSet<String>();for(var example:this.examples){require(exampleIds.add(example.id()) && example.question()!=null && !example.question().isBlank(),"Invalid formula example");ContractRules.validate(example.ir());require(example.ir().contextRefs().stream().allMatch(semantic.publicNodeIds()::contains),"Unknown example context");for(var q:example.ir().queries()){require(q.analysisId()==null || contains(q.analysisId()),"Unknown example analysis");require(q.metrics().stream().allMatch(m->contains(m.field())),"Unknown example metric");}}
    }
    public static FormulaRegistry resource(SemanticRegistry semantic,Class<?> owner,String resource) {
        try(var in=owner.getResourceAsStream(resource)){
            require(in!=null,"Formula resource absent");var document=ContractJson.readExact(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Document.class);
            require(Set.of(FormulaDefinition.FORMAT,FormulaDefinition.FORMAT_V2).contains(document.version()),"Unsupported formula format");return new FormulaRegistry(semantic,document.formulas(),document.examples());
        }catch(java.io.IOException ex){throw new IllegalStateException("Cannot load formula resource",ex);}
    }
    public record Document(String version,List<FormulaDefinition> formulas,List<com.openstudy.domainlens.api.prompt.PromptPackage.Example> examples) {}
    public List<com.openstudy.domainlens.api.prompt.PromptPackage.Example> examples(){return examples;}
    public SemanticRegistry semantic(){return semantic;}
    public boolean mixedPlanning(){return mixedPlanning;}
    public Collection<FormulaDefinition> definitions(){return definitions.values();}
    public FormulaDefinition get(String id){var f=definitions.get(id);require(f!=null,"Unknown formula");return f;}
    public boolean contains(String id){return definitions.containsKey(id);}
    public FormulaDefinition.Ref ref(String id){var f=get(id);return new FormulaDefinition.Ref(id,f.version(),hashes.get(id));}
    public void requireRef(FormulaDefinition.Ref ref){require(ref!=null && contains(ref.id()) && ref.equals(ref(ref.id())),"Formula version unavailable");}
    public FormulaDefinition.Contract contract(String id){var f=get(id);return new FormulaDefinition.Contract(id,f.version(),hashes.get(id),f.kind(),f.grain(),f.description(),f.inputs(),f.outputs().stream().map(o->new FormulaDefinition.OutputContract(o.id(),o.grain(),o.columns())).toList(),f.presentations().stream().map(p->new FormulaDefinition.Presentation(p.component(),p.output(),p.bindings())).toList(),f.narrativeTemplate(),f.aliases().stream().filter(alias->definitions.values().stream().filter(other->other.aliases().stream().anyMatch(a->a.equalsIgnoreCase(alias))).count()==1).toList());}
    public String fingerprint(){return SearchText.hash(ContractJson.canonical(new TreeMap<>(hashes)));}
    private void validate(FormulaDefinition f){
        require(f.aliases().size()<=16,"Formula alias budget");var aliases=new HashSet<String>();
        for(String alias:f.aliases())require(alias!=null && !alias.isBlank() && alias.equals(alias.trim()) && alias.length()<=96 && alias.codePoints().anyMatch(Character::isLetter) && alias.codePoints().noneMatch(Character::isISOControl) && aliases.add(alias.toLowerCase(Locale.ROOT)),"Invalid or duplicate formula alias");
        require(f.stages().size()<=16 && f.dependencies().size()<=64,"Formula graph budget");
        require(f.kind()!=null && f.outputs().size()>0 && f.outputs().size()<=6,"Formula output budget");
        require(!f.baseSources().isEmpty() && f.baseSources().containsAll(f.discoverySources()) && semantic.sources().keySet().containsAll(f.baseSources()),"Formula source absent");
        require(f.dependencies().stream().allMatch(semantic.fields()::containsKey),"Formula field absent");
        f.inputs().forEach((id,input)->{require(!input.operators().isEmpty() && !input.policyFields().isEmpty() && f.dependencies().containsAll(input.policyFields()),"Input policy dependency absent");if(semantic.fields().containsKey(id))require(input.type()==semantic.fields().get(id).type(),"Input type mismatch");else require(input.type()==ValueType.STRING && !input.values().isEmpty(),"Virtual input allowlist absent");});
        var boundInputs=new HashSet<String>();for(var stage:f.stages())collectInputs(stage.query(),boundInputs);for(var output:f.outputs())if(output.query()!=null)collectInputs(output.query(),boundInputs);if(f.kind()==FormulaDefinition.Kind.ANALYSIS)require(boundInputs.containsAll(f.inputs().keySet()),"Unbound formula input");
        var stages=new LinkedHashMap<String,Map<String,Shape>>();var used=new HashSet<String>();
        for(var s:f.stages()){identifier(s.id());require(!semantic.sources().containsKey(s.id()) && !stages.containsKey(s.id()),"Duplicate stage");stages.put(s.id(),select(s.query(),Map.of(),stages,f,used,0));}
        var ids=new HashSet<String>();
        for(var o:f.outputs()){identifier(o.id());require(ids.add(o.id()) && o.maxRows()>0 && o.maxRows()<=100,"Invalid output");
            var names=new HashSet<String>();for(var c:o.columns()){identifier(c.alias());require(names.add(c.alias()),"Duplicate output column");}
            require(names.containsAll(o.uniqueKeys()) && names.containsAll(o.sortColumns().keySet()) && names.containsAll(o.sortColumns().values()),"Output ordering absent");
            if(f.kind()==FormulaDefinition.Kind.ANALYSIS){require(o.query()!=null,"Missing analysis query");var shape=select(o.query(),Map.of(),stages,f,used,0);require(shape.keySet().equals(names),"Output aliases mismatch");for(var c:o.columns())require(shape.get(c.alias()).type()==c.type() || c.type()==ValueType.DECIMAL && shape.get(c.alias()).type()==ValueType.INTEGER,"Output type mismatch");}
        }
        if(f.kind()==FormulaDefinition.Kind.METRIC){var metric=semantic.metrics().get(f.id());require(metric!=null && metric.source().equals(f.source()) && metric.grain().equals(f.grain()) && f.expression()!=null && f.stages().isEmpty() && f.outputs().size()==1,"Metric contract mismatch");require(f.joinPath().equals(metric.path()) && Objects.equals(f.defaultFilter(),metric.defaultFilter()),"Formula lowering differs from semantic metric");metricOperations(f.expression());var scope=Map.of("root",new Source(f.source(),null));var shape=expression(f.expression(),scope,stages,f,used,0);require(shape.aggregate(),"Metric must aggregate");require(f.outputs().getFirst().columns().size()==1 && f.outputs().getFirst().columns().getFirst().type()==shape.type(),"Metric output type mismatch");}
        require(f.dependencies().containsAll(used),"Undeclared calculation dependency");
        for(var p:f.presentations()){require(Set.of("KpiGroup","BarChart","DonutChart","PaginatedDataTable","DataTable","DetailCard").contains(p.component()),"Unknown formula component");var o=f.outputs().stream().filter(x->x.id().equals(p.output())).findFirst().orElseThrow(()->new EngineException(EngineErrorCode.IR_INVALID,"Presentation output absent"));var columns=o.columns().stream().map(TypedDataset.Column::alias).collect(java.util.stream.Collectors.toSet());for(Object slot:p.bindings().values()){if(slot instanceof String s)require(columns.contains(s),"Presentation column absent");else if(slot instanceof List<?> list)require(list.stream().allMatch(columns::contains),"Presentation columns absent");else require(false,"Invalid presentation slot");}
            var shape=new HashMap<String,Shape>();o.columns().forEach(c->shape.put(c.alias(),new Shape(c.type(),c.unit(),false)));
            for(var derived:p.derived().values()){displayOperations(derived);expression(derived,Map.of("row",new Source(o.id(),shape),"dataset",new Source(o.id(),shape)),Map.of(),f,new HashSet<>(),0);}
        }
    }
    private static void metricOperations(OperationIr e){
        require(!Set.of(OperationIr.Op.COLUMN,OperationIr.Op.ENTITY,OperationIr.Op.RELATION,OperationIr.Op.AS_OF,OperationIr.Op.INPUT_FILTER,OperationIr.Op.INPUT_FILTER_BOUND,OperationIr.Op.CAST_STRING,OperationIr.Op.EXISTS,OperationIr.Op.ADD_DAYS).contains(e.op()),"Operation unavailable in metric context");e.args().forEach(FormulaRegistry::metricOperations);
    }
    private static void displayOperations(OperationIr e){
        require(Set.of(OperationIr.Op.COLUMN,OperationIr.Op.LITERAL,OperationIr.Op.SUM,OperationIr.Op.ADD,OperationIr.Op.SUBTRACT,OperationIr.Op.MULTIPLY,OperationIr.Op.DIVIDE,OperationIr.Op.COALESCE).contains(e.op()),"Unavailable display operation");
        if(e.op()==OperationIr.Op.COLUMN)require(Set.of("row","dataset").contains(e.scope()),"Display scope absent");
        if(e.op()==OperationIr.Op.SUM)require(e.args().size()==1 && e.args().getFirst().op()==OperationIr.Op.COLUMN && "dataset".equals(e.args().getFirst().scope()),"Display total must reference complete dataset");
        e.args().forEach(FormulaRegistry::displayOperations);
    }
    private record Source(String id,Map<String,Shape> columns) {}
    private record Shape(ValueType type,String unit,boolean aggregate) {}
    private Map<String,Shape> select(OperationIr.Select q,Map<String,Source> outer,Map<String,Map<String,Shape>> stages,FormulaDefinition f,Set<String> used,int depth){
        require(depth<=32,"Operation depth exceeded");identifier(q.alias());var scope=new HashMap<>(outer);require(!scope.containsKey(q.alias()),"Duplicate query scope");
        scope.put(q.alias(),source(q.source(),stages,f));
        for(var j:q.joins()){identifier(j.alias());require(!scope.containsKey(j.alias()),"Duplicate join scope");
            if(j.relation()!=null){var r=semantic.relations().get(j.relation());require(r!=null && scope.containsKey(j.from()) && r.source().equals(scope.get(j.from()).id()) && !r.joinUse().equals("FORBIDDEN"),"Invalid registered relation");scope.put(j.alias(),source(r.target(),stages,f));}
            else {require(j.on()!=null,"Entity join needs a predicate");scope.put(j.alias(),source(j.source(),stages,f));}
            if(j.on()!=null)require(expression(j.on(),scope,stages,f,used,depth+1).type()==ValueType.BOOLEAN,"Join predicate type");
        }
        if(q.where()!=null)require(expression(q.where(),scope,stages,f,used,depth+1).type()==ValueType.BOOLEAN,"Where predicate type");
        for(var g:q.group())require(!expression(g,scope,stages,f,used,depth+1).aggregate(),"Aggregate group key");
        q.inputs().forEach((id,e)->{require(f.inputs().containsKey(id),"Unknown query input");require(expression(e,scope,stages,f,used,depth+1).type()==f.inputs().get(id).type(),"Filter binding type");});
        var columns=new LinkedHashMap<String,Shape>();boolean aggregate=false;
        for(var c:q.columns()){identifier(c.alias());var shape=expression(c.expression(),scope,stages,f,used,depth+1);require(columns.put(c.alias(),shape)==null,"Duplicate column");aggregate|=shape.aggregate();}
        require(!columns.isEmpty(),"Empty selection");
        if(aggregate || !q.group().isEmpty())for(var c:q.columns())if(!columns.get(c.alias()).aggregate() && c.expression().op()!=OperationIr.Op.LITERAL)require(q.group().contains(c.expression()),"Ungrouped selection");
        for(var o:q.order())expression(o.expression(),scope,stages,f,used,depth+1);
        return Map.copyOf(columns);
    }
    private Source source(String id,Map<String,Map<String,Shape>> stages,FormulaDefinition f){if(stages.containsKey(id))return new Source(id,stages.get(id));require(f.baseSources().contains(id),"Unapproved query source");return new Source(id,null);}
    private Shape expression(OperationIr e,Map<String,Source> scope,Map<String,Map<String,Shape>> stages,FormulaDefinition f,Set<String> used,int depth){
        require(e!=null && depth<=32,"Operation depth exceeded");
        if(e.op()==OperationIr.Op.EXISTS){require(e.args().isEmpty() && e.query()!=null,"Exists arity");select(e.query(),scope,stages,f,used,depth+1);return new Shape(ValueType.BOOLEAN,null,false);}
        if(Set.of(OperationIr.Op.FIELD,OperationIr.Op.COLUMN,OperationIr.Op.ENTITY,OperationIr.Op.RELATION,OperationIr.Op.AS_OF,OperationIr.Op.INPUT_FILTER,OperationIr.Op.INPUT_FILTER_BOUND).contains(e.op()))require(e.args().isEmpty() && e.query()==null,"Leaf operation arity");
        if(e.op()==OperationIr.Op.FIELD){var s=scope.get(e.scope());var field=semantic.fields().get(e.ref());require(s!=null && field!=null && field.source().equals(s.id()),"Field scope mismatch");used.add(field.id());return new Shape(field.type(),field.unit(),false);}
        if(e.op()==OperationIr.Op.COLUMN){var s=scope.get(e.scope());require(s!=null && s.columns()!=null && s.columns().containsKey(e.ref()),"Unknown stage column");var c=s.columns().get(e.ref());return new Shape(c.type(),c.unit(),false);}
        if(e.op()==OperationIr.Op.ENTITY || e.op()==OperationIr.Op.RELATION){var s=scope.get(e.scope());require(s!=null && s.columns()==null,"Entity scope mismatch");if(e.op()==OperationIr.Op.ENTITY)return new Shape(ValueType.STRING,"entity:"+s.id(),false);var r=semantic.relations().get(e.ref());require(r!=null && r.source().equals(s.id()),"Relation scope mismatch");return new Shape(ValueType.STRING,"entity:"+r.target(),false);}
        if(e.op()==OperationIr.Op.LITERAL){require(e.type()!=null && e.value()!=null && e.args().isEmpty(),"Literal type absent");require(switch(e.type()){case INTEGER,DECIMAL->e.value() instanceof Number;case STRING->e.value() instanceof String;case BOOLEAN->e.value() instanceof Boolean;default->false;},"Literal value mismatch");return new Shape(e.type(),e.unit(),false);}
        if(e.op()==OperationIr.Op.AS_OF)return new Shape(ValueType.TIMESTAMP,null,false);
        if(e.op()==OperationIr.Op.INPUT_FILTER || e.op()==OperationIr.Op.INPUT_FILTER_BOUND)return new Shape(ValueType.BOOLEAN,null,false);
        var a=e.args().stream().map(x->expression(x,scope,stages,f,used,depth+1)).toList();boolean aggregated=a.stream().anyMatch(Shape::aggregate);
        switch(e.op()){
            case COUNT,COUNT_DISTINCT,SUM,MIN,MAX,AVG->{require(a.size()==1 && !aggregated,"Aggregate arity/nesting");if(e.op()==OperationIr.Op.COUNT || e.op()==OperationIr.Op.COUNT_DISTINCT)return new Shape(ValueType.INTEGER,null,true);require(numeric(a.getFirst()) || Set.of(OperationIr.Op.MIN,OperationIr.Op.MAX).contains(e.op()) && a.getFirst().type()==ValueType.TIMESTAMP,"Aggregate operand type");return new Shape(e.op()==OperationIr.Op.AVG?ValueType.DECIMAL:a.getFirst().type(),a.getFirst().unit(),true);}
            case ADD,SUBTRACT,MULTIPLY,DIVIDE->{require(a.size()==2 && a.stream().allMatch(FormulaRegistry::numeric),"Arithmetic operand type");if(e.op()==OperationIr.Op.ADD || e.op()==OperationIr.Op.SUBTRACT)require(Objects.equals(a.get(0).unit(),a.get(1).unit()),"Arithmetic unit mismatch");else require(a.get(0).unit()==null || a.get(1).unit()==null || e.op()==OperationIr.Op.DIVIDE && Objects.equals(a.get(0).unit(),a.get(1).unit()),"Arithmetic units require explicit conversion");String unit=e.op()==OperationIr.Op.DIVIDE && Objects.equals(a.get(0).unit(),a.get(1).unit())?null:a.get(0).unit()==null?a.get(1).unit():a.get(0).unit();return new Shape(e.op()==OperationIr.Op.DIVIDE || a.stream().anyMatch(x->x.type()==ValueType.DECIMAL)?ValueType.DECIMAL:ValueType.INTEGER,unit,aggregated);}
            case EQ,NE,GT,GTE,LT,LTE->{require(a.size()==2 && (a.get(0).type()==a.get(1).type() || a.stream().allMatch(FormulaRegistry::numeric)) && Objects.equals(a.get(0).unit(),a.get(1).unit()),"Comparison type/unit mismatch");return new Shape(ValueType.BOOLEAN,null,aggregated);}
            case AND,OR->{require(a.size()>=2 && a.stream().allMatch(x->x.type()==ValueType.BOOLEAN),"Boolean operands");return new Shape(ValueType.BOOLEAN,null,aggregated);}
            case NOT->{require(a.size()==1 && a.getFirst().type()==ValueType.BOOLEAN,"Not operand");return new Shape(ValueType.BOOLEAN,null,aggregated);}
            case IS_NULL->{require(a.size()==1,"Null arity");return new Shape(ValueType.BOOLEAN,null,aggregated);}
            case COALESCE->{require(a.size()==2 && a.get(0).type()==a.get(1).type() && Objects.equals(a.get(0).unit(),a.get(1).unit()),"Coalesce type/unit mismatch");return new Shape(a.getFirst().type(),a.getFirst().unit(),aggregated);}
            case CASE->{require(a.size()>=3 && a.size()%2==1,"Case arity");var fallback=a.getLast();for(int i=0;i<a.size()-1;i+=2)require(a.get(i).type()==ValueType.BOOLEAN && a.get(i+1).type()==fallback.type() && Objects.equals(a.get(i+1).unit(),fallback.unit()),"Case type/unit mismatch");return new Shape(fallback.type(),fallback.unit(),aggregated);}
            case CAST_STRING->{require(a.size()==1 && a.getFirst().type()==ValueType.STRING,"String cast operand type");return new Shape(ValueType.STRING,null,aggregated);}
            case ADD_DAYS->{require(a.size()==2 && a.get(0).type()==ValueType.TIMESTAMP && a.get(1).type()==ValueType.INTEGER,"Date operands");return new Shape(ValueType.TIMESTAMP,null,aggregated);}
            default->throw new EngineException(EngineErrorCode.IR_INVALID,"Unsupported operation");
        }
    }
    private static void collectInputs(OperationIr.Select q,Set<String> inputs){
        var expressions=new ArrayList<OperationIr>();q.columns().forEach(n->expressions.add(n.expression()));
        expressions.add(q.where());q.joins().forEach(j->expressions.add(j.on()));
        if(expressions.stream().anyMatch(FormulaRegistry::usesInputFilter))inputs.addAll(q.inputs().keySet());
        for(var expression:expressions)collectInputs(expression,inputs);
    }
    private static boolean usesInputFilter(OperationIr e){return e!=null && (Set.of(OperationIr.Op.INPUT_FILTER,OperationIr.Op.INPUT_FILTER_BOUND).contains(e.op()) || e.args().stream().anyMatch(FormulaRegistry::usesInputFilter));}
    private static void collectInputs(OperationIr e,Set<String> inputs){if(e==null)return;if(e.query()!=null)collectInputs(e.query(),inputs);e.args().forEach(a->collectInputs(a,inputs));}
    private static boolean numeric(Shape s){return s.type()==ValueType.INTEGER || s.type()==ValueType.DECIMAL;}
    private static void identifier(String id){require(id!=null && id.matches("[A-Za-z][A-Za-z0-9_]*"),"Invalid operation identifier");}
}
