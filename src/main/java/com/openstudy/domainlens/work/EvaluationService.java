package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.evaluation.*;
import com.openstudy.domainlens.api.evaluation.EvaluationRun.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import com.openstudy.domainlens.api.error.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
/** Single-instance runner; durable idempotency and no automatic replay after restart. */
public final class EvaluationService implements AutoCloseable {
 private final EvaluationStore store;private final EvaluationSuite suite;private final EvaluationAccessPolicy policy;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Clock clock;private volatile Cancellation active;
 public EvaluationService(EvaluationStore store,EvaluationSuite suite,EvaluationAccessPolicy policy,Clock clock){this.store=store;this.suite=suite;this.policy=policy;this.clock=clock;store.interruptActive();}
 public synchronized EvaluationRun start(String dataset,List<Mode> modes,String key,UserContext user){
  policy.authorize(user);if(!suite.dataset().equals(dataset)||modes==null||modes.isEmpty()||modes.stream().anyMatch(Objects::isNull)||new HashSet<>(modes).size()!=modes.size()||key==null||!key.matches("[A-Za-z0-9_-]{1,128}"))throw new EngineException(EngineErrorCode.VALIDATION_ERROR,"Invalid evaluation request");
  var existing=store.byKey(user.subjectId(),key);if(existing.isPresent())return compatible(existing.get(),dataset,modes);
  var run=new EvaluationRun(UUID.randomUUID(),user.subjectId(),dataset,modes,State.QUEUED,clock.instant(),null,suite.versions(),suite.caseIds().size()*modes.size(),List.of(),null);
  var created=store.create(run,key);if(!created.id().equals(run.id()))return compatible(created,dataset,modes);
  worker.submit(()->execute(run));return run;
 }
 private EvaluationRun compatible(EvaluationRun run,String dataset,List<Mode> modes){if(!run.dataset().equals(dataset)||!run.modes().equals(modes))throw new EngineException(EngineErrorCode.VALIDATION_ERROR,"Idempotency key reused with different inputs");return run;}
 private void execute(EvaluationRun initial){var results=new ArrayList<CaseResult>();var cancellation=new Cancellation();active=cancellation;State state=State.RUNNING;String error=null;
  try{store.save(copy(initial,state,results,null));for(var mode:initial.modes())for(String id:suite.caseIds()){
    cancellation.check();if(!initial.versions().equals(suite.versions()))throw new EngineException(EngineErrorCode.NOT_READY,"Evaluation versions changed");
    var result=suite.execute(id,mode,cancellation);if(!id.equals(result.caseId())||mode!=result.mode())throw new IllegalStateException("Mismatched case result");results.add(result);store.save(copy(initial,state,results,null));
   }state=State.COMPLETED;
  }catch(EngineException ex){state=ex.code()==EngineErrorCode.CANCELED?State.INTERRUPTED:State.FAILED;error=ex.code().name();}
   catch(RuntimeException ex){state=State.FAILED;error="INTERNAL_ERROR";}
   finally{try{store.save(copy(initial,state,results,error));}finally{active=null;}}
 }
 private EvaluationRun copy(EvaluationRun r,State state,List<CaseResult> results,String error){return new EvaluationRun(r.id(),r.owner(),r.dataset(),r.modes(),state,r.startedAt(),state==State.RUNNING?null:clock.instant(),r.versions(),r.total(),results,error);}
 public EvaluationRun get(UUID id,UserContext user){policy.authorize(user);return store.find(id).orElseThrow(()->new EngineException(EngineErrorCode.NOT_FOUND,"Evaluation not found"));}
 public List<EvaluationRun> list(int page,int size,UserContext user){policy.authorize(user);if(page<0||size<1||size>100)throw new EngineException(EngineErrorCode.VALIDATION_ERROR,"Invalid evaluation page");return store.list(page,size);}
 public void close(){if(active!=null)active.cancel();worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
