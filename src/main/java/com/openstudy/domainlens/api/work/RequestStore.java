package com.openstudy.domainlens.api.work;

import java.time.Instant;
import java.util.*;

/** Durable request journal. Replay reads events only and never repeats work. */
public interface RequestStore {
    record Request(UUID id, UUID conversationId, String owner, String key, String inputHash,
                   Instant asOf, String snapshotVersion, String state) {}
    record Accepted(Request request, boolean created) {}
    record Event(long id, String type, Map<String,Object> data, Instant at) {
        public Event { data=Map.copyOf(data); }
    }
    record Context(String question,UUID referenceExecutionId,UUID referenceRequestId,String permissionHash) {}
    default Accepted accept(UUID conversation,String owner,String key,String hash,Instant asOf,String version,Context context) { return accept(conversation,owner,key,hash,asOf,version); }
    Accepted accept(UUID conversation, String owner, String key, String inputHash, Instant asOf, String snapshotVersion);
    Optional<Request> find(UUID id, String owner);
    default Optional<Request> forExecution(UUID execution,String owner){return Optional.empty();}
    List<Event> events(UUID id, String owner, long after);
    /** Appends under a row lock; returns false once a terminal state won the race. */
    boolean append(UUID id, String owner, String type, Map<String,Object> data);
    /** Fail interrupted requests on startup; never enqueue them again. */
    int interruptActive();
    static boolean terminal(String state) { return Set.of("completed","clarification","failed","canceled").contains(state); }
}
