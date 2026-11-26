package com.openstudy.modules.customer.service;

import java.util.Map;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.customer.entity.CustomerStatus;

/** Field rules shared by registration and update; every message is a fixed, client-safe text. */
final class CustomerInputs {

    static final String NORMAL = "정상";
    static final String RESTRICTED = "이용 제한";
    static final String INACTIVE = "비활성";
    static final String MERGED = "병합됨";

    private static final Pattern PHONE = Pattern.compile("[0-9]{9,11}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_MEMO_LENGTH = 1000;
    private static final int MIN_PHONE_FRAGMENT_DIGITS = 4;

    private CustomerInputs() {
    }

    static String readName(StrictInput input) {
        return input.text("name", MAX_NAME_LENGTH);
    }

    /** Hyphens and spaces are formatting only; the stored value is the 9~11 digits. */
    static String readPhone(Map<String, Object> body) {
        if (!(body.get("phone") instanceof String value)) {
            throw ApiException.validation("phone", "연락처를 입력해 주세요.");
        }

        String digits = SEPARATORS.matcher(value).replaceAll("");

        if (!PHONE.matcher(digits).matches()) {
            throw ApiException.validation("phone", "연락처는 숫자 9~11자리로 입력해 주세요.");
        }

        return digits;
    }

    static String readEmail(Map<String, Object> body) {
        String value = readOptional(body, "email", MAX_EMAIL_LENGTH);

        if (value != null && !EMAIL.matcher(value).matches()) {
            throw ApiException.validation("email", "올바른 이메일을 입력해 주세요.");
        }

        return value;
    }

    static String readMemo(Map<String, Object> body) {
        return readOptional(body, "memo", MAX_MEMO_LENGTH);
    }

    /** Absent, null and blank all mean "no value"; anything else must be a string within the limit. */
    private static String readOptional(Map<String, Object> body, String field, int max) {
        Object value = body.get(field);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text) || text.strip().length() > max) {
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        }

        return text.isBlank() ? null : text.strip();
    }

    /** Digits-only form of a search term with at least four digits, else null (name / member number only). */
    static String toPhoneFragment(String q) {
        String digits = SEPARATORS.matcher(q).replaceAll("");
        boolean phoneLike = digits.length() >= MIN_PHONE_FRAGMENT_DIGITS && digits.chars().allMatch(Character::isDigit);

        return phoneLike ? digits : null;
    }

    static CustomerStatus toStatus(String label, String field) {
        return switch (label) {
            case NORMAL -> CustomerStatus.NORMAL;
            case RESTRICTED -> CustomerStatus.RESTRICTED;
            case INACTIVE -> CustomerStatus.INACTIVE;
            case MERGED -> CustomerStatus.MERGED;
            default -> throw ApiException.validation(field, "올바른 고객 상태를 선택해 주세요.");
        };
    }

    static String toLabel(CustomerStatus status) {
        return switch (status) {
            case NORMAL -> NORMAL;
            case RESTRICTED -> RESTRICTED;
            case INACTIVE -> INACTIVE;
            case MERGED -> MERGED;
        };
    }
}
