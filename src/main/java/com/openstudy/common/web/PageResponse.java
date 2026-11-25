package com.openstudy.common.web;

import java.util.Comparator;
import java.util.List;

public record PageResponse<T>(List<T> items, Meta meta) {

    public record Meta(long page, int pageSize, long total) {}

    public PageResponse {
        items = List.copyOf(items);
    }

    /** Input must already be authorized and filtered; total is calculated from that same set. */
    public static <T> PageResponse<T> fromAuthorized(
            List<T> rows,
            ListQuery query,
            Comparator<T> selectedField,
            Comparator<T> id
    ) {
        Comparator<T> stableOrder = selectedField.thenComparing(id);
        if (query.direction() == ListQuery.Direction.DESC) {
            stableOrder = stableOrder.reversed();
        }

        List<T> ordered = rows.stream()
                .sorted(stableOrder)
                .toList();

        int start = (int) Math.min(query.offset(), ordered.size());
        int end = Math.min(start + query.pageSize(), ordered.size());

        Meta meta = new Meta(query.page(), query.pageSize(), ordered.size());

        return new PageResponse<>(ordered.subList(start, end), meta);
    }
}
