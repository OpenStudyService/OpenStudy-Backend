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

    private ProductInputs() {}

    static long integer(Object value, String field, long min, long max) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
            throw ApiException.validation(field, "허용 범위의 정수를 입력해 주세요.");
        }
        return ((Number) value).longValue();
    }

    static long price(Object value) { return integer(value, "priceKrw", 1, JS_MAX); }

    static ProductTerms terms(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw ApiException.validation("terms", "상품 조건이 필요합니다.");
        if (!(map.get("kind") instanceof String kind)) throw ApiException.validation("terms.kind", "상품 유형이 필요합니다.");
        return switch (kind) {
            case ProductTerms.TIME -> {
                exactly(map, TIME_FIELDS);
                yield ProductTerms.Time.of(integer(map.get("includedMinutes"), "terms.includedMinutes", 1, U32_MAX),
                        validDays(map));
            }
            case ProductTerms.PERIOD -> {
                exactly(map, PERIOD_FIELDS);
                yield ProductTerms.Period.of(integer(map.get("periodDays"), "terms.periodDays", 1, U32_MAX));
            }
            case ProductTerms.STORED_VALUE -> {
                exactly(map, STORED_FIELDS);
                yield ProductTerms.StoredValue.of(integer(map.get("creditKrw"), "terms.creditKrw", 1, JS_MAX),
                        integer(map.get("hourlyRateKrw"), "terms.hourlyRateKrw", 1, JS_MAX), validDays(map));
            }
            default -> throw ApiException.validation("terms.kind", "올바른 상품 유형을 선택해 주세요.");
        };
    }

    /** {@code null} means no expiry; zero or negative values never stand in for it. */
    private static Long validDays(Map<?, ?> map) {
        Object value = map.get("validDays");
        return value == null ? null : integer(value, "terms.validDays", 1, U32_MAX);
    }

    private static void exactly(Map<?, ?> map, Set<String> fields) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !fields.contains(name))
                throw ApiException.validation("terms." + key, "이 상품 유형에서 허용되지 않은 필드입니다.");
        }
        for (String field : fields) {
            if (!map.containsKey(field)) throw ApiException.validation("terms." + field, "필수 항목입니다.");
        }
    }

    static Set<Long> branchIds(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 100)
            throw ApiException.validation("branchIds", "판매 지점을 1~100개 지정해 주세요.");
        Set<Long> ids = new TreeSet<>();
        for (Object item : list) {
            Long id = item instanceof String text ? parse(text) : null;
            if (id == null || !ids.add(id)) throw ApiException.validation("branchIds", "중복 없는 올바른 지점 ID를 입력해 주세요.");
        }
        return ids;
    }

    static long id(String text, String field) {
        Long id = parse(text);
        if (id == null) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        return id;
    }

    /** Positive decimal ID that fits a signed long; larger BIGINT UNSIGNED values are never valid here. */
    private static Long parse(String text) {
        if (!text.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.parseLong(text); }
        catch (NumberFormatException exception) { return null; }
    }
}
