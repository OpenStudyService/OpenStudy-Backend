package com.openstudy.domainlens.api.formula;

import com.openstudy.domainlens.api.ir.*;
import java.util.*;

/** Consumer-authored relational expression tree. No executable text is accepted. */
public record OperationIr(Op op, List<OperationIr> args, String scope, String ref,
        Object value, ValueType type, String unit, Select query) {
    public enum Op { FIELD, COLUMN, ENTITY, RELATION, LITERAL, AS_OF, INPUT_FILTER, INPUT_FILTER_BOUND,
        SUM, COUNT, COUNT_DISTINCT, MIN, MAX, AVG, ADD, SUBTRACT, MULTIPLY, DIVIDE,
        EQ, NE, GT, GTE, LT, LTE, AND, OR, NOT, IS_NULL, COALESCE, CASE, EXISTS, ADD_DAYS, CAST_STRING }
    public OperationIr { args=args==null?List.of():List.copyOf(args);Objects.requireNonNull(op); }
    public record Named(String alias,OperationIr expression) {}
    /** relation refers to a semantic relation; source is used only for an explicit entity join. */
    public record Join(String alias,String from,String relation,String source,boolean left,OperationIr on) {}
    public record Ordering(OperationIr expression,QuerySpec.Direction direction) {}
    public record Select(String source,String alias,List<Join> joins,List<Named> columns,
            OperationIr where,List<OperationIr> group,List<Ordering> order,Map<String,OperationIr> inputs) {
        public Select { joins=List.copyOf(joins);columns=List.copyOf(columns);group=List.copyOf(group);
            order=List.copyOf(order);inputs=Map.copyOf(inputs); }
    }
    public record Stage(String id,Select query) {}
}
