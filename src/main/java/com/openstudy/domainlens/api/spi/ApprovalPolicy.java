package com.openstudy.domainlens.api.spi;
import com.openstudy.domainlens.api.ir.*;
import java.util.List;
import com.openstudy.domainlens.api.work.ExecutionPlan;
/** Consumer-owned CSV field/action policy. Returns its current version after authorization. */
@FunctionalInterface
public interface ApprovalPolicy {
    String authorize(ExecutionPlan plan,List<TypedDataset.Column> columns,UserContext user,boolean decision);
}
