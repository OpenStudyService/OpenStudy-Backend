package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.evaluation.EvaluationScenarioVerifier;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.*;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.query.*;
import com.openstudy.domainlens.security.PolicyEngine;
import java.util.*;
import java.time.*;
/** Probes only the evaluation's own saved execution and artifacts. No global restarts or clock changes. */
public final class EvaluationOperations implements EvaluationScenarioVerifier {
 private final WorkStore work;private final RequestStore requests;private final QueryExecutor executor;private final PolicyEngine policies;private final ApprovalService approvals;private final ArtifactService files;private final ArtifactStore artifacts;private final ApprovalStore approvalStore;private final IrValidator validator;private final ApprovalPolicy approvalPolicy;private final CurrentUserResolver users;
 public EvaluationOperations(WorkStore work,RequestStore requests,QueryExecutor executor,PolicyEngine policies,ApprovalService approvals,ArtifactService files,ArtifactStore artifacts,ApprovalStore approvalStore,IrValidator validator,ApprovalPolicy approvalPolicy,CurrentUserResolver users){this.work=work;this.requests=requests;this.executor=executor;this.policies=policies;this.approvals=approvals;this.files=files;this.artifacts=artifacts;this.approvalStore=approvalStore;this.validator=validator;this.approvalPolicy=approvalPolicy;this.users=users;}
 public Map<String,Boolean> verify(String operation,UUID executionId,UserContext user,boolean actionAllowed){
  var saved=work.execution(executionId,user.subjectId()).orElseThrow();var ds=saved.datasets().getFirst();var pageService=new PageService(work,policies,executor);
  switch(operation){
   case "PAGE","SORT" -> {var order=operation.equals("SORT")?List.of(new QuerySpec.Order(ds.columns().getFirst().alias(),QuerySpec.Direction.ASC)):null;var page=pageService.page(executionId,ds.id(),new PageRequest(operation.equals("PAGE")?1:0,10,order),user,new Cancellation());return Map.of("operation",page.totalElements()==ds.totalElements());}
   case "PERMISSION_CHANGE" -> {UserContext revoked=new UserContext(){public String subjectId(){return user.subjectId();}public Set<String> roles(){return Set.of("REVOKED");}public PolicyBinding policyBinding(){return new PolicyBinding(){public String roleKey(){return "REVOKED";}public Map<String,Object> scope(){return Map.of();}};}};return Map.of("actionSafety",denied(()->pageService.page(executionId,ds.id(),new PageRequest(0,10,null),revoked,new Cancellation())));}
   case "SNAPSHOT_CHANGE" -> {var p=ds.plan();var stale=new ExecutionPlan(p.formatVersion(),p.registryVersion()+"-stale",p.policyVersion(),p.owner(),p.effectiveScope(),p.asOf(),p.snapshotAt(),p.query(),p.joins(),p.rowFilters(),p.uniqueKeys(),p.timeoutSeconds());return Map.of("operation",denied(()->executor.execute(stale,user,new Cancellation())));}
   case "RESTART" -> {var reloaded=work.execution(executionId,user.subjectId()).orElseThrow();var page=new PageService(work,policies,executor).page(executionId,ds.id(),new PageRequest(0,10,null),user,new Cancellation());return Map.of("storageReload",ContractJson.canonical(saved).equals(ContractJson.canonical(reloaded))&&page.totalElements()==ds.totalElements());}
   case "SSE_RECONNECT","DUPLICATE_REQUEST" -> {String key=UUID.randomUUID().toString();var first=requests.accept(saved.conversationId(),user.subjectId(),key,"0".repeat(64),saved.asOf(),saved.snapshotVersion());requests.append(first.request().id(),user.subjectId(),"completed",Map.of("executionId",executionId.toString()));var events=requests.events(first.request().id(),user.subjectId(),0);var duplicate=requests.accept(saved.conversationId(),user.subjectId(),key,"0".repeat(64),saved.asOf(),saved.snapshotVersion());boolean safe=first.created()&&!duplicate.created()&&first.request().id().equals(duplicate.request().id())&&!events.isEmpty()&&requests.events(first.request().id(),user.subjectId(),events.getLast().id()).isEmpty();return Map.of("operation",safe);}
   case "CSV_BEFORE_APPROVAL","APPROVAL_REPLAY","ARTIFACT_EXPIRY" -> {
    var card=saved.view().components().stream().filter(c->c.events().stream().anyMatch(e->e.actionId().equals("requestCsv"))).findFirst().orElseThrow(()->new EngineException(EngineErrorCode.VALIDATION_ERROR,"Evaluation CSV component missing"));
    var target=saved.datasets().stream().filter(d->d.id().equals(card.datasetId())).findFirst().orElseThrow();String key=UUID.randomUUID().toString();
    if(!actionAllowed)return Map.of("actionSafety",denied(()->approvals.request(executionId,target.id(),card.id(),target.columns().stream().map(TypedDataset.Column::alias).toList(),key,user)));
    var approval=approvals.request(executionId,target.id(),card.id(),target.columns().stream().map(TypedDataset.Column::alias).toList(),key,user);
    boolean before=artifacts.forApproval(approval.id(),user.subjectId()).isEmpty();if(operation.equals("CSV_BEFORE_APPROVAL"))return Map.of("humanConfirm",before&&approval.state()==ApprovalStore.State.PENDING);
    var accepted=approvals.decide(approval.id(),true,approval.hash(),user);files.start(approval.id(),user);var repeated=approvals.decide(approval.id(),true,approval.hash(),user);files.start(approval.id(),user);
    ArtifactStore.Artifact artifact=null;long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();do{artifact=files.forApproval(approval.id(),user).orElse(null);if(artifact!=null&&artifact.state()==ArtifactStore.State.READY)break;if(artifact!=null&&artifact.state()==ArtifactStore.State.FAILED)break;try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new EngineException(EngineErrorCode.CANCELED,"Canceled");}}while(System.nanoTime()<deadline);
    if(artifact==null||artifact.state()!=ArtifactStore.State.READY)throw new IllegalStateException("Evaluation export failed: "+(artifact==null?"MISSING":artifact.errorCode()));
    if(operation.equals("APPROVAL_REPLAY"))return Map.of("humanConfirm",before,"operation",accepted.id().equals(repeated.id())&&artifact.rows()==target.totalElements());
    final var produced=artifact;var future=Clock.fixed(artifact.expiresAt(),ZoneOffset.UTC);var scopedStore=new ArtifactStore(){public Optional<Artifact>find(UUID id,String owner){return artifacts.find(id,owner);}public Optional<Artifact>forApproval(UUID id,String owner){return artifacts.forApproval(id,owner);}public boolean retain(UUID id,Instant now){return true;}public boolean claim(UUID id,String owner){throw new UnsupportedOperationException();}public void finish(UUID id,String owner,State state,long rows,Instant expiry,String code){throw new UnsupportedOperationException();}public void interruptActive(){}public List<Artifact>expire(Instant now){return List.of();}};
    try{var directory=java.nio.file.Files.createTempDirectory("evaluation-expiry-");try(var expired=new ArtifactService(scopedStore,approvals,users,(p,u,c,sink)->{throw new AssertionError("Expiry probe must never export");},directory,future)){return Map.of("operation",denied(()->{try(var stream=expired.download(produced.id(),user)){throw new AssertionError("Expired file opened");}catch(java.io.IOException e){throw new IllegalStateException(e);}}));}finally{java.nio.file.Files.delete(directory);}}catch(java.io.IOException e){throw new IllegalStateException("Expiry probe failed",e);}
   }
   default -> throw new EngineException(EngineErrorCode.VALIDATION_ERROR,"Unknown evaluation operation");
  }
 }
 private static boolean denied(Runnable action){try{action.run();return false;}catch(EngineException e){return Set.of(EngineErrorCode.SCOPE_DENIED,EngineErrorCode.PII_DENIED,EngineErrorCode.IR_UNSUPPORTED,EngineErrorCode.IR_INVALID).contains(e.code());}}
}
