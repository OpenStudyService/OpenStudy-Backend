package com.openstudy.domainlens.api.evaluation;
import com.openstudy.domainlens.api.spi.UserContext;
import java.util.*;
/** Opt-in isolated evaluation probes. Never a client-facing action execution bypass. */
@FunctionalInterface public interface EvaluationScenarioVerifier {
 Map<String,Boolean> verify(String operation,UUID executionId,UserContext user,boolean actionAllowed);
}
