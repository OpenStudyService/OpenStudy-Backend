package com.openstudy.domainlens.catalog;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
import java.util.function.BooleanSupplier;
/** One volatile publication, including diagnostics; a failed candidate closes new admissions. */
public final class CatalogActivation {
 public record Check(String name,boolean ready,String code){}
 public record Snapshot(String version,SemanticRegistry registry,EmbeddingIndex index,String policyVersion,ComponentCatalog components){}
 public record Status(boolean ready,String snapshotVersion,List<Check> checks){public Status{checks=List.copyOf(checks);}}
 private record State(Snapshot snapshot,Status status){}
 private List<ReadinessContributor> probes=List.of();
 private volatile State state=new State(null,new Status(false,null,List.of(new Check("activation",false,"NOT_READY"))));
 public synchronized Status activate(SemanticRegistry registry,EmbeddingIndex index,String policyVersion,ComponentCatalog components,ClockPort clock,List<MetricImpl> implementations,List<ReadinessContributor> contributors,BooleanSupplier policiesValid){
  probes=List.of();
  state=new State(null,new Status(false,null,List.of(new Check("activation",false,"ACTIVATING"))));
  var checks=new ArrayList<Check>();
  check(checks,"clock",()->clock!=null && clock.asOf()!=null && clock.displayZone()!=null,"CLOCK_UNSET");
  check(checks,"graph",()->registry!=null && !registry.version().isBlank() && !registry.sources().isEmpty(),"GRAPH_INVALID");
  check(checks,"index",()->compatible(registry,index),"INDEX_MISMATCH");
  check(checks,"policy",()->policyVersion!=null && !policyVersion.isBlank() && policiesValid.getAsBoolean(),"POLICY_MISMATCH");
  check(checks,"components",()->components!=null && !components.catalogVersion().isBlank() && !components.components().isEmpty(),"COMPONENT_MISMATCH");
  check(checks,"metrics",()->metricsValid(registry,implementations),"METRIC_INCOMPLETE");
  var names=new HashSet<String>();
  for(var contributor:contributors){var result=probe(contributor);checks.add(names.add(result.name())?result:new Check(result.name(),false,"DUPLICATE_CONTRIBUTOR"));}
  if(checks.stream().anyMatch(c->!c.ready())){state=new State(null,new Status(false,null,checks));return state.status();}
  String version=SearchText.hash(registry.version()+":"+index.version()+":"+policyVersion+":"+componentFingerprint(components)+":"+implementations.stream().map(m->m.id()+":"+m.version()).sorted().toList());
  var snapshot=new Snapshot(version,registry,index,policyVersion,components);probes=List.copyOf(contributors);state=new State(snapshot,new Status(true,version,checks));return state.status();
 }
 public synchronized void close(String name,String code){probes=List.of();state=new State(null,new Status(false,null,List.of(new Check(name,false,code))));}
 public synchronized Status status(){refresh();return state.status();}
 public synchronized Snapshot require(){refresh();var captured=state;if(captured.snapshot()==null)throw new EngineException(EngineErrorCode.NOT_READY,"Catalog is not ready");return captured.snapshot();}
 private void refresh(){
  if(state.snapshot()==null)return;
  var checks=new ArrayList<>(state.status().checks());
  for(var contributor:probes){var current=probe(contributor);if(!current.ready()){checks.removeIf(c->c.name().equals(current.name()));checks.add(current);state=new State(null,new Status(false,null,checks));probes=List.of();return;}}
 }
 private static Check probe(ReadinessContributor contributor){String name="contributor";try{name=contributor.name();boolean ready=contributor.ready();return new Check(name,ready,ready?"OK":contributor.code());}catch(RuntimeException ex){return new Check(name,false,"NOT_READY");}}
 private static void check(List<Check> checks,String name,BooleanSupplier action,String code){boolean ready;try{ready=action.getAsBoolean();}catch(RuntimeException ex){ready=false;}checks.add(new Check(name,ready,ready?"OK":code));}
 private static String componentFingerprint(ComponentCatalog catalog){
  return ContractJson.canonical(new ComponentCatalog(catalog.catalogVersion(),catalog.components().stream().sorted(Comparator.comparing(ComponentCatalog.Definition::id)).toList(),catalog.compatibleVersions()));
 }
 private static boolean metricsValid(SemanticRegistry registry,List<MetricImpl> implementations){var byId=new HashMap<String,MetricImpl>();for(var m:implementations)if(byId.put(m.id(),m)!=null)return false;return registry.metrics().values().stream().allMatch(m->{var impl=byId.get(m.implementationRef());return impl!=null && impl.version().equals(m.version()) && impl.grain().equals(m.grain());});}
 private static boolean compatible(SemanticRegistry registry,EmbeddingIndex index){
  if(index==null || index.model().isBlank() || index.dimensions()<1)return false;
  var expected=new SearchDocuments().build(registry);if(expected.size()!=index.entries().size())return false;
  for(int i=0;i<expected.size();i++){var e=index.entries().get(i);if(!expected.get(i).equals(e.document()) || e.vector().size()!=index.dimensions() || e.vector().stream().anyMatch(v->!Double.isFinite(v)) || e.vector().stream().allMatch(v->v==0))return false;}
  String expectedVersion=SearchText.hash(String.join("\n",expected.stream().map(d->d.id()+":"+SearchText.hash(SearchText.VERSION+"\n"+index.model()+"\n"+index.dimensions()+"\n"+SearchText.sanitize(d.text()))).toList()));
  return expectedVersion.equals(index.version());
 }
}
