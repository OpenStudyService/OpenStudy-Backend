package com.openstudy.common.web;

import java.util.*;
import com.openstudy.common.exception.ApiException;

/** Strict command bodies: reject unknown fields rather than silently accepting privilege input. */
public record StrictInput(Map<String, Object> values) {
    public StrictInput {
        if (values == null) throw ApiException.validation("body", "입력값이 필요합니다.");
    }
    public StrictInput only(String... allowed) {
        Set<String> fields = Set.of(allowed);
        for (String key : values.keySet()) if (!fields.contains(key)) throw ApiException.validation(key, "허용되지 않은 필드입니다.");
        return this;
    }
    public boolean has(String field) { return values.containsKey(field); }
    public String text(String field, int max) {
        if (!(values.get(field) instanceof String value) || value.isBlank() || value.length() > max)
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        return value.strip();
    }
    public String choice(String field, String... allowed) {
        String value = text(field, 100);
        if (!Set.of(allowed).contains(value)) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return value;
    }
    public long version(String field) {
        Object value = values.get(field);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 1 || ((Number) value).longValue() > ApiVersion.MAX)
            throw ApiException.validation(field, "올바른 버전을 입력해 주세요.");
        return ((Number) value).longValue();
    }
    public long id(String field) {
        String value = text(field, 20);
        try { if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw ApiException.validation(field, "올바른 ID를 입력해 주세요."); }
    }
    public List<String> permissions() {
        if (!(values.get("permissions") instanceof List<?> input)) throw ApiException.validation("permissions", "권한 목록이 필요합니다.");
        Set<String> unique = new TreeSet<>();
        for (Object item : input) if (!(item instanceof String value) || !unique.add(value))
            throw ApiException.validation("permissions", "중복 없는 권한 목록을 입력해 주세요.");
        return List.copyOf(unique);
    }
}
