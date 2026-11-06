package com.openstudy.domainlens.work;

import com.openstudy.domainlens.api.evaluation.*;
import com.openstudy.domainlens.api.work.Cancellation;
import java.util.*;

/** Internal, synchronous runner. The journal owns durable evidence and candidate validation.
 * No database execution IDs survive a resume: suite.execute reconstructs follow-up prerequisites.
 * A completed failure is just as final as a completed success within an official round.
 */
public final class CheckpointEvaluationRunner {
 public record Item(String caseId, EvaluationRun.Mode mode) {}
 public interface Journal {
  boolean completed(Item item);
  void started(Item item);
  void completed(Item item, EvaluationRun.CaseResult result);
 }
 public static void execute(EvaluationSuite suite, List<Item> items, Journal journal, Cancellation cancellation) {
  if(new HashSet<>(items).size()!=items.size())throw new IllegalArgumentException("Duplicate evaluation item");
  var versions=suite.versions();
  for(var item:items){
   if(!suite.caseIds().contains(item.caseId()))throw new IllegalArgumentException("Unknown evaluation item");
   if(journal.completed(item))continue;
   cancellation.check();
   if(!versions.equals(suite.versions()))throw new IllegalStateException("Evaluation candidate changed");
   journal.started(item);
   var result=suite.execute(item.caseId(),item.mode(),cancellation);
   if(!result.caseId().equals(item.caseId())||result.mode()!=item.mode())throw new IllegalStateException("Mismatched evaluation result");
   journal.completed(item,result);
  }
 }
 private CheckpointEvaluationRunner() {}
}
