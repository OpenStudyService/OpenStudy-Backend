package com.openstudy.domainlens.api.work;

import com.openstudy.domainlens.api.ir.*;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Persistence port. Owner arguments are supplied by authenticated application services. No result rows are persisted. */
public interface WorkStore extends PlanStore {
    record Conversation(UUID id,String owner,Instant createdAt) {}
    record Message(UUID id,UUID conversationId,String role,String text,Instant createdAt) {}
    record Dataset(String id,ExecutionPlan plan,List<TypedDataset.Column> columns,long totalElements) {
        public Dataset { columns=List.copyOf(columns); }
    }
    record Execution(int formatVersion,UUID id,UUID conversationId,String owner,IrDocument ir,
            List<Dataset> datasets,@Nullable ViewSpec view,List<String> warnings,String finalStatus,
            Instant asOf,String snapshotVersion,String policyVersion,Instant completedAt,List<com.openstudy.domainlens.api.prompt.ModelUsage> usage) {
        public Execution(int formatVersion,UUID id,UUID conversationId,String owner,IrDocument ir,List<Dataset> datasets,@Nullable ViewSpec view,List<String> warnings,String finalStatus,Instant asOf,String snapshotVersion,String policyVersion,Instant completedAt){this(formatVersion,id,conversationId,owner,ir,datasets,view,warnings,finalStatus,asOf,snapshotVersion,policyVersion,completedAt,List.of());}
        public Execution {datasets=List.copyOf(datasets);warnings=List.copyOf(warnings);usage=usage==null?List.of():List.copyOf(usage);}
    }
    Conversation create(String owner,Instant at);
    Optional<Conversation> conversation(UUID id,String owner);
    void save(Execution execution,List<Message> messages,String owner);
    Optional<Execution> execution(UUID id,String owner);
    List<Message> messages(UUID conversationId,String owner);
}
