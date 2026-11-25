package com.openstudy.common.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;

/** Strict command bodies: reject unknown fields rather than silently accepting privilege input. */
public record StrictInput(Map<String, Object> values) {

    private static final int CHOICE_MAX_LENGTH = 100;
    private static final int ID_MAX_LENGTH = 20;
    private static final Pattern ID_PATTERN = Pattern.compile("[1-9][0-9]*");
    private static final String PERMISSIONS = "permissions";

    public StrictInput {
        if (values == null) {
            throw ApiException.validation("body", "입력값이 필요합니다.");
        }
    }

    public StrictInput only(String... allowed) {
        Set<String> allowedFields = Set.of(allowed);

        for (String key : values.keySet()) {
            if (!allowedFields.contains(key)) {
                throw ApiException.validation(key, "허용되지 않은 필드입니다.");
            }
        }

        return this;
    }

    public boolean has(String field) {
        return values.containsKey(field);
    }

    public String text(String field, int max) {
        if (!(values.get(field) instanceof String value) || value.isBlank() || value.length() > max) {
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        }

        return value.strip();
    }

    public String choice(String field, String... allowed) {
        String value = text(field, CHOICE_MAX_LENGTH);

        if (!Set.of(allowed).contains(value)) {
            throw ApiException.validation(field, "허용되지 않은 값입니다.");
        }

        return value;
    }

    public long version(String field) {
        Object value = values.get(field);

        boolean integral = value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;

        if (!integral) {
            throw invalidVersion(field);
        }

        long version = ((Number) value).longValue();
        if (version < 1 || version > ApiVersion.MAX) {
            throw invalidVersion(field);
        }

        return version;
    }

    public long id(String field) {
        String value = text(field, ID_MAX_LENGTH);

        if (!ID_PATTERN.matcher(value).matches()) {
            throw invalidId(field);
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidId(field);
        }
    }

    public List<String> permissions() {
        if (!(values.get(PERMISSIONS) instanceof List<?> input)) {
            throw ApiException.validation(PERMISSIONS, "권한 목록이 필요합니다.");
        }

        Set<String> unique = new TreeSet<>();
        for (Object item : input) {
            if (!(item instanceof String value) || !unique.add(value)) {
                throw ApiException.validation(PERMISSIONS, "중복 없는 권한 목록을 입력해 주세요.");
            }
        }

        return List.copyOf(unique);
    }

    private static ApiException invalidVersion(String field) {
        return ApiException.validation(field, "올바른 버전을 입력해 주세요.");
    }

    private static ApiException invalidId(String field) {
        return ApiException.validation(field, "올바른 ID를 입력해 주세요.");
    }
}
