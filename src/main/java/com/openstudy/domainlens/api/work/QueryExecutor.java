package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.ir.TypedDataset;
public interface QueryExecutor {
    TypedDataset execute(ExecutionPlan plan,UserContext user,Cancellation cancellation);
    default java.util.List<TypedDataset> executeAll(ExecutionPlan plan,UserContext user,Cancellation cancellation) {
        return java.util.List.of(execute(plan,user,cancellation));
    }
}
