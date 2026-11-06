package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.retrieval.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.*;
import com.openstudy.domainlens.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
/** Permission-first similarity or resolved seeds, with bounded approved dependency expansion. */
public final class Retriever {
 private boolean connected;
 private GraphRetrieval.Options options=GraphRetrieval.Options.defaults();
 private GraphRetrieval.Recognizer recognizer;
 public Retriever graph(GraphRetrieval.Options value,GraphRetrieval.Recognizer filter){options=Objects.requireNonNull(value);recognizer=filter;return this;}
 private boolean connected(){return connected||experimental();}
 private boolean experimental(){return options.strategy()!=GraphRetrieval.Strategy.LEGACY;}
 private int seedLimit(){return experimental()?options.seeds():limits.seeds();}
 private int nodeLimit(){return experimental()?options.nodes():limits.nodes();}
 private int byteLimit(){return experimental()?options.bytes():limits.tokenBudget();}
 public static final String CONNECTED_VERSION="dependency-path-1";
 public Retriever connected(boolean enabled){connected=enabled;return this;}
 public record Limits(int seeds,int nodes,int examples,int tokenBudget,double minimumScore){
  public Limits{if(seeds<1 || seeds>8 || nodes<1 || nodes>40 || examples<0 || examples>3 || tokenBudget<1 || !Double.isFinite(minimumScore))throw new IllegalArgumentException("Invalid retrieval limits");}
  public static Limits defaults(){return new Limits(8,40,3,4096,0.25);}
 }
 public record Hit(String id,double score,String reason){}
 public record Context(String sanitizedAsk,List<Hit> seeds,List<Hit> nodes,String graphVersion,String indexVersion,boolean truncated){
  public Context{seeds=List.copyOf(seeds);nodes=List.copyOf(nodes);}public Set<String> ids(){return nodes.stream().map(Hit::id).collect(java.util.stream.Collectors.toUnmodifiableSet());}
 }
 private final SemanticRegistry registry;private final PolicyEngine policies;private final EmbeddingIndex index;private final EmbeddingGateway gateway;private final MetadataVisibility visibility;private final Limits limits;
 public Retriever(SemanticRegistry registry,PolicyEngine policies,EmbeddingIndex index,EmbeddingGateway gateway,MetadataVisibility visibility,Limits limits){this.registry=registry;this.policies=policies;this.index=index;this.gateway=gateway;this.visibility=visibility;this.limits=limits;}
 public enum Mode { TERM_ONLY, SIMILARITY, SIMILARITY_ONE_HOP, PRODUCTION }
 public record Rejection(String id,String stage,String reason,Double score){}
 /** Evaluation-only diagnostics. Never attach rejected IDs to user prompts or ordinary traces. */
 public record Neighbor(String anchor,String relation,String target,Double rawSimilarity,Double decayedScore,String reason,boolean selected){}
 public record Evaluation(Context context,List<Hit> expanded,List<Rejection> rejected,Map<String,Double> similarities,List<Neighbor> neighbors){
  public Evaluation{expanded=List.copyOf(expanded);rejected=List.copyOf(rejected);similarities=Collections.unmodifiableMap(new TreeMap<>(similarities));neighbors=List.copyOf(neighbors);}
  public Evaluation(Context context,List<Hit> expanded,List<Rejection> rejected,Map<String,Double> similarities){this(context,expanded,rejected,similarities,List.of());}
  public Evaluation(Context context,List<Hit> expanded,List<Rejection> rejected){this(context,expanded,rejected,Map.of(),List.of());}
 }
 private record Prepared(String ask,List<EmbeddingIndex.Entry> eligible,Map<String,Double> cosine,List<Rejection> rejected,List<Double> query){}
 public Context retrieve(String ask,UserContext user){return select(prepare(ask,user),user,Mode.PRODUCTION,false).context();}
 /** Observes the very same production selection; never runs a second embedding/search. */
 public Context retrieve(String ask,UserContext user,java.util.function.Consumer<Evaluation> observer){var result=select(prepare(ask,user),user,Mode.PRODUCTION,true);observer.accept(result);return result.context();}
 /** Independently resolved consumer meanings are mandatory seeds, still subject to policy and budgets. */
 public Context retrieve(String ask,UserContext user,Set<String> required,java.util.function.Consumer<Evaluation> observer){var result=select(prepare(ask,user),user,Mode.PRODUCTION,observer!=null,connected()?required:Set.of());if(observer!=null)observer.accept(result);return result.context();}
 /** All vector modes share a single query embedding and the same policy-filtered documents. */
 public Map<Mode,Evaluation> evaluate(String ask,UserContext user){
  var prepared=prepare(ask,user);var result=new EnumMap<Mode,Evaluation>(Mode.class);
  for(var mode:Mode.values())result.put(mode,select(prepared,user,mode,true));
  return Collections.unmodifiableMap(result);
 }
 public void requireCombinedBudget(Set<String> ids) {
  if(!connected())return;
  var documents=new HashMap<String,String>();index.entries().forEach(e->documents.put(e.document().id(),e.document().text()));
  int bytes=ids.stream().mapToInt(id->documents.getOrDefault(id,id).getBytes(StandardCharsets.UTF_8).length).sum();
  if(ids.size()>nodeLimit() || bytes>byteLimit())throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Combined reference and retrieval context exceeds budget");
 }
 public boolean eligible(String id,UserContext user){
  Identity.require(user);
  return allowed(id,user,new HashSet<>()) && index.entries().stream().filter(e->e.document().id().equals(id))
   .allMatch(e->e.document().references().stream().allMatch(ref->allowed(ref,user,new HashSet<>())));
 }
 private Prepared prepare(String ask,UserContext user){
  Identity.require(user);String safe=SearchText.sanitize(ask);
  if(safe.isBlank())return new Prepared(safe,List.of(),Map.of(),List.of(),List.of());
  if(!gateway.model().equals(index.model()) || gateway.dimensions()!=index.dimensions())throw new EngineException(EngineErrorCode.NOT_READY,"Embedding index incompatible");
  var eligible=new ArrayList<EmbeddingIndex.Entry>();var rejected=new ArrayList<Rejection>();
  for(var entry:index.entries()){
   String id=entry.document().id();
   if(allowed(id,user,new HashSet<>()) && entry.document().references().stream().allMatch(ref->allowed(ref,user,new HashSet<>())))eligible.add(entry);
   else rejected.add(new Rejection(id,"prefilter","POLICY_EXCLUDED",null));
  }
  if(eligible.isEmpty())return new Prepared(safe,eligible,Map.of(),rejected,List.of());
  List<Double> query;try{query=gateway.embedQuery(safe);if(query.size()!=index.dimensions() || query.stream().anyMatch(v->v==null || !Double.isFinite(v)))throw new IllegalArgumentException();}catch(RuntimeException ex){throw new EngineException(EngineErrorCode.NOT_READY,"Query embedding unavailable");}
  var scores=new HashMap<String,Double>();for(var e:eligible)scores.put(e.document().id(),cosine(query,e.vector()));
  return new Prepared(safe,eligible,scores,rejected,query);
 }
 private Evaluation select(Prepared prepared,UserContext user,Mode mode,boolean diagnostics){
  return select(prepared,user,mode,diagnostics,Set.of());
 }
 private Evaluation select(Prepared prepared,UserContext user,Mode mode,boolean diagnostics,Set<String> required){
  if(required.size()>(experimental() && options.strategy()!=GraphRetrieval.Strategy.SELECTIVE?64:seedLimit()))throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Resolved intent seed budget exceeded");
  for(String id:required)if(!eligible(id,user))throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Resolved meaning is unavailable");
  if(!prepared.eligible().stream().map(e->e.document().id()).collect(java.util.stream.Collectors.toSet()).containsAll(required))throw new EngineException(EngineErrorCode.NOT_READY,"Resolved meaning is absent from the index");
  var scores=new HashMap<String,Double>();
  for(var e:prepared.eligible()){
   String id=e.document().id();
   if(mode==Mode.TERM_ONLY){
    if(exact(prepared.ask(),id)){
     String target=registry.terms().stream().filter(t->t.id().equals(id)).map(SemanticRegistry.Term::target).findFirst().orElse(id);
     if(eligible(target,user))scores.put(target,1.0);
    }
   }else scores.put(id,prepared.cosine().get(id)+(mode==Mode.PRODUCTION && exact(prepared.ask(),id)?1:0));
  }
  Comparator<Hit> order=Comparator.<Hit,Boolean>comparing(h->required.contains(h.id())).reversed().thenComparing(Comparator.comparingDouble(Hit::score).reversed()).thenComparing(Hit::id);
  var seeds=scores.entrySet().stream().filter(e->required.contains(e.getKey()) || e.getValue()>=limits.minimumScore()).map(e->new Hit(e.getKey(),e.getValue(),required.contains(e.getKey())?"resolved-intent":"seed")).sorted(order).limit(seedLimit()).toList();
  if(experimental() && mode==Mode.PRODUCTION) {
   var exactIds=new HashSet<String>();for(String id:scores.keySet())if(exact(prepared.ask(),id))exactIds.add(id);
   // SELECTIVE changes expansion only: retain the existing seed scores, priority and order.
   if(options.strategy()!=GraphRetrieval.Strategy.SELECTIVE)seeds=GraphRetrieval.seeds(prepared.cosine(),exactIds,required,options);
   if(options.strategy()==GraphRetrieval.Strategy.RECOGNITION && recognizer!=null) {
    var links=registry.edges().stream().filter(e->prepared.cosine().containsKey(e.from())&&prepared.cosine().containsKey(e.to())&&!e.type().equals("GOVERNED_BY"))
     .map(e->new GraphRetrieval.Link(e.from(),e.to(),e.type(),relationScore(e,prepared)))
     .sorted(Comparator.comparingDouble(GraphRetrieval.Link::score).reversed().thenComparing(GraphRetrieval.Link::from).thenComparing(GraphRetrieval.Link::to)).limit(24).toList();
    Set<String> recognized=recognizer.select(prepared.ask(),links);
    var safeRecognized=new HashSet<String>();for(var link:links){safeRecognized.add(link.from());safeRecognized.add(link.to());}safeRecognized.retainAll(recognized);safeRecognized.addAll(required);
    if(recognizer.valid())seeds=prepared.cosine().entrySet().stream().filter(e->safeRecognized.contains(e.getKey())).map(e->new Hit(e.getKey(),Math.max(.001,e.getValue()),required.contains(e.getKey())?"resolved-intent":"recognized-seed")).sorted(order).limit(Math.max(seedLimit(),required.size())).toList();
   }
  }
  var candidates=new HashMap<String,Hit>();seeds.forEach(h->candidates.put(h.id(),h));
  var rejected=new ArrayList<Rejection>();if(diagnostics)rejected.addAll(prepared.rejected());
  if(diagnostics)for(var e:prepared.eligible()){
   String id=e.document().id();if(!candidates.containsKey(id))rejected.add(new Rejection(id,"seed",!scores.containsKey(id)?"NO_TERM_MATCH":experimental()?"SEED_GATE_OR_LIMIT":scores.get(id)<limits.minimumScore()?"BELOW_THRESHOLD":"SEED_LIMIT",scores.get(id)));
  }
  var expanded=new ArrayList<Hit>();boolean hop=mode==Mode.PRODUCTION || mode==Mode.SIMILARITY_ONE_HOP;
  if(hop && experimental() && mode==Mode.PRODUCTION) {
   var visibleScores=new HashMap<>(prepared.cosine());
   for(String id:registry.publicNodeIds())if(!visibleScores.containsKey(id)&&eligible(id,user))visibleScores.put(id,0.0);
   for(var entry:GraphRetrieval.expand(seeds,visibleScores,registry.edges(),options).entrySet())if(entry.getValue().score()>=options.floor()) {
    var hit=new Hit(entry.getKey(),entry.getValue().score(),options.strategy()+":"+entry.getValue().path());
    if(candidates.putIfAbsent(hit.id(),hit)==null)expanded.add(hit);
   }
  }
  if(hop && !(experimental() && mode==Mode.PRODUCTION))for(var seed:seeds)for(var edge:registry.edges())if(edge.from().equals(seed.id()) && !edge.type().equals("GOVERNED_BY")){
   if(!allowed(edge.to(),user,new HashSet<>())){if(diagnostics)rejected.add(new Rejection(edge.to(),"expansion","POLICY_EXCLUDED",null));continue;}
   var hit=new Hit(edge.to(),scores.getOrDefault(edge.to(),seed.score()),"one-hop:"+seed.id());
   if(candidates.putIfAbsent(edge.to(),hit)==null)expanded.add(hit);
  }
  // Name retention never creates additional expansion seeds.
  var explicitSources=new TreeSet<String>();
  if(options.strategy()==GraphRetrieval.Strategy.ANCHORED && mode==Mode.PRODUCTION)for(var e:prepared.eligible()) {
   String id=e.document().id();if(!registry.sources().containsKey(id)||!sourceMention(prepared.ask(),registry.sources().get(id)))continue;
   explicitSources.add(id);candidates.putIfAbsent(id,new Hit(id,prepared.cosine().get(id),"explicit-source"));
  }
  // Entity context lost only to damping can still discover a registered contract.
  // Examine original source seeds only; neither explicit nor supplemented sources recurse.
  var neighbors=new ArrayList<Neighbor>();
  if(options.strategy()==GraphRetrieval.Strategy.ANCHORED && mode==Mode.PRODUCTION)for(var seed:seeds) {
   if(!registry.sources().containsKey(seed.id()))continue;
   for(var relation:registry.relations().values().stream().filter(r->r.source().equals(seed.id())).sorted(Comparator.comparing(SemanticRegistry.Relation::id)).toList()) {
    boolean visible=eligible(relation.id(),user) && prepared.cosine().containsKey(relation.target());
    Double raw=visible?prepared.cosine().get(relation.target()):null;
    Double decayed=raw==null?null:seed.score()*options.decay()*GraphRetrieval.weight("RELATES_TO")*(.25+.75*Math.max(0,raw));
    String reason=!visible?"POLICY_EXCLUDED":!relation.direction().equalsIgnoreCase("OUTGOING")?"NOT_OUTGOING":relation.joinUse().isBlank() || relation.joinUse().equalsIgnoreCase("FORBIDDEN")?"JOIN_NOT_APPROVED":raw<options.floor()?"BELOW_RAW_FLOOR":candidates.containsKey(relation.target())?"ALREADY_CANDIDATE":"RAW_NEIGHBOR";
    if(reason.equals("RAW_NEIGHBOR")) {
     var hit=new Hit(relation.target(),raw,"anchored-neighbor:"+seed.id()+">"+relation.id()+">"+relation.target());
     candidates.put(hit.id(),hit);expanded.add(hit);
    }
    if(diagnostics)neighbors.add(new Neighbor(seed.id(),relation.id(),relation.target(),raw,decayed,reason,false));
   }
  }
  if(diagnostics && hop)for(var e:prepared.eligible())if(!candidates.containsKey(e.document().id()))rejected.add(new Rejection(e.document().id(),"expansion",experimental()?"BELOW_GRAPH_SELECTION":"OUTSIDE_ONE_HOP",scores.get(e.document().id())));
  var bundles=new LinkedHashMap<String,Set<String>>();
  if(connected() && mode==Mode.PRODUCTION) {
   if(!required.isEmpty())bundles.put("resolved-intent",new TreeSet<>(required));
   if(!explicitSources.isEmpty())bundles.put("explicit-sources",explicitSources);
   for(var hit:candidates.values().stream().sorted(order).toList()) {
    String metric=registry.metrics().containsKey(hit.id())?hit.id():registry.terms().stream().filter(t->t.id().equals(hit.id()) && registry.metrics().containsKey(t.target())).map(SemanticRegistry.Term::target).findFirst().orElse(null);
    if(metric==null)continue;
    var bundle=new TreeSet<String>();bundle.add(metric);bundle.add(hit.id());
    registry.edges().stream().filter(e->e.from().equals(metric) && e.type().equals("METRIC_USES")).forEach(e->bundle.add(e.to()));
    if(bundle.stream().anyMatch(id->!allowed(id,user,new HashSet<>())))continue;
    bundles.put(metric,bundle);
    for(String id:bundle){var added=new Hit(id,hit.score(),"dependency:"+metric);if(candidates.putIfAbsent(id,added)==null)expanded.add(added);}
   }
   // A field needs its owner; a relation needs both endpoints to remain meaningful/renderable.
   // These are semantic dependencies, not invitations to expand the owners' other neighbors.
   if(experimental())for(var hit:candidates.values().stream().sorted(order).toList()) {
    var bundle=new TreeSet<String>();bundle.add(hit.id());
    if(registry.fields().containsKey(hit.id()))bundle.add(registry.fields().get(hit.id()).source());
    else if(registry.relations().containsKey(hit.id())){var rel=registry.relations().get(hit.id());bundle.add(rel.source());bundle.add(rel.target());}
    else continue;
    if(bundle.stream().anyMatch(id->!allowed(id,user,new HashSet<>())))continue;
    bundles.put("node:"+hit.id(),bundle);
    for(String id:bundle){var added=new Hit(id,hit.score(),"semantic-dependency:"+hit.id());if(candidates.putIfAbsent(id,added)==null)expanded.add(added);}
   }
   // Only an approved metric root (or an explicit entity seed) anchors candidate paths.
   var roots=new TreeSet<String>();bundles.keySet().stream().filter(registry.metrics()::containsKey).forEach(id->roots.add(registry.metrics().get(id).source()));
   if(roots.isEmpty())seeds.stream().filter(h->registry.sources().containsKey(h.id())).forEach(h->roots.add(h.id()));
   for(String root:roots)for(var seed:seeds) {
    String target=registry.fields().containsKey(seed.id())?registry.fields().get(seed.id()).source():registry.sources().containsKey(seed.id())?seed.id():null;
    if(target==null || target.equals(root))continue;
    var paths=new ArrayList<List<SemanticRegistry.Relation>>();paths(root,target,user,new HashSet<>(Set.of(root)),new ArrayList<>(),paths);
    if(paths.size()!=1)continue;
    var bundle=new TreeSet<String>();bundle.add(root);bundle.add(target);bundle.add(seed.id());
    paths.getFirst().forEach(r->{bundle.add(r.source());bundle.add(r.target());});
    bundles.put("path:"+root+":"+seed.id(),bundle);
    for(String id:bundle){var added=new Hit(id,seed.score(),"approved-path:"+root+":"+target);if(candidates.putIfAbsent(id,added)==null)expanded.add(added);}
   }
  }
  var documents=new HashMap<String,String>();index.entries().forEach(e->documents.put(e.document().id(),e.document().text()));
  var examples=registry.examples().stream().map(SemanticRegistry.Example::id).collect(java.util.stream.Collectors.toSet());
  var selected=new ArrayList<Hit>();int tokens=0,exampleCount=0;boolean truncated=false;
  if(connected() && mode==Mode.PRODUCTION)for(var entry:bundles.entrySet()) {
   var missing=entry.getValue().stream().filter(id->selected.stream().noneMatch(h->h.id().equals(id))).toList();
   int bytes=missing.stream().mapToInt(id->documents.getOrDefault(id,id).getBytes(StandardCharsets.UTF_8).length).sum();
   if(selected.size()+missing.size()>nodeLimit() || tokens+bytes>byteLimit()) {
    if(entry.getKey().equals("resolved-intent") || entry.getKey().equals("explicit-sources") || registry.metrics().containsKey(entry.getKey()) || entry.getKey().startsWith("node:") && required.contains(entry.getKey().substring(5)))throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Required semantic dependencies exceed retrieval budget");
    truncated=true;continue;
   }
   for(String id:missing)selected.add(candidates.get(id));tokens+=bytes;
  }
  for(var hit:candidates.values().stream().sorted(order).toList()){
   if(selected.stream().anyMatch(h->h.id().equals(hit.id())))continue;
   if(!allowed(hit.id(),user,new HashSet<>())){if(diagnostics)rejected.add(new Rejection(hit.id(),"selection","POLICY_EXCLUDED",null));continue;}
   int cost=documents.getOrDefault(hit.id(),hit.id()).getBytes(StandardCharsets.UTF_8).length;
   String reason=selected.size()>=nodeLimit()?"NODE_BUDGET":tokens+cost>byteLimit()?(experimental()?"BYTE_BUDGET":"TOKEN_BUDGET"):examples.contains(hit.id()) && exampleCount>=limits.examples()?"EXAMPLE_BUDGET":null;
   if(reason!=null){truncated=true;if(diagnostics)rejected.add(new Rejection(hit.id(),"selection",reason,hit.score()));continue;}
   if(connected() && mode==Mode.PRODUCTION && registry.metrics().containsKey(hit.id()) && !bundles.containsKey(hit.id()))continue;
   if(experimental() && registry.fields().containsKey(hit.id()) && selected.stream().noneMatch(h->h.id().equals(registry.fields().get(hit.id()).source())))continue;
   if(experimental() && registry.relations().containsKey(hit.id())){var rel=registry.relations().get(hit.id());if(selected.stream().noneMatch(h->h.id().equals(rel.source()))||selected.stream().noneMatch(h->h.id().equals(rel.target())))continue;}
   selected.add(hit);tokens+=cost;if(examples.contains(hit.id()))exampleCount++;
  }
  rejected.sort(Comparator.comparing(Rejection::id).thenComparing(Rejection::stage).thenComparing(Rejection::reason));expanded.sort(order);
  var selectedIds=selected.stream().map(Hit::id).collect(java.util.stream.Collectors.toSet());
  var neighborDiagnostics=neighbors.stream().map(n->{
   boolean kept=selectedIds.contains(n.target());
   String reason=n.reason();
   if(reason.equals("RAW_NEIGHBOR") && !kept)reason=rejected.stream().filter(r->r.id().equals(n.target()) && r.stage().equals("selection")).map(Rejection::reason).findFirst().orElse("NOT_SELECTED");
   return new Neighbor(n.anchor(),n.relation(),n.target(),n.rawSimilarity(),n.decayedScore(),reason,kept);
  }).toList();
  return new Evaluation(new Context(prepared.ask(),seeds,selected,registry.version(),index.version(),truncated),expanded,rejected,prepared.cosine(),neighborDiagnostics);
 }
 private void paths(String from,String target,UserContext user,Set<String> visited,List<SemanticRegistry.Relation> path,List<List<SemanticRegistry.Relation>> results) {
  if(path.size()>=3 || results.size()>1)return;
  for(var r:registry.relations().values().stream().sorted(Comparator.comparing(SemanticRegistry.Relation::id)).toList()) {
   if(!r.source().equals(from) || visited.contains(r.target()) || r.many() || !r.joinUse().equals("APPROVED") || !allowed(r.id(),user,new HashSet<>()))continue;
   var next=new ArrayList<>(path);next.add(r);
   if(r.target().equals(target))results.add(List.copyOf(next));else{var seen=new HashSet<>(visited);seen.add(r.target());paths(r.target(),target,user,seen,next,results);}
  }
 }
 private static boolean sourceMention(String ask,SemanticRegistry.Source source) {
  String normalized=java.text.Normalizer.normalize(ask,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  var names=new ArrayList<>(source.metadata().aliases());names.add(source.metadata().name());names.add(source.id());
  return names.stream().map(s->java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip()).filter(s->s.codePointCount(0,s.length())>=2)
   .anyMatch(s->java.util.regex.Pattern.compile("(?<![a-z0-9_])"+java.util.regex.Pattern.quote(s)+"(?![a-z0-9_])").matcher(normalized).find());
 }
 private static final Map<String,List<Double>> relationVectors=new java.util.concurrent.ConcurrentHashMap<>();
 private double relationScore(SemanticRegistry.Edge edge,Prepared prepared) {
  var docs=new HashMap<String,String>();prepared.eligible().forEach(e->docs.put(e.document().id(),e.document().text()));
  String text=docs.get(edge.from())+" "+edge.type()+" "+docs.get(edge.to());
  String key=gateway.model()+":"+gateway.dimensions()+":"+text;
  var vector=relationVectors.computeIfAbsent(key,k->gateway.embedDocuments(List.of(SearchText.sanitize(text))).getFirst());
  return cosine(prepared.query(),vector);
 }
 private boolean exact(String ask,String id){if(ask.toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT)))return true;return registry.terms().stream().filter(t->t.id().equals(id)).flatMap(t->t.aliases().stream()).anyMatch(a->ask.equalsIgnoreCase(a));}
 private boolean allowed(String id,UserContext user,Set<String> visited){
  if(!registry.publicNodeIds().contains(id) || !visibility.allows(user,id))return false;if(!visited.add(id))return true;
  try{
   if(registry.sources().containsKey(id))policies.source(id,user);
   else if(registry.fields().containsKey(id))policies.field(id,"search",user);
   else if(registry.relations().containsKey(id)){var r=registry.relations().get(id);return allowed(r.source(),user,visited)&&allowed(r.target(),user,visited);}
   else if(registry.metrics().containsKey(id)){var m=registry.metrics().get(id);policies.metric(id,user);if(m.field()!=null)policies.field(m.field(),"search",user);}
   else {var term=registry.terms().stream().filter(t->t.id().equals(id)).findFirst();if(term.isPresent())return allowed(term.get().target(),user,visited);var example=registry.examples().stream().filter(e->e.id().equals(id)).findFirst();return example.isPresent() && example.get().references().stream().allMatch(ref->allowed(ref,user,visited));}
   return true;
  }catch(EngineException ex){return false;}
 }
 private static double cosine(List<Double>a,List<Double>b){double dot=0,na=0,nb=0;for(int i=0;i<a.size();i++){dot+=a.get(i)*b.get(i);na+=a.get(i)*a.get(i);nb+=b.get(i)*b.get(i);}return na==0 || nb==0?-1:dot/Math.sqrt(na*nb);}
}
