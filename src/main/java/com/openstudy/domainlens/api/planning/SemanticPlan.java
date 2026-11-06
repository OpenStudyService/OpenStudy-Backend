package com.openstudy.domainlens.api.planning;

import com.openstudy.domainlens.api.ir.*;
import java.util.*;

/** Model-owned meaning only; executable paths, aliases and UI wiring are server-owned. */
public record SemanticPlan(String version, IrStatus status, String reason, List<ClarificationOption> options,
        List<Request> requests, List<String> unresolved, ReasonCode reasonCode) {
    public static final String VERSION="semantic-plan-2";
    public static final String FORMULA_VERSION="semantic-formula-plan-2", MIXED_VERSION="semantic-mixed-plan-1";
    public SemanticPlan { options=List.copyOf(options); requests=List.copyOf(requests); unresolved=List.copyOf(unresolved); }
    public SemanticPlan(String version,IrStatus status,String reason,List<ClarificationOption> options,List<Request> requests,List<String> unresolved) {
        this(version,status,reason,options,requests,unresolved,(VERSION.equals(version)||FORMULA_VERSION.equals(version)||MIXED_VERSION.equals(version))?ReasonCode.defaultFor(status):null);
    }
    public void validateReason() {
        boolean current=VERSION.equals(version)||FORMULA_VERSION.equals(version)||MIXED_VERSION.equals(version);
        ContractRules.require(status!=null,"Missing plan status");
        if(status==IrStatus.READY)ContractRules.require(reasonCode==null,"READY has failure code");
        else ContractRules.require((!current && reasonCode==null) || reasonCode!=null && reasonCode.permits(status),"Invalid plan reason code/status");
    }
    public record Request(QuerySpec.Mode kind, String source, String analysisId, List<String> metrics,
            List<String> dimensions, Filter filter, List<Sort> order, Integer page, Integer size,
            Integer limit, boolean total, List<String> presentations, List<Evidence> evidence, List<com.openstudy.domainlens.api.formula.FormulaDefinition.Ref> formulas) {
        public Request { metrics=List.copyOf(metrics); dimensions=List.copyOf(dimensions); order=List.copyOf(order);
            presentations=List.copyOf(presentations); evidence=List.copyOf(evidence);formulas=formulas==null?List.of():List.copyOf(formulas); }
        public Request(QuerySpec.Mode kind,String source,String analysisId,List<String> metrics,List<String> dimensions,Filter filter,List<Sort> order,Integer page,Integer size,Integer limit,boolean total,List<String> presentations,List<Evidence> evidence){this(kind,source,analysisId,metrics,dimensions,filter,order,page,size,limit,total,presentations,evidence,List.of());}
    }
    public record Sort(String field, QuerySpec.Direction direction) {}
    public enum Origin { QUESTION, PREVIOUS, CONTRACT }
    /** slot is a semantic ID or filter, order, page, size, limit, total, presentation. */
    public record Evidence(String slot, Origin origin, String quote) {}
}
