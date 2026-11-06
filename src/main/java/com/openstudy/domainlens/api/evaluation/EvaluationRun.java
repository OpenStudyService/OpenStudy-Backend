package com.openstudy.domainlens.api.evaluation;
import java.time.Instant;
import java.util.*;
import com.openstudy.domainlens.api.prompt.ModelUsage;
/** Persisted, redacted measurements. Null measurements are not successes. */
public record EvaluationRun(UUID id,String owner,String dataset,List<Mode> modes,State state,
 Instant startedAt,Instant finishedAt,Map<String,String> versions,int total,List<CaseResult> results,String errorCode) {
 public enum Mode { PROMPT_ONLY, RETRIEVAL, FORMULA_RETRIEVAL }
 public enum State { QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED }
 public record Count(long matched,long eligible){public Count{if(matched<0||eligible<matched)throw new IllegalArgumentException("Invalid measurement count");}}
 public record CaseResult(String caseId,Mode mode,boolean supported,String expected,String actual,boolean passed,
  long durationMillis,Map<String,Boolean> checks,List<ModelUsage> usage,List<String> failures,Map<String,Count> counts) {
  public CaseResult(String caseId,Mode mode,boolean supported,String expected,String actual,boolean passed,long durationMillis,Map<String,Boolean> checks,List<ModelUsage> usage,List<String> failures){this(caseId,mode,supported,expected,actual,passed,durationMillis,checks,usage,failures,Map.of());}
  public CaseResult { counts=counts==null?Map.of():Map.copyOf(counts);checks=Map.copyOf(checks);usage=List.copyOf(usage);failures=List.copyOf(failures); }
 }
 public record Measurement(long numerator,long denominator,Double value,String unit) {}
 public EvaluationRun { modes=List.copyOf(modes);versions=Map.copyOf(versions);results=List.copyOf(results); }
 public Map<String,Measurement> metrics(Mode mode){
  var selected=results.stream().filter(r->r.mode()==mode).toList();var values=new LinkedHashMap<String,Measurement>();
  for(String key:List.of("schema","metric","binding","period","task","actionSafety","modelSuccess","piiRedaction","humanConfirm","recall")){
   var measured=selected.stream().filter(r->r.checks().containsKey(key)).toList();long pass=measured.stream().filter(r->r.checks().get(key)).count();values.put(key,new Measurement(pass,measured.size(),measured.isEmpty()?null:100.0*pass/measured.size(),"percent"));
  }
  for(String key:List.of("recall")){long matched=0,eligible=0;for(var row:selected){var count=row.counts().get(key);if(count!=null){matched+=count.matched();eligible+=count.eligible();}}if(eligible>0)values.put(key,new Measurement(matched,eligible,100.0*matched/eligible,"percent"));}
  var calls=selected.stream().flatMap(r->r.usage().stream()).toList();long received=calls.stream().filter(u->u.status().equals("RECEIVED")).count();values.put("modelSuccess",new Measurement(received,calls.size(),calls.isEmpty()?null:100.0*received/calls.size(),"percent"));
  var times=selected.stream().mapToLong(CaseResult::durationMillis).sorted().toArray();values.put("e2eP95",new Measurement(0,times.length,times.length==0?null:(double)times[(int)Math.ceil(times.length*.95)-1],"ms"));return values;
 }
}
