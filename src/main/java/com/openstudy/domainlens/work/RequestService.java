package com.openstudy.domainlens.work;

import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.query.QueryPipeline;
import com.openstudy.domainlens.security.Identity;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single dispatch after durable acceptance. Polling/replay never invokes the submitted job. */
public final class RequestService implements AutoCloseable {
    @FunctionalInterface public interface Job { QueryPipeline.Outcome run(Cancellation cancellation, Consumer<String> stage); }
    private final com.openstudy.domainlens.api.spi.TraceSpanCollector trace;
    private final RequestStore journal;
    private final WorkService work;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<UUID,Cancellation> active=new ConcurrentHashMap<>();
    public RequestService(RequestStore journal,WorkService work) {this(journal,work,com.openstudy.domainlens.api.spi.TraceSpanCollector.NOOP);}
    public RequestService(RequestStore journal,WorkService work,com.openstudy.domainlens.api.spi.TraceSpanCollector trace){this.trace=trace;this.journal=journal;this.work=work;journal.interruptActive();}
    public RequestStore.Request submit(UUID conversation,String key,String hash,String question,Instant asOf,String version,UserContext user,Job job) {
        return submit(conversation,key,hash,question,asOf,version,user,job,null,null);
    }
    public RequestStore.Request submit(UUID conversation,String key,String hash,String question,Instant asOf,String version,UserContext user,Job job,UUID referenceExecutionId,UUID referenceRequestId) {
        String owner=Identity.require(user).subjectId();
        var accepted=journal.accept(conversation,owner,key,hash,asOf,version,new RequestStore.Context(question,referenceExecutionId,referenceRequestId,com.openstudy.domainlens.catalog.SearchText.hash(com.openstudy.domainlens.api.ir.ContractJson.canonical(Identity.require(user)))));
        if(!accepted.created())return accepted.request();
        var id=accepted.request().id();var cancellation=new Cancellation();active.put(id,cancellation);
        try { executor.submit(()->execute(id,conversation,question,user,cancellation,job)); }
        catch(RejectedExecutionException ex) {active.remove(id);journal.append(id,owner,"failed",Map.of("code","SERVER_INTERRUPTED","retryable",true,"notice",com.openstudy.domainlens.api.ir.OutcomeNotice.forCode("SERVER_INTERRUPTED")));}
        return accepted.request();
    }
    private void execute(UUID id,UUID conversation,String question,UserContext user,Cancellation cancellation,Job job) {
        String owner=user.subjectId();
        try(var scope=trace.request(id,owner)) {
          trace.start("request");String traceOutcome=null;
          try {
            cancellation.check();
            var outcome=job.run(cancellation,stage->{cancellation.check();if(!journal.append(id,owner,stage,Map.of())){cancellation.cancel();cancellation.check();}});
            traceOutcome=outcome.result().status();
            cancellation.check();
            trace.attribute("finalIr",com.openstudy.domainlens.api.ir.ContractJson.write(outcome.ir()));trace.attribute("usage",com.openstudy.domainlens.api.ir.ContractJson.write(outcome.usage()));
            var saved=work.complete(conversation,question,outcome,user,true);
            cancellation.check();
            String type=outcome.result().status().equals("COMPLETED")?"completed":"clarification";
            journal.append(id,owner,type,Map.of("executionId",saved.toString()));
        } catch(EngineException ex) {
            if(ex.code()==EngineErrorCode.SCOPE_DENIED||ex.code()==EngineErrorCode.PII_DENIED||ex.code()==EngineErrorCode.FORMULA_CONFLICT)traceOutcome="DENIED";trace.failure(ex.code().name());trace.attribute("usage",com.openstudy.domainlens.api.ir.ContractJson.write(ex.usage()));
            journal.append(id,owner,ex.code()==EngineErrorCode.CANCELED?"canceled":"failed",Map.of("code",ex.code().name(),"retryable",retryable(ex.code()),"usage",ex.usage(),"notice",com.openstudy.domainlens.api.ir.OutcomeNotice.forCode(ex.code().name())));
        } catch(RuntimeException ex) {
            trace.failure(queryTimeout(ex)?"QUERY_TIMEOUT":"INTERNAL_ERROR");
            journal.append(id,owner,"failed",Map.of("code",queryTimeout(ex)?"QUERY_TIMEOUT":"INTERNAL_ERROR","retryable",queryTimeout(ex),"notice",com.openstudy.domainlens.api.ir.OutcomeNotice.forCode(queryTimeout(ex)?"QUERY_TIMEOUT":"INTERNAL_ERROR")));
        } finally {String state=journal.find(id,owner).orElseThrow().state();if(state.equals("canceled"))trace.failure("CANCELED");trace.end("request");trace.finish(state.equals("clarification")&&"UNSUPPORTED".equals(traceOutcome)||state.equals("failed")&&"DENIED".equals(traceOutcome)?"denied":state);}
        } catch(RuntimeException ex){journal.append(id,owner,"failed",Map.of("code","INTERNAL_ERROR","retryable",false,"notice",com.openstudy.domainlens.api.ir.OutcomeNotice.forCode("INTERNAL_ERROR")));} finally {active.remove(id,cancellation);}
    }
    private static boolean queryTimeout(Throwable failure){for(int i=0;failure!=null && i<8;i++,failure=failure.getCause())if(failure instanceof jakarta.persistence.QueryTimeoutException || failure instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState()))return true;return false;}
    private static boolean retryable(EngineErrorCode code) {return Set.of(EngineErrorCode.MODEL_FAILED,EngineErrorCode.MODEL_TIMEOUT,EngineErrorCode.MODEL_INCOMPLETE,EngineErrorCode.QUERY_TIMEOUT,EngineErrorCode.NOT_READY).contains(code);}
    public Optional<RequestStore.Request> forExecution(UUID execution,UserContext user){return journal.forExecution(execution,Identity.require(user).subjectId());}
    public RequestStore.Request get(UUID id,UserContext user) {return journal.find(id,Identity.require(user).subjectId()).orElseThrow(()->new EngineException(EngineErrorCode.NOT_FOUND,"Request not found"));}
    public List<RequestStore.Event> events(UUID id,long after,UserContext user) {return journal.events(id,Identity.require(user).subjectId(),after);}
    public boolean cancel(UUID id,UserContext user) {
        String owner=Identity.require(user).subjectId();
        boolean won=journal.append(id,owner,"canceled",Map.of("code","CANCELED","retryable",true));
        if(won){var token=active.get(id);if(token!=null)token.cancel();}return won;
    }
    @Override public void close() { active.values().forEach(Cancellation::cancel);executor.shutdownNow(); }
}
