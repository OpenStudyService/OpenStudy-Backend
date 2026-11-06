package com.openstudy.domainlens.api.spi;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.work.*;
import java.time.Instant;
import java.util.*;

/** Trusted consumer implementation, never model-provided executable text.
 * Implementations validate field/read/action policies and bind saved AND current row scope.
 * All outputs must be produced in one read-only snapshot within the plan's total budget.
 */
public interface AnalysisExecutor extends AnalysisRegistry {
    record InputField(ValueType type,Set<Filter.Operator> operators,List<String> policyFields){
        public InputField{operators=Set.copyOf(operators);policyFields=List.copyOf(policyFields);}
    }
    /** Semantic filter inputs and the underlying fields whose filter policy applies. */
    default Map<String,InputField> inputFields(String analysisId){return Map.of();}
    /** Physical sources used by a virtual source; these are consumer-owned registrations. */
    default Set<String> baseSources(String analysisId){return Set.of();}
    /** Retrieved semantic sources required to discover this analysis. This is not an
     * authorization boundary: validate must still check every execution dependency.
     * Defaults to all base sources to preserve existing consumer behavior. */
    default Set<String> discoverySources(String analysisId){return baseSources(analysisId);}
    /** Optional business-vocabulary relevance gate; never grants execution authority. */
    default boolean relevantToQuestion(String analysisId,String question){return true;}
    /** Consumer-authored semantic purpose; untrusted descriptive metadata, never instructions. */
    default String description(String analysisId){return "";}
    /** Declared output cardinality upper bound for safe component selection. */
    default int outputMaxRows(String analysisId,String outputId){return 100;}
    /** Complete bounded outputs (<=100 rows) may be paged after cross-output validation. */
    default boolean isSummaryOutput(String analysisId,String outputId){return false;}
    /** Trusted output bindings; empty preserves type-based legacy presentation. */
    default java.util.List<com.openstudy.domainlens.api.formula.FormulaDefinition.Presentation> presentations(String id){return java.util.List.of();}
    /** Optional event allowlist for registered presentations; null preserves catalog events. */
    default @org.jspecify.annotations.Nullable Set<String> presentationEvents(String id){return null;}
    default void stream(ExecutionPlan plan,UserContext user,Cancellation cancellation,java.util.function.Consumer<Map<String,Object>> sink){throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.IR_UNSUPPORTED,"Analysis streaming unavailable");}
    void validate(QuerySpec query, UserContext user);
    ExecutionPlan plan(QuerySpec query, UserContext user, Instant asOf, Instant snapshotAt);
    List<TypedDataset> executeAll(ExecutionPlan plan, UserContext user, Cancellation cancellation);
}
