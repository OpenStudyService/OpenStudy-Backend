package com.openstudy.domainlens.api.evaluation;
import java.util.*;
public interface EvaluationStore {
 Optional<EvaluationRun> byKey(String owner,String key);
 EvaluationRun create(EvaluationRun run,String key);
 void save(EvaluationRun run); Optional<EvaluationRun> find(UUID id); List<EvaluationRun> list(int page,int size);
 void interruptActive();
}
