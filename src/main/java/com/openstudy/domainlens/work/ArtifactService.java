package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.security.Identity;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
public final class ArtifactService implements AutoCloseable {
 private static final System.Logger LOG=System.getLogger(ArtifactService.class.getName());
 private final java.util.concurrent.atomic.AtomicLong cleanupFailures=new java.util.concurrent.atomic.AtomicLong();
 public long cleanupFailures(){return cleanupFailures.get();}
 private void cleanupFailure(){cleanupFailures.incrementAndGet();LOG.log(System.Logger.Level.WARNING,"ARTIFACT_CLEANUP_FAILED; download expiry remains enforced; cleanup will retry");}
 private final RequestStore requests;private final TraceSpanCollector trace;
 private final ArtifactStore store;private final ApprovalService approvals;private final CurrentUserResolver users;private final ExportExecutor exporter;private final Path root;private final Clock clock;
 private final ExecutorService workers=Executors.newFixedThreadPool(2);private final ScheduledExecutorService cleaner=Executors.newSingleThreadScheduledExecutor();
 private final ConcurrentMap<UUID,Cancellation> active=new ConcurrentHashMap<>();
 public ArtifactService(ArtifactStore store,ApprovalService approvals,CurrentUserResolver users,ExportExecutor exporter,Path root,Clock clock){
  this(store,approvals,users,exporter,root,clock,null,TraceSpanCollector.NOOP);
 }
 public ArtifactService(ArtifactStore store,ApprovalService approvals,CurrentUserResolver users,ExportExecutor exporter,Path root,Clock clock,RequestStore requests,TraceSpanCollector trace){
  this.requests=requests;this.trace=trace;this.store=store;this.approvals=approvals;this.users=users;this.exporter=exporter;this.root=root.toAbsolutePath().normalize();this.clock=clock;
  try{Files.createDirectories(this.root);}catch(IOException ex){throw new IllegalStateException("Artifact directory unavailable",ex);}
  store.interruptActive();cleanup();cleaner.scheduleWithFixedDelay(()->{try{cleanup();}catch(RuntimeException ignored){cleanupFailure();}},1,1,TimeUnit.MINUTES);
 }
 public Optional<ArtifactStore.Artifact> forApproval(UUID id,UserContext user){return store.forApproval(id,Identity.require(user).subjectId()).map(a->visible(a,user));}
 public ArtifactStore.Artifact get(UUID id,UserContext user){return visible(store.find(id,Identity.require(user).subjectId()).orElseThrow(ArtifactService::missing),user);}
 private ArtifactStore.Artifact visible(ArtifactStore.Artifact a,UserContext user){
  var approval=approvals.get(a.approvalId(),user);approvals.revalidate(approval,user,false);var current=Identity.require(users.resolve(a.owner()));Identity.owner(current,a.owner());approvals.revalidate(approval,current,false);
  if(a.state()==ArtifactStore.State.READY && !clock.instant().isBefore(a.expiresAt()))return new ArtifactStore.Artifact(a.id(),a.approvalId(),a.executionId(),a.owner(),a.name(),ArtifactStore.State.EXPIRED,a.rows(),a.createdAt(),a.expiresAt(),null);
  return a;
 }
 public void start(UUID approvalId,UserContext user){
  var a=store.forApproval(approvalId,Identity.require(user).subjectId()).orElseThrow(ArtifactService::missing);
  if(a.state()!=ArtifactStore.State.QUEUED)return;
  workers.submit(()->generate(a));
 }
 private void generate(ArtifactStore.Artifact a){
  if(!store.claim(a.id(),a.owner()))return;var cancellation=new Cancellation();active.put(a.id(),cancellation);Path temporary=path(a.id(),".tmp"),file=path(a.id(),".csv");
  var request=requests==null?Optional.<RequestStore.Request>empty():requests.forExecution(a.executionId(),a.owner());var collector=request.isEmpty()?TraceSpanCollector.NOOP:trace;
  try(var scope=collector.request(request.map(RequestStore.Request::id).orElse(a.executionId()),a.owner())){collector.start("export");collector.attribute("artifactId",a.id().toString());collector.attribute("approvalId",a.approvalId().toString());
   try {
   var user=Identity.require(users.resolve(a.owner()));Identity.owner(user,a.owner());var approval=approvals.get(a.approvalId(),user);
   if(approval.state()!=ApprovalStore.State.APPROVED)throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Approval not executable");
   approvals.revalidate(approval,user,true);long[] count={0};
   try(var writer=Files.newBufferedWriter(temporary,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
    writer.write('\ufeff');CsvWriter.row(writer,approval.columns().stream().map(c->c.alias()).toList());
    exporter.stream(approval.plan(),user,cancellation,row->{cancellation.check();CsvWriter.row(writer,approval.columns().stream().map(c->row.get(c.alias())).toList());count[0]++;});
   }
   var current=Identity.require(users.resolve(a.owner()));var latest=approvals.get(a.approvalId(),current);
   if(latest.state()!=ApprovalStore.State.APPROVED)throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Approval expired");
   approvals.revalidate(latest,current,true);cancellation.check();Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);
   store.finish(a.id(),a.owner(),ArtifactStore.State.READY,count[0],clock.instant().plus(Duration.ofHours(24)),null);
  }catch(Exception ex){collector.failure(ex instanceof EngineException engine?engine.code().name():"EXPORT_FAILED");delete(temporary);delete(file);store.finish(a.id(),a.owner(),ArtifactStore.State.FAILED,0,null,ex instanceof EngineException engine?engine.code().name():"EXPORT_FAILED");}
   finally{collector.end("export");}
  }finally{active.remove(a.id());}
 }
 public InputStream download(UUID id,UserContext user){var a=get(id,user);if(a.state()!=ArtifactStore.State.READY)throw new EngineException(EngineErrorCode.SCOPE_DENIED,"File expired or unavailable");try{return Files.newInputStream(path(id,".csv"));}catch(IOException ex){throw missing();}}
 private Path path(UUID id,String extension){return root.resolve(id+extension);}
 private void delete(Path file){try{Files.deleteIfExists(file);}catch(IOException ignored){cleanupFailure();}}
 public void cleanup(){store.expire(clock.instant()).forEach(a->delete(path(a.id(),".csv")));
  try(var files=Files.list(root)){files.filter(p->p.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(tmp|csv)")).forEach(p->{String name=p.getFileName().toString();UUID id=UUID.fromString(name.substring(0,36));if(!active.containsKey(id)){
   // Only orphan/interrupted temporary files are removed here. Ready files expire through metadata.
   if(!store.retain(id,clock.instant()))delete(p);
  }});}catch(IOException ex){throw new IllegalStateException("Artifact cleanup failed",ex);}
 }
 private static EngineException missing(){return new EngineException(EngineErrorCode.NOT_FOUND,"Artifact not found");}
 public void close(){cleaner.shutdownNow();active.values().forEach(Cancellation::cancel);workers.shutdownNow();}
}
