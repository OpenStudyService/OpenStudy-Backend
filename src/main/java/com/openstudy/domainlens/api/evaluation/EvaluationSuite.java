package com.openstudy.domainlens.api.evaluation;
import java.util.*;
import com.openstudy.domainlens.api.work.Cancellation;
/** Consumer owns held-out questions, identities, oracle and comparison rules. */
public interface EvaluationSuite {
 String dataset(); List<String> caseIds(); Map<String,String> versions();
 EvaluationRun.CaseResult execute(String caseId,EvaluationRun.Mode mode,Cancellation cancellation);
}
