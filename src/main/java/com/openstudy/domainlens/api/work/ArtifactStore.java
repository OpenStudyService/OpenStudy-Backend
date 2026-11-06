package com.openstudy.domainlens.api.work;
import java.time.Instant;
import java.util.*;
public interface ArtifactStore {
 enum State { QUEUED, GENERATING, READY, FAILED, EXPIRED }
 record Artifact(UUID id,UUID approvalId,UUID executionId,String owner,String name,State state,long rows,Instant createdAt,Instant expiresAt,String errorCode) {}
 Optional<Artifact> find(UUID id,String owner);
 Optional<Artifact> forApproval(UUID approvalId,String owner);
 boolean retain(UUID id,Instant now);
 boolean claim(UUID id,String owner);
 void finish(UUID id,String owner,State state,long rows,Instant expiresAt,String code);
 void interruptActive();
 List<Artifact> expire(Instant now);
}
