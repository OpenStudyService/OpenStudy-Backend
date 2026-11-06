package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.ir.*;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
public interface ApprovalStore {
    enum State { PENDING, APPROVED, DENIED, EXPIRED }
    record Approval(UUID id,String owner,String key,UUID executionId,String datasetId,String componentId,
                    ExecutionPlan plan,List<TypedDataset.Column> columns,long expectedRows,String hash,
                    String policyVersion,State state,Instant createdAt,Instant expiresAt) {
        public Approval { columns=List.copyOf(columns); }
        public Approval state(State next){return new Approval(id,owner,key,executionId,datasetId,componentId,plan,columns,expectedRows,hash,policyVersion,next,createdAt,expiresAt);}
    }
    Approval create(Approval approval);
    Optional<Approval> find(UUID id,String owner);
    List<Approval> list(String owner);
    Approval update(UUID id,String owner,UnaryOperator<Approval> transition);
}
