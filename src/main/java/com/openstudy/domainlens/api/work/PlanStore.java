package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.ir.TypedDataset;
import java.util.*;
/** Owner is supplied by authenticated server context. Unknown/other-owner records are indistinguishable. */
public interface PlanStore {
    Optional<StoredDataset> find(UUID executionId,String datasetId,String owner);
    record StoredDataset(UUID executionId,ExecutionPlan plan,List<TypedDataset.Column> columns) {
        public StoredDataset {columns=List.copyOf(columns);}
    }
}
