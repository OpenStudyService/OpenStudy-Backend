package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.retrieval.QuestionNormalizer;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.catalog.SearchText;
import java.util.*;
/** Consumer normalization, sanitized retrieval, completeness, and body-free diagnostic spans. */
public final class RetrievalService {
 public record Result(Retriever.Context context,ContextGate.Decision decision){}
 private final java.util.function.Consumer<Retriever.Evaluation> observer;
 private final QuestionNormalizer normalizer;private final Retriever retriever;private final ContextGate gate;private final TraceSpanCollector trace;
 public RetrievalService(QuestionNormalizer normalizer,Retriever retriever,ContextGate gate,TraceSpanCollector trace){this(normalizer,retriever,gate,trace,null);}
 public RetrievalService(QuestionNormalizer normalizer,Retriever retriever,ContextGate gate,TraceSpanCollector trace,java.util.function.Consumer<Retriever.Evaluation> observer){this.observer=observer;this.normalizer=normalizer;this.retriever=retriever;this.gate=gate;this.trace=trace;}
 public void requireBudget(Set<String> ids){retriever.requireCombinedBudget(ids);}
 public void requireVisible(Set<String> ids,UserContext user){
  for(String id:ids)if(!retriever.eligible(id,user))throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.SCOPE_DENIED,"Reference metadata is no longer available");
 }
 public Result prepare(String ask,UserContext user,com.openstudy.domainlens.security.SensitiveTokens tokens){
  return prepare(ask,user,tokens,com.openstudy.domainlens.api.prompt.PromptLimits.defaults());
 }
 public Result prepare(String ask,UserContext user,com.openstudy.domainlens.security.SensitiveTokens tokens,com.openstudy.domainlens.api.prompt.PromptLimits limits){
  var context=prepareContext(ask,user,tokens,limits);return new Result(context,gate.inspect(context));
 }
 /** The pipeline may add a verified reference before applying the completeness gate. */
 public Retriever.Context prepareContext(String ask,UserContext user,com.openstudy.domainlens.security.SensitiveTokens tokens,com.openstudy.domainlens.api.prompt.PromptLimits limits){
  return prepareContext(ask,user,tokens,limits,text->Set.of());
 }
 public Retriever.Context prepareContext(String ask,UserContext user,com.openstudy.domainlens.security.SensitiveTokens tokens,com.openstudy.domainlens.api.prompt.PromptLimits limits,java.util.function.Function<String,Set<String>> resolved){
  limits.question(ask);return trace.measure("retrieval",()->{String safe=trace.measure("normalization",()->tokens.protect(normalizer.normalize(tokens.protect(ask),user)));limits.question(safe);return selected(safe,user,resolved.apply(safe));});
 }
 private Retriever.Context selected(String safe,UserContext user){
  return selected(safe,user,Set.of());
 }
 private Retriever.Context selected(String safe,UserContext user,Set<String> required){
  var context=required.isEmpty()?(observer==null?retriever.retrieve(safe,user):retriever.retrieve(safe,user,observer)):retriever.retrieve(safe,user,required,observer);trace.attribute("graphVersion",context.graphVersion());trace.attribute("indexVersion",context.indexVersion());trace.attribute("seeds",context.seeds().stream().map(Retriever.Hit::id).toList().toString());trace.attribute("selection",context.nodes().toString());return context;
 }
 public Result prepare(String ask,UserContext user){
  trace.start("retrieval");try{String safe=SearchText.sanitize(normalizer.normalize(SearchText.sanitize(ask),user));var context=selected(safe,user);return new Result(context,gate.inspect(context));}finally{trace.end("retrieval");}
 }
}
