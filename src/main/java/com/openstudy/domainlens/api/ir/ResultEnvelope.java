package com.openstudy.domainlens.api.ir;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Server-owned result; entities and authority from model output never enter this envelope. */
public record ResultEnvelope(String requestId, @Nullable String executionId, String status,
        List<TypedDataset> datasets, @Nullable ViewSpec view, List<String> warnings,
        @Nullable String errorCode, @Nullable Instant asOf, @Nullable String snapshotVersion,
        @Nullable String policyVersion,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Binding> formulaBindings,List<com.openstudy.domainlens.api.formula.FormulaDefinition.DisplayValues> displayValues, @Nullable OutcomeNotice notice) {
    public ResultEnvelope { datasets=List.copyOf(datasets); warnings=List.copyOf(warnings);formulaBindings=formulaBindings==null?List.of():List.copyOf(formulaBindings);displayValues=displayValues==null?List.of():List.copyOf(displayValues); }
    public ResultEnvelope withNotice(OutcomeNotice value){return new ResultEnvelope(requestId,executionId,status,datasets,view,warnings,errorCode,asOf,snapshotVersion,policyVersion,formulaBindings,displayValues,value);}
    public ResultEnvelope(String requestId,String executionId,String status,List<TypedDataset> datasets,ViewSpec view,List<String> warnings,String errorCode,Instant asOf,String snapshotVersion,String policyVersion,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Binding> formulaBindings,List<com.openstudy.domainlens.api.formula.FormulaDefinition.DisplayValues> displayValues){this(requestId,executionId,status,datasets,view,warnings,errorCode,asOf,snapshotVersion,policyVersion,formulaBindings,displayValues,null);}
    public ResultEnvelope(String requestId,String executionId,String status,List<TypedDataset> datasets,ViewSpec view,List<String> warnings,String errorCode,Instant asOf,String snapshotVersion,String policyVersion,List<com.openstudy.domainlens.api.formula.FormulaDefinition.Binding> formulaBindings){this(requestId,executionId,status,datasets,view,warnings,errorCode,asOf,snapshotVersion,policyVersion,formulaBindings,List.of());}
    public ResultEnvelope(String requestId,String executionId,String status,List<TypedDataset> datasets,ViewSpec view,List<String> warnings,String errorCode,Instant asOf,String snapshotVersion,String policyVersion){this(requestId,executionId,status,datasets,view,warnings,errorCode,asOf,snapshotVersion,policyVersion,List.of(),List.of());}
}
