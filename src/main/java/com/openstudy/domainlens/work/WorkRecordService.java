package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.security.Identity;
import java.time.Instant;
import java.util.*;
public final class WorkRecordService {
 public record Detail(UUID id,String title,String status,String reason,Instant createdAt,List<WorkRecordStore.Link> links,List<WorkStore.Message> messages,List<WorkStore.Execution> executions) {}
 public record Page(List<Detail> items,int page,int size,long total) {}
 private final WorkRecordStore records;private final WorkStore work;private final WorkService service;
 public WorkRecordService(WorkRecordStore records,WorkStore work,WorkService service){this.records=records;this.work=work;this.service=service;}
 public Detail get(UUID id,UserContext user){return visible(records.find(id,Identity.require(user).subjectId()).orElseThrow(()->new EngineException(EngineErrorCode.NOT_FOUND,"Work record not found")),user);}
 public Page list(String search,String status,int page,int size,UserContext user){
  if(page<0||size<1||size>100||search.length()>200||!Set.of("ALL","IN_PROGRESS","NEEDS_ATTENTION","COMPLETED").contains(status))throw new EngineException(EngineErrorCode.VALIDATION_ERROR,"Invalid work record filter");
  var all=records.list(Identity.require(user).subjectId()).stream().map(r->visible(r,user)).filter(r->status.equals("ALL")||status.equals(r.status())).filter(r->search.isBlank()||r.reason()==null&&r.title().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();
  int from=(int)Math.min((long)page*size,all.size());return new Page(all.subList(from,Math.min(from+size,all.size())),page,size,all.size());
 }
 private Detail visible(WorkRecordStore.Record record,UserContext user){
  var executions=new ArrayList<WorkStore.Execution>();
  String permissionHash=com.openstudy.domainlens.catalog.SearchText.hash(com.openstudy.domainlens.api.ir.ContractJson.canonical(Identity.require(user)));
  if(record.links().stream().anyMatch(l->!permissionHash.equals(l.permissionHash())))return new Detail(record.id(),"접근할 수 없는 업무","NEEDS_ATTENTION","ACCESS_REVOKED",record.createdAt(),List.of(),List.of(),List.of());
  for(var link:record.links())if(link.executionId()!=null){
   var restored=service.restore(link.executionId(),user);
   if(restored.execution()==null)return new Detail(record.id(),"접근할 수 없는 업무","NEEDS_ATTENTION",restored.status(),record.createdAt(),List.of(),List.of(),List.of());
   executions.add(restored.execution());
  }
  var ids=executions.stream().map(WorkStore.Execution::id).collect(java.util.stream.Collectors.toSet());
  String status=record.links().stream().anyMatch(l->!RequestStore.terminal(l.state()))?"IN_PROGRESS":record.links().stream().anyMatch(l->Set.of("failed","clarification").contains(l.state()))?"NEEDS_ATTENTION":"COMPLETED";
  var messageIds=new HashSet<UUID>();record.links().forEach(l->{messageIds.add(l.messageId());messageIds.add(l.assistantMessageId());});
  var messages=record.links().stream().map(WorkRecordStore.Link::conversationId).distinct().flatMap(c->work.messages(c,user.subjectId()).stream()).filter(m->messageIds.contains(m.id())).toList();
  return new Detail(record.id(),record.title(),status,null,record.createdAt(),record.links(),messages,List.copyOf(executions));
 }
}
