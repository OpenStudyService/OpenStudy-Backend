package com.openstudy.domainlens.api.ir;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record QuerySpec(String id, String source, String grain, Mode mode,
        List<Selection> dimensions, List<Selection> metrics, @Nullable Filter filter,
        List<Order> orderBy, Page pagination, int limit, @Nullable String analysisId) {
    public QuerySpec { dimensions = List.copyOf(dimensions); metrics = List.copyOf(metrics); orderBy = List.copyOf(orderBy); }
    public enum Mode { DETAIL, AGGREGATE, REGISTERED_ANALYSIS }
    public record Selection(String field, String alias) {}
    public record Order(String alias, Direction direction) {}
    public enum Direction { ASC, DESC }
    public record Page(int page, int size) {}
}
