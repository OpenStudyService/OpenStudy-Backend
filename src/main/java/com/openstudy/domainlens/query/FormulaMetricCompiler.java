package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.formula.*;
import com.openstudy.domainlens.catalog.*;
import jakarta.persistence.criteria.*;
import java.util.*;

/** Common scalar/aggregate operator implementation for ordinary Criteria queries. */
final class FormulaMetricCompiler {
    private FormulaMetricCompiler() {}
    @SuppressWarnings({"rawtypes","unchecked"})
    static Expression<?> compile(OperationIr e,CriteriaBuilder cb,SemanticRegistry registry,Map<String,From<?,?>> from){
        if(e.op()==OperationIr.Op.FIELD){var field=registry.fields().get(e.ref());Path<?> p=from.get(field.source());for(String part:field.path().split("\\."))p=p.get(part);return p;}
        if(e.op()==OperationIr.Op.LITERAL)return cb.literal(switch(e.type()){case DECIMAL->new java.math.BigDecimal(e.value().toString());case INTEGER->new java.math.BigDecimal(e.value().toString()).longValueExact();default->e.value();});
        var a=e.args().stream().map(x->compile(x,cb,registry,from)).toList();
        return switch(e.op()){
            case COUNT->cb.count(a.getFirst());case COUNT_DISTINCT->cb.countDistinct(a.getFirst());
            case SUM->cb.sum((Expression)a.getFirst());case AVG->cb.avg((Expression)a.getFirst());case MIN->cb.least((Expression)a.getFirst());case MAX->cb.greatest((Expression)a.getFirst());
            case ADD->cb.sum((Expression)a.get(0),(Expression)a.get(1));case SUBTRACT->cb.diff((Expression)a.get(0),(Expression)a.get(1));case MULTIPLY->cb.prod((Expression)a.get(0),(Expression)a.get(1));case DIVIDE->cb.quot(a.get(0).as(java.math.BigDecimal.class),cb.nullif(a.get(1).as(java.math.BigDecimal.class),java.math.BigDecimal.ZERO));
            case COALESCE->cb.coalesce((Expression)a.get(0),(Expression)a.get(1));
            case EQ->cb.equal(a.get(0),a.get(1));case NE->cb.notEqual(a.get(0),a.get(1));case GT->cb.greaterThan((Expression)a.get(0),(Expression)a.get(1));case GTE->cb.greaterThanOrEqualTo((Expression)a.get(0),(Expression)a.get(1));case LT->cb.lessThan((Expression)a.get(0),(Expression)a.get(1));case LTE->cb.lessThanOrEqualTo((Expression)a.get(0),(Expression)a.get(1));
            case IS_NULL->cb.isNull(a.getFirst());case NOT->cb.not((Expression<Boolean>)a.getFirst());
            case AND->cb.and(a.stream().map(x->(Predicate)x).toArray(Predicate[]::new));case OR->cb.or(a.stream().map(x->(Predicate)x).toArray(Predicate[]::new));
            case CASE->{var choice=cb.selectCase();for(int i=0;i<a.size()-1;i+=2)choice.when((Expression<Boolean>)a.get(i),a.get(i+1));yield choice.otherwise(a.getLast());}
            default->throw new IllegalArgumentException("Operation unavailable in metric context");
        };
    }
}
