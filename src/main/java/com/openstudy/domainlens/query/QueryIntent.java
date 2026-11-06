package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.prompt.PreviousReferenceResolver;
import java.util.*;

/** Conservative consumer interpretation; unknown slots stay empty, never guessed from retrieval scores. */
public record QueryIntent(Set<String> metrics,Set<String> dimensions,Filter period,
        QuerySpec.Direction order,Set<String> analyses,IrStatus status,String reason,ReasonCode reasonCode,boolean total,String orderField) {
    public QueryIntent {metrics=Set.copyOf(metrics);dimensions=Set.copyOf(dimensions);analyses=Set.copyOf(analyses);}
    public QueryIntent(Set<String> metrics,Set<String> dimensions,Filter period,QuerySpec.Direction order,Set<String> analyses,IrStatus status,String reason,ReasonCode reasonCode,boolean total){this(metrics,dimensions,period,order,analyses,status,reason,reasonCode,total,null);}
    public QueryIntent(Set<String> metrics,Set<String> dimensions,Filter period,QuerySpec.Direction order,Set<String> analyses,IrStatus status,String reason,ReasonCode reasonCode){this(metrics,dimensions,period,order,analyses,status,reason,reasonCode,false);}
    public QueryIntent(Set<String> metrics,Set<String> dimensions,Filter period,QuerySpec.Direction order,Set<String> analyses,IrStatus status,String reason){this(metrics,dimensions,period,order,analyses,status,reason,ReasonCode.defaultFor(status));}
    public static QueryIntent stop(IrStatus status,ReasonCode code,String reason){return new QueryIntent(Set.of(),Set.of(),null,null,Set.of(),status,reason,code);}
    public static QueryIntent unknown(){return new QueryIntent(Set.of(),Set.of(),null,null,Set.of(),IrStatus.READY,null);}
    public static QueryIntent stop(IrStatus status,String reason){return new QueryIntent(Set.of(),Set.of(),null,null,Set.of(),status,reason);}
    public QueryIntent withTotal(boolean value){return new QueryIntent(metrics,dimensions,period,order,analyses,status,reason,reasonCode,value,orderField);}
    public QueryIntent withOrderField(String field){return new QueryIntent(metrics,dimensions,period,order,analyses,status,reason,reasonCode,total,field);}
    @FunctionalInterface public interface Analyzer {
        QueryIntent analyze(String sanitizedQuestion,PreviousReferenceResolver.Conditions previous);
        Analyzer NONE=(question,previous)->unknown();
    }
}
