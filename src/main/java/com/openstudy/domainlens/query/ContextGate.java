package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.catalog.*;
import java.util.*;
/** Completeness is checked against approved dependencies, never by unbounded graph expansion. */
public final class ContextGate {
 public record Decision(IrStatus status,List<ClarificationOption> options,String reason,Set<String> contextIds,ReasonCode reasonCode){
  public Decision{options=List.copyOf(options);contextIds=Set.copyOf(contextIds);}
  public Decision(IrStatus status,List<ClarificationOption> options,String reason,Set<String> contextIds){this(status,options,reason,contextIds,ReasonCode.defaultFor(status));}
 }
 private final SemanticRegistry registry;
 public ContextGate(SemanticRegistry registry){this.registry=registry;}
 public Decision inspect(Retriever.Context context){
  if(!registry.version().equals(context.graphVersion()))throw new EngineException(EngineErrorCode.NOT_READY,"Context version mismatch");
  var ids=context.ids();if(context.seeds().isEmpty())return new Decision(IrStatus.UNSUPPORTED,List.of(),"No related approved context",Set.of(),ReasonCode.KNOWLEDGE_MISSING);
  var targets=new TreeSet<String>();for(var term:registry.terms())if(ids.contains(term.id()) && term.aliases().stream().anyMatch(a->a.equalsIgnoreCase(context.sanitizedAsk())))targets.add(term.target());
  if(targets.size()>1)return new Decision(IrStatus.NEEDS_CLARIFICATION,targets.stream().map(id->new ClarificationOption(id,label(id))).toList(),"Ambiguous registered term",ids,ReasonCode.METRIC_AMBIGUOUS);
  // Two named relations to the same entity must be selected explicitly, not guessed.
  for(var source:registry.sources().values())if(ids.contains(source.id())){
   var groups=registry.relations().values().stream().filter(rel->rel.source().equals(source.id()) && ids.contains(rel.target())).collect(java.util.stream.Collectors.groupingBy(SemanticRegistry.Relation::target));
   for(var group:groups.values())if(group.size()>1 && mentionsTarget(context.sanitizedAsk(),group.getFirst().target()) && group.stream().noneMatch(rel->context.sanitizedAsk().contains(rel.id()) || (!rel.meaning().isBlank() && context.sanitizedAsk().contains(rel.meaning()))))return new Decision(IrStatus.NEEDS_CLARIFICATION,group.stream().sorted(Comparator.comparing(SemanticRegistry.Relation::id)).map(rel->new ClarificationOption(rel.id(),rel.meaning().isBlank()?rel.id():rel.meaning())).toList(),"Select a registered relation",ids,ReasonCode.SEMANTIC_GROUNDING_FAILED);
  }
  for(String id:ids)if(registry.metrics().containsKey(id))requireMetric(id,ids);
  for(var term:registry.terms())if(ids.contains(term.id()) && registry.metrics().containsKey(term.target()))requireMetric(term.target(),ids);
  if(context.truncated())for(var seed:context.seeds()){
   if(registry.metrics().containsKey(seed.id()))requireMetric(seed.id(),ids);
   for(var term:registry.terms())if(term.id().equals(seed.id()) && registry.metrics().containsKey(term.target()))requireMetric(term.target(),ids);
  }
  return new Decision(IrStatus.READY,List.of(),null,ids);
 }
 public void requireMetric(String id,Set<String> ids){
  var metric=registry.metrics().get(id);if(metric==null || !ids.contains(id))incomplete();
  var required=new HashSet<String>();required.add(metric.source());if(metric.field()!=null)required.add(metric.field());
  String current=metric.source();if(metric.path().size()>3)incomplete();
  for(String path:metric.path()){var relation=registry.relations().get(path);if(relation==null || !relation.source().equals(current) || relation.many())incomplete();required.add(relation.target());current=relation.target();}
  dependencies(metric.defaultFilter(),required);required.addAll(metric.contextFields());
  if(!ids.containsAll(required))incomplete();
 }
 private void dependencies(Filter filter,Set<String> required){if(filter instanceof Filter.Leaf l)required.add(l.field());else if(filter instanceof Filter.Group g)g.children().forEach(f->dependencies(f,required));}
 private boolean mentionsTarget(String ask,String id){
  var source=registry.sources().get(id);var names=new ArrayList<String>(source.metadata().aliases());
  names.add(source.metadata().name());names.add(id);names.add(id.substring(id.lastIndexOf('.')+1));
  String text=ask.toLowerCase(Locale.ROOT);
  return names.stream().filter(name->!name.isBlank()).anyMatch(name->{
   String value=name.toLowerCase(Locale.ROOT);
   // Latin words need boundaries; Korean names may have particles (e.g. 언어별).
   return value.matches("[a-z0-9_ .]+")?java.util.regex.Pattern.compile("(?<![a-z0-9_])"+java.util.regex.Pattern.quote(value)+"(?![a-z0-9_])").matcher(text).find():text.contains(value);
  });
 }
 private String label(String id){var source=registry.sources().get(id);return source==null || source.metadata().name().isBlank()?id:source.metadata().name();}
 private static void incomplete(){throw new EngineException(EngineErrorCode.METRIC_INCOMPLETE,"Approved metric dependencies are incomplete");}
}
