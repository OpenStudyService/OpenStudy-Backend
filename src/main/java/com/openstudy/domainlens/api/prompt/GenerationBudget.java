package com.openstudy.domainlens.api.prompt;
import com.openstudy.domainlens.api.error.*;
import java.time.Duration;
import java.util.*;
/** One request-wide deadline and budget shared by transient retry and semantic repair. */
public final class GenerationBudget {
 private final int maxCalls,maxBytes,maxInput,maxOutput;
 private final long deadline;private int calls,bytes,input,output;private boolean retried;
 private final List<ModelUsage> usages=new ArrayList<>();
 public GenerationBudget(Duration timeout){this(timeout,3,196608,32768,12288);}
 /** Explicit candidate policy: initial plan plus bounded corrections; one transient transport retry. */
 public static GenerationBudget planning(Duration perCall,int attempts){
  if(attempts<1 || attempts>10)throw new IllegalArgumentException("Planning attempts must be 1..10");
  if(attempts==1)return new GenerationBudget(perCall,1,65536,32768,8192);
  return attempts==2?new GenerationBudget(perCall):new GenerationBudget(perCall.multipliedBy(attempts),attempts+1,65536*(attempts+1),32768*attempts,8192*attempts);
 }
 private GenerationBudget(Duration timeout,int calls,int bytes,int input,int output){if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("Positive deadline required");deadline=System.nanoTime()+timeout.toNanos();maxCalls=calls;maxBytes=bytes;maxInput=input;maxOutput=output;}
 public int completionAllowance(int configured){if(configured<1 || configured>8192)throw new IllegalArgumentException("Completion limit must be 1..8192");int remaining=maxOutput-output;if(remaining<1)throw exceeded();return Math.min(configured,remaining);}
 public boolean expired(){return deadline-System.nanoTime()<=0;}
 public Duration remaining(){long nanos=deadline-System.nanoTime();if(nanos<=0)throw new EngineException(EngineErrorCode.MODEL_TIMEOUT,"Model deadline exceeded");return Duration.ofNanos(nanos);}
 public void beforeCall(int requestBytes){remaining();if(requestBytes<0 || calls>=maxCalls || (long)bytes+requestBytes>maxBytes || input>=maxInput || output>=maxOutput)throw exceeded();calls++;bytes+=requestBytes;}
 public boolean retry(){if(retried || calls>=maxCalls || remaining().toMillis()<=250)return false;retried=true;return true;}
 public void record(String model,Integer in,Integer out,String status){
  usages.add(new ModelUsage(model,in,out,calls,maxInput,maxOutput,"unpriced-v1",status));
  input+=in==null?0:Math.max(0,in);output+=out==null?0:Math.max(0,out);
  if(input>maxInput || output>maxOutput)throw exceeded();
 }
 public List<ModelUsage> usages(){return List.copyOf(usages);}
 private static EngineException exceeded(){return new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Generation budget exceeded");}
}
