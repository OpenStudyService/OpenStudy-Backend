package com.openstudy.modules.ticket.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.ticket.dto.ProductTerms;

/**
 * Strict parsing of product command bodies. Every kind accepts exactly its own fields, so a field of another
 * kind (for example {@code periodDays} on a time pass) is rejected instead of being silently dropped.
 */
final class ProductInputs {

    static final long JS_MAX = 9_007_199_254_740_991L;
    static final long U32_MAX = 4_294_967_295L;

    private static final Set<String> TIME_FIELDS = Set.of("kind", "includedMinutes", "validDays");
    private static final Set<String> PERIOD_FIELDS = Set.of("kind", "periodDays");
    private static final Set<String> STORED_FIELDS = Set.of("kind", "creditKrw", "hourlyRateKrw", "validDays");
    private static final int MAX_BRANCHES = 100;
    private static final String ID_FORMAT = "[1-9][0-9]{0,18}";

    private ProductInputs() {
    }

    static long parseInteger(Object value, String field, long min, long max) {
        boolean isIntegral = value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;

        if (!isIntegral || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
            throw ApiException.validation(field, "허용 범위의 정수를 입력해 주세요.");
        }

        return ((Number) value).longValue();
    }

    static long parsePrice(Object value) {
        return parseInteger(value, "priceKrw", 1, JS_MAX);
    }

    static ProductTerms parseTerms(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw ApiException.validation("terms", "상품 조건이 필요합니다.");
        }

        if (!(map.get("kind") instanceof String kind)) {
            throw ApiException.validation("terms.kind", "상품 유형이 필요합니다.");
        }

        return switch (kind) {
            case ProductTerms.TIME -> {
                requireExactFields(map, TIME_FIELDS);
                long includedMinutes = parseInteger(map.get("includedMinutes"), "terms.includedMinutes", 1, U32_MAX);
                yield ProductTerms.Time.of(includedMinutes, parseValidDays(map));
            }
            case ProductTerms.PERIOD -> {
                requireExactFields(map, PERIOD_FIELDS);
                yield ProductTerms.Period.of(parseInteger(map.get("periodDays"), "terms.periodDays", 1, U32_MAX));
            }
            case ProductTerms.STORED_VALUE -> {
                requireExactFields(map, STORED_FIELDS);
                long creditKrw = parseInteger(map.get("creditKrw"), "terms.creditKrw", 1, JS_MAX);
                long hourlyRateKrw = parseInteger(map.get("hourlyRateKrw"), "terms.hourlyRateKrw", 1, JS_MAX);
                yield ProductTerms.StoredValue.of(creditKrw, hourlyRateKrw, parseValidDays(map));
            }
            default -> throw ApiException.validation("terms.kind", "올바른 상품 유형을 선택해 주세요.");
        };
    }

    static Set<Long> parseBranchIds(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_BRANCHES) {
            throw ApiException.validation("branchIds", "판매 지점을 1~100개 지정해 주세요.");
        }

        Set<Long> ids = new TreeSet<>();

        for (Object item : list) {
            Long id = item instanceof String text ? parseIdOrNull(text) : null;

            if (id == null || !ids.add(id)) {
                throw ApiException.validation("branchIds", "중복 없는 올바른 지점 ID를 입력해 주세요.");
            }
        }

        return ids;
    }

    static long parseId(String text, String field) {
        Long id = parseIdOrNull(text);

        if (id == null) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }

        return id;
    }

    /** {@code null} means no expiry; zero or negative values never stand in for it. */
    private static Long parseValidDays(Map<?, ?> map) {
        Object value = map.get("validDays");

        return value == null ? null : parseInteger(value, "terms.validDays", 1, U32_MAX);
    }

    private static void requireExactFields(Map<?, ?> map, Set<String> fields) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !fields.contains(name)) {
                throw ApiException.validation("terms." + key, "이 상품 유형에서 허용되지 않은 필드입니다.");
            }
        }

        for (String field : fields) {
            if (!map.containsKey(field)) {
                throw ApiException.validation("terms." + field, "필수 항목입니다.");
            }
        }
    }

    /** Positive decimal ID that fits a signed long; larger BIGINT UNSIGNED values are never valid here. */
    private static Long parseIdOrNull(String text) {
        if (!text.matches(ID_FORMAT)) {
            return null;
        }

        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
