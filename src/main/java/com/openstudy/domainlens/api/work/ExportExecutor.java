package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.spi.UserContext;
import java.util.Map;
import java.util.function.Consumer;
/** Streams all rows of one approved dataset in a single read-only snapshot. */
@FunctionalInterface public interface ExportExecutor {
 void stream(ExecutionPlan plan,UserContext user,Cancellation cancellation,Consumer<Map<String,Object>> row);
}
