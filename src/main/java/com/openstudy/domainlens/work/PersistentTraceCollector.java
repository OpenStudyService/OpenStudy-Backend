package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.TraceStore;
import com.openstudy.domainlens.api.spi.TraceSpanCollector;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.catalog.SearchText;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
/** Thread-bound request context; only allowlisted, redacted attributes reach storage. */
public final class PersistentTraceCollector implements TraceSpanCollector,AutoCloseable {
 private static final Set<String> ATTRIBUTES=Set.of("graphVersion","indexVersion","seeds","selection","mode","usage","finalIr","approvalId","artifactId","executionId");
 private static final class Active {final UUID id=UUID.randomUUID();final String name;final Instant at=Instant.now();final long start=System.nanoTime();final Map<String,String> attributes=new LinkedHashMap<>();String error;Active(String name){this.name=name;}}
 private record Context(UUID id,String owner,Deque<Active> spans) {}
 private final ThreadLocal<Context> context=new ThreadLocal<>();private final TraceStore store;private final Clock clock;private final ScheduledExecutorService cleaner=Executors.newSingleThreadScheduledExecutor();
 public PersistentTraceCollector(TraceStore store,Clock clock){this.store=store;this.clock=clock;store.recover();store.purge(clock.instant().minus(Duration.ofDays(30)));cleaner.scheduleWithFixedDelay(()->{try{store.purge(clock.instant().minus(Duration.ofDays(30)));}catch(RuntimeException ignored){System.getLogger(PersistentTraceCollector.class.getName()).log(System.Logger.Level.WARNING,"TRACE_CLEANUP_FAILED; expiry filtering remains enforced; cleanup will retry");}},1,1,TimeUnit.HOURS);}
 public Scope request(UUID id,String owner){var previous=context.get();var current=new Context(id,owner,new ArrayDeque<>());store.begin(id,owner,clock.instant());context.set(current);return ()->{try{while(!current.spans().isEmpty()){failure("INCOMPLETE_SPAN");end(current.spans().peek().name);}}finally{if(previous==null)context.remove();else context.set(previous);}};}
 public void start(String name){var current=context.get();if(current==null)return;if(!Set.of("request","normalization","retrieval","model","validation","authorization","jpa","binding","approval","export","canned","registeredAction").contains(name))throw new IllegalArgumentException("Unknown trace stage");var active=new Active(name);current.spans().push(active);store.span(current.id(),snapshot(active,"RUNNING",null));}
 public void attribute(String key,String value){var current=context.get();if(current==null||current.spans().isEmpty()||value==null||!ATTRIBUTES.contains(key))return;String safe=key.equals("finalIr")?ContractJson.write(redact(ContractJson.read(value,Object.class))):SearchText.sanitize(value);if(safe.length()>32768)safe=safe.substring(0,32768);current.spans().peek().attributes.put(key,safe);}
 private static Object redact(Object value){if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((key,item)->result.put(key.toString(),Set.of("values","clarification","unsupportedReason","title","label","description").contains(key.toString())?"[MASKED]":redact(item)));return result;}if(value instanceof List<?> list)return list.stream().map(PersistentTraceCollector::redact).toList();return value instanceof String text?SearchText.sanitize(text):value;}
 public void failure(String code){var current=context.get();if(current!=null&&!current.spans().isEmpty())current.spans().peek().error=code!=null&&code.matches("[A-Z_]{1,64}")?code:"INTERNAL_ERROR";}
 private TraceStore.Span snapshot(Active active,String state,Long duration){return new TraceStore.Span(active.id,active.name,state,active.at,duration,active.error,Map.copyOf(active.attributes));}
 public void end(String name){var current=context.get();if(current==null)return;var active=current.spans().peek();if(active==null||!active.name.equals(name))throw new IllegalStateException("Unbalanced trace span");current.spans().pop();store.span(current.id(),snapshot(active,active.error==null?"COMPLETED":active.error.equals("CANCELED")?"CANCELED":"FAILED",System.nanoTime()-active.start));}
 public void finish(String state){var current=context.get();if(current!=null)store.finish(current.id(),state);}
 public void close(){cleaner.shutdownNow();}
}
