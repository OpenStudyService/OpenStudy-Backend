package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.SearchText;
import com.openstudy.domainlens.security.Identity;
import java.util.*;
import java.time.*;
import java.util.function.Supplier;
/** Bounded ephemeral result cache. Authorization runs on hits and misses; never stores model output. */
public final class BoundedQueryCache implements QueryExecutorDecorator {
 private com.openstudy.domainlens.catalog.FormulaRegistry formulas;
 public BoundedQueryCache formulas(com.openstudy.domainlens.catalog.FormulaRegistry registry){formulas=registry;return this;}
 private record Entry(Instant expires,List<TypedDataset> rows){}
 private final QueryCachePolicy policy;private final Clock clock;private final int maximum;
 private final Map<String,Entry> entries=new LinkedHashMap<>(16,.75f,true);
 public BoundedQueryCache(QueryCachePolicy policy,Clock clock,int maximum){if(maximum<1||maximum>10000)throw new IllegalArgumentException("Invalid cache capacity");this.policy=policy;this.clock=clock;this.maximum=maximum;}
 public QueryExecutor decorate(QueryExecutor executor){return new QueryExecutor(){
  public TypedDataset execute(ExecutionPlan p,UserContext u,Cancellation c){return load("single",p,u,c,()->List.of(executor.execute(p,u,c))).getFirst();}
  public List<TypedDataset> executeAll(ExecutionPlan p,UserContext u,Cancellation c){return load("all",p,u,c,()->executor.executeAll(p,u,c));}
 };}
 private List<TypedDataset> load(String mode,ExecutionPlan plan,UserContext user,Cancellation cancel,Supplier<List<TypedDataset>> loader){
  Identity.owner(user,plan.owner());cancel.check();
  if(!plan.formulas().isEmpty()){
   ContractRules.require(formulas!=null,"Formula cache registry unavailable");plan.formulas().forEach(formulas::requireRef);
   var selected=new HashSet<String>();plan.query().metrics().forEach(m->selected.add(m.field()));if(plan.query().analysisId()!=null)selected.add(plan.query().analysisId());
   ContractRules.require(plan.formulas().size()==selected.size() && plan.formulas().stream().map(f->f.id()).collect(java.util.stream.Collectors.toSet()).equals(selected),"Formula cache selection mismatch");FormulaPlanning.validateInputs(formulas,plan.query());
  }
  var context=policy.authorize(plan,user);String key=key(mode,context,plan,user);Entry entry;
  synchronized(entries){entries.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires()));entry=entries.get(key);}
  if(entry!=null){cancel.check();return entry.rows();}
  var result=List.copyOf(loader.get());cancel.check();var current=policy.authorize(plan,user);if(!context.equals(current))throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Cache authority or data version changed");
  if(result.stream().mapToLong(d->d.rows().size()).sum()<=1000){synchronized(entries){entries.put(key,new Entry(clock.instant().plus(context.ttl()),result));while(entries.size()>maximum)entries.remove(entries.keySet().iterator().next());}}
  return result;
 }
 public static String key(String mode,QueryCachePolicy.Context context,ExecutionPlan plan,UserContext user){
  var q=plan.query();var normalized=new QuerySpec(q.id(),q.source(),q.grain(),q.mode(),q.dimensions(),q.metrics(),normalize(q.filter()),q.orderBy(),q.pagination(),q.limit(),q.analysisId());
  var permissions=Map.of("owner",user.subjectId(),"roles",user.roles().stream().sorted().toList(),"scope",user.policyBinding().scope(),"role",user.policyBinding().roleKey(),"policy",plan.policyVersion());
  var parts=new LinkedHashMap<String,Object>();parts.put("format",1);parts.put("mode",mode);parts.put("tenant",context.tenant());parts.put("permissions",SearchText.hash(ContractJson.canonical(permissions)));parts.put("query",normalized);parts.put("plan",List.of(plan.formatVersion(),plan.registryVersion(),plan.policyVersion(),plan.effectiveScope(),plan.joins(),plan.rowFilters(),plan.uniqueKeys(),plan.timeoutSeconds()));parts.put("formulas",plan.formulas().stream().sorted(Comparator.comparing(com.openstudy.domainlens.api.formula.FormulaDefinition.Ref::id)).toList());parts.put("dataVersion",context.dataVersion());parts.put("ttl",context.ttl().toMillis());parts.put("asOf",plan.asOf());parts.put("snapshotAt",plan.snapshotAt());return SearchText.hash(ContractJson.canonical(parts));
 }
 private static Filter normalize(Filter f){if(f instanceof Filter.Group g)return new Filter.Group(g.junction(),g.children().stream().map(BoundedQueryCache::normalize).sorted(Comparator.comparing(ContractJson::canonical)).toList());if(f instanceof Filter.Leaf l&&l.operator()==Filter.Operator.IN)return new Filter.Leaf(l.field(),l.operator(),l.values().stream().distinct().sorted(Comparator.comparing(ContractJson::canonical)).toList());return f;}
}
