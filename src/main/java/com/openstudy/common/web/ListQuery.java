package com.openstudy.common.web;

import java.util.Set;

import com.openstudy.common.exception.ApiException;

/** Endpoint supplies its own sort whitelist; a query value must never become a SQL identifier. */
public record ListQuery(long page, int pageSize, String q, String sort, Direction direction) {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final String DEFAULT_DIRECTION = "desc";

    public enum Direction { ASC, DESC }

    public static ListQuery parse(
            long page,
            int pageSize,
            String q,
            String sort,
            String direction,
            Set<String> allowedSorts,
            String defaultSort
    ) {
        if (page < 1 || page > ApiVersion.MAX) {
            throw ApiException.validation("page", "페이지는 1 이상이어야 합니다.");
        }

        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw ApiException.validation("pageSize", "페이지 크기는 1~100이어야 합니다.");
        }

        String query = q == null ? "" : q.strip();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw ApiException.validation("q", "검색어는 100자 이하여야 합니다.");
        }

        String sortField = sort == null ? defaultSort : sort;
        if (!allowedSorts.contains(sortField)) {
            throw ApiException.validation("sort", "지원하지 않는 정렬 항목입니다.");
        }

        Direction order = switch (direction == null ? DEFAULT_DIRECTION : direction) {
            case "asc" -> Direction.ASC;
            case "desc" -> Direction.DESC;
            default -> throw ApiException.validation("direction", "정렬 방향은 asc 또는 desc여야 합니다.");
        };

        return new ListQuery(page, pageSize, query, sortField, order);
    }

    public long offset() {
        return (page - 1) * pageSize;
    }
}
