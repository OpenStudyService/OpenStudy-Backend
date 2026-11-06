package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.ir.*;
import java.util.*;
import java.time.Instant;
/** Versioned server-owned plan. Requests may select paging/sort aliases, never physical paths. */
public record ExecutionPlan(int formatVersion,String registryVersion,String policyVersion,String owner,
        Map<String,Object> effectiveScope,Instant asOf,Instant snapshotAt,QuerySpec query,
        List<Join> joins,Map<String,Filter> rowFilters,List<String> uniqueKeys,int timeoutSeconds,
        List<com.openstudy.domainlens.api.formula.FormulaDefinition.Ref> formulas) {
    public ExecutionPlan {effectiveScope=Map.copyOf(effectiveScope);joins=List.copyOf(joins);rowFilters=Map.copyOf(rowFilters);uniqueKeys=List.copyOf(uniqueKeys);formulas=formulas==null?List.of():List.copyOf(formulas);}
    public ExecutionPlan(int formatVersion,String registryVersion,String policyVersion,String owner,Map<String,Object> effectiveScope,Instant asOf,Instant snapshotAt,QuerySpec query,List<Join> joins,Map<String,Filter> rowFilters,List<String> uniqueKeys,int timeoutSeconds){this(formatVersion,registryVersion,policyVersion,owner,effectiveScope,asOf,snapshotAt,query,joins,rowFilters,uniqueKeys,timeoutSeconds,List.of());}
    public ExecutionPlan withFormulas(List<com.openstudy.domainlens.api.formula.FormulaDefinition.Ref> refs){return new ExecutionPlan(formatVersion,registryVersion,policyVersion,owner,effectiveScope,asOf,snapshotAt,query,joins,rowFilters,uniqueKeys,timeoutSeconds,refs);}
    public static final int VERSION=1;
    public enum JoinType { INNER, LEFT }
    public record Join(String relationId,String source,String target,String path,JoinType type,boolean many) {}
}
