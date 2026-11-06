package com.openstudy.domainlens.api.work;
import java.time.Instant;
import java.util.*;
/** References only; never copies result rows or source messages. */
public interface WorkRecordStore {
    record Link(UUID requestId,UUID conversationId,UUID messageId,UUID assistantMessageId,UUID executionId,String state,String reason,String permissionHash,Instant at) {}
    record Record(UUID id,String owner,String title,Instant createdAt,List<Link> links) {}
    List<Record> list(String owner);
    Optional<Record> find(UUID id,String owner);
}
