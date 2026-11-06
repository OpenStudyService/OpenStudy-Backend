package com.openstudy.domainlens.api.work;
import java.util.*;
import java.time.Instant;
public interface TraceStore {
 record Span(UUID id,String name,String state,Instant startedAt,Long durationNanos,String errorCode,Map<String,String> attributes) {}
 record Trace(UUID requestId,String owner,String state,Instant createdAt,List<Span> spans) {}
 void begin(UUID request,String owner,Instant at);
 void span(UUID request,Span span);
 void finish(UUID request,String state);
 void recover();
 void purge(Instant before);
 List<Trace> list(int page,int size,Instant since);
 Optional<Trace> find(UUID id,Instant since);
}
