package com.openstudy.common.web;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;

/** Shared input validation for newly chosen passwords. Values are never trimmed. */
public final class PasswordPolicy {
    private static final int MAX_BYTES = 72;
    private static final int MIN_CODE_POINTS = 8;
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("\\p{Punct}");

    private PasswordPolicy() {}

    /** Bounds the input size for BCrypt, including when verifying an existing password. */
    public static void validateByteLength(String field, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw ApiException.validation(field, "비밀번호는 UTF-8 72바이트 이하여야 합니다.");
    }

    /** Validates a newly chosen password and returns the original value for hashing. */
    public static String validateNew(String field, Object value) {
        if (!(value instanceof String password))
            throw ApiException.validation(field, "비밀번호를 입력해 주세요.");
        validateByteLength(field, password);
        if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()
                || !SPECIAL.matcher(password).find())
            throw ApiException.validation(field, "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해 8자 이상이어야 합니다.");
        return password;
    }
}
