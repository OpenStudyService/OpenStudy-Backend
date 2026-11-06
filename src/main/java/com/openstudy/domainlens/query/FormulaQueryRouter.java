package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.*;
import java.util.List;
/** Stored executions choose their original engine by provenance, never the latest runtime default. */
public final class FormulaQueryRouter implements QueryExecutor {
    private final QueryExecutor legacy,formulas;
    public FormulaQueryRouter(QueryExecutor legacy,QueryExecutor formulas){this.legacy=legacy;this.formulas=formulas;}
    private QueryExecutor runtime(ExecutionPlan p){if(p.formulas().isEmpty())return legacy;if(formulas==null)throw new com.openstudy.domainlens.api.error.EngineException(com.openstudy.domainlens.api.error.EngineErrorCode.IR_UNSUPPORTED,"Stored formula runtime unavailable");return formulas;}
    public TypedDataset execute(ExecutionPlan p,UserContext u,Cancellation c){return runtime(p).execute(p,u,c);}
    public List<TypedDataset> executeAll(ExecutionPlan p,UserContext u,Cancellation c){return runtime(p).executeAll(p,u,c);}
}
