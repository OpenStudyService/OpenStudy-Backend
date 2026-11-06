package com.openstudy.domainlens.query;

import com.openstudy.domainlens.catalog.SemanticRegistry;
import java.util.*;

/** Experimental query-local graph scoring. Input contains visible nodes only. */
public final class GraphRetrieval {
 public enum Strategy { LEGACY, SELECTIVE, ANCHORED, RIPPLE, PPR, RECOGNITION }
 public record Options(Strategy strategy,int seeds,int nodes,int bytes,int hops,double decay,double restart,double floor,double gap) {
  public Options {
   Objects.requireNonNull(strategy);
   if(seeds<1||seeds>64||nodes<1||nodes>256||bytes<1||hops<0||hops>8||!Double.isFinite(decay)||decay<=0||decay>=1||!Double.isFinite(restart)||restart<=0||restart>=1||!Double.isFinite(floor)||floor<0||floor>1||!Double.isFinite(gap)||gap<0||gap>2)throw new IllegalArgumentException("Invalid graph retrieval options");
  }
  public static Options defaults(){return new Options(Strategy.LEGACY,8,40,4096,2,.6,.5,.25,.08);}
  public static Options forStrategy(Strategy strategy){return strategy==Strategy.ANCHORED?new Options(strategy,4,40,8192,2,.6,.5,.25,.08):new Options(strategy,8,40,4096,2,.6,.5,.25,.08);}
 }
 public record Link(String from,String to,String type,double score) {}
 public interface Recognizer { Set<String> select(String protectedQuestion,List<Link> candidates); default boolean valid(){return true;} default List<com.openstudy.domainlens.api.prompt.ModelUsage> usage(){return List.of();} }
 public record Scored(double score,String path) {}
 private record Arc(String to,double weight,String type) {}
 private GraphRetrieval() {}
 public static List<Retriever.Hit> seeds(Map<String,Double> cosine,Set<String> exact,Set<String> required,Options o) {
  double best=cosine.values().stream().mapToDouble(Double::doubleValue).max().orElse(-1);
  return cosine.entrySet().stream().filter(e->required.contains(e.getKey())||exact.contains(e.getKey())||e.getValue()>=o.floor()&&e.getValue()>=best-o.gap())
   .map(e->new Retriever.Hit(e.getKey(),required.contains(e.getKey())?1:exact.contains(e.getKey())?1:Math.max(0,e.getValue()),required.contains(e.getKey())?"resolved-intent":exact.contains(e.getKey())?"exact-seed":"similarity-seed"))
   .sorted(Comparator.<Retriever.Hit,Boolean>comparing(h->required.contains(h.id())).reversed().thenComparing(Comparator.comparingDouble(Retriever.Hit::score).reversed()).thenComparing(Retriever.Hit::id))
   .limit(Math.max(o.seeds(),required.size())).toList();
 }
 public static Map<String,Scored> expand(List<Retriever.Hit> seeds,Map<String,Double> cosine,List<SemanticRegistry.Edge> edges,Options o) {
  var adjacency=new TreeMap<String,List<Arc>>();cosine.keySet().forEach(id->adjacency.put(id,new ArrayList<>()));
  for(var e:edges)if(adjacency.containsKey(e.from())&&adjacency.containsKey(e.to())&&!e.type().equals("GOVERNED_BY")) {
   double w=weight(e.type());adjacency.get(e.from()).add(new Arc(e.to(),w,e.type()));
   // Search directions are independent of approved execution join directions.
   if(o.strategy()!=Strategy.SELECTIVE && o.strategy()!=Strategy.ANCHORED)adjacency.get(e.to()).add(new Arc(e.from(),w*.7,"reverse:"+e.type()));
  }
  adjacency.values().forEach(as->as.sort(Comparator.comparing(Arc::to).thenComparing(Arc::type)));
  if(o.strategy()==Strategy.PPR||o.strategy()==Strategy.RECOGNITION)return pageRank(seeds,adjacency,o);
  return ripple(seeds,adjacency,cosine,o);
 }
 private record Wave(String id,double energy,String path,int depth) {}
 private static Map<String,Scored> ripple(List<Retriever.Hit> seeds,Map<String,List<Arc>> graph,Map<String,Double> cosine,Options o) {
  var queue=new PriorityQueue<Wave>(Comparator.comparingDouble(Wave::energy).reversed().thenComparing(Wave::path).thenComparingInt(Wave::depth));
  seeds.forEach(s->queue.add(new Wave(s.id(),s.score(),s.id(),0)));
  var visited=new HashSet<String>();var result=new TreeMap<String,Scored>();int max=o.strategy()==Strategy.SELECTIVE || o.strategy()==Strategy.ANCHORED?1:o.hops();
  while(!queue.isEmpty()) {
   var wave=queue.remove();if(!visited.add(wave.depth()+":"+wave.id()))continue;
   double include=wave.depth()==0?wave.energy():wave.energy()*(.25+.75*Math.max(0,cosine.getOrDefault(wave.id(),0.0)));
   var old=result.get(wave.id());if(old==null||include>old.score())result.put(wave.id(),new Scored(include,wave.path()));
   if(wave.depth()<max&&wave.energy()>=o.floor())for(var arc:graph.getOrDefault(wave.id(),List.of())) {
    double next=wave.energy()*o.decay()*arc.weight();
    if(next>=o.floor()*.25)queue.add(new Wave(arc.to(),next,wave.path()+">"+arc.type()+">"+arc.to(),wave.depth()+1));
   }
  }
  return result;
 }
 private static Map<String,Scored> pageRank(List<Retriever.Hit> seeds,Map<String,List<Arc>> graph,Options o) {
  var reset=new TreeMap<String,Double>();double sum=seeds.stream().mapToDouble(h->Math.max(.001,h.score())).sum();
  if(sum==0)return Map.of();seeds.forEach(h->reset.put(h.id(),Math.max(.001,h.score())/sum));
  Map<String,Double> scores=new TreeMap<>(reset);
  for(int i=0;i<100;i++) {
   var next=new TreeMap<String,Double>();reset.forEach((id,s)->next.put(id,o.restart()*s));
   for(var entry:scores.entrySet()) {
    var arcs=graph.getOrDefault(entry.getKey(),List.of());double total=arcs.stream().mapToDouble(Arc::weight).sum();
    if(total==0)reset.forEach((id,s)->next.merge(id,(1-o.restart())*entry.getValue()*s,Double::sum));
    else for(var arc:arcs)next.merge(arc.to(),(1-o.restart())*entry.getValue()*arc.weight()/total,Double::sum);
   }
   var ids=new HashSet<>(scores.keySet());ids.addAll(next.keySet());double delta=0;for(String id:ids)delta+=Math.abs(next.getOrDefault(id,0.0)-scores.getOrDefault(id,0.0));
   scores=next;if(delta<1e-9)break;
  }
  double max=scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
  var result=new TreeMap<String,Scored>();scores.forEach((id,s)->result.put(id,new Scored(s/max,"ppr")));return result;
 }
 public static double weight(String type){return switch(type){case "ALIAS_OF","METRIC_USES"->1;case "RELATION_ENDPOINT","RELATES_TO"->.8;case "HAS_FIELD"->.5;case "EXAMPLE_USES"->.3;default->.2;};}
}
