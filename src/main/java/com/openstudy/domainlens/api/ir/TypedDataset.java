package com.openstudy.domainlens.api.ir;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record TypedDataset(String id, List<Column> columns, List<Map<String,Object>> rows,
        long totalElements, int page, int size, Instant snapshotAt, Instant asOf) {
    public TypedDataset { columns=List.copyOf(columns); rows=rows.stream().map(row -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row))).toList(); }
    public record Column(String alias, ValueType type, @Nullable String unit, @Nullable Integer precision) {}
}
