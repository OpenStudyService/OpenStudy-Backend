package com.openstudy.modules.account.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;

/**
 * Password and reset-token rules of account-collab-api.md. Passwords are never trimmed, logged or echoed; a new one has at
 * least 8 code points and at most 72 UTF-8 bytes (the BCrypt limit), with an ASCII letter, digit and punctuation.
 * Tokens are 32 random bytes in URL-safe Base64 and are
 * stored only as their SHA-256 hash.
 */
public final class Passwords {
    static final int MAX_BYTES = 72;
    static final int MIN_CODE_POINTS = 8;
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("\\p{Punct}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    /** The body holds exactly the given string fields; anything else is VALIDATION_FAILED. */
    static Map<String, String> fields(Map<String, Object> body, Set<String> names) {
        if (body == null) throw ApiException.validation("body", "요청 본문이 필요합니다.");
        for (String key : body.keySet())
            if (!names.contains(key)) throw ApiException.validation(key, "허용되지 않은 필드입니다.");
        var values = new java.util.HashMap<String, String>();
        for (String name : names) {
            Object value = body.get(name);
            if (!(value instanceof String text) || text.isEmpty()) throw ApiException.validation(name, "필수 입력입니다.");
            values.put(name, text);
        }
        return values;
    }

    /** Any password field: at most 72 UTF-8 bytes. */
    static void bounded(String field, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw ApiException.validation(field, "비밀번호는 UTF-8 72바이트 이하여야 합니다.");
    }

    /** Validates a newly chosen password and returns the original value for hashing. */
    public static String acceptable(String field, Object value) {
        if (!(value instanceof String password))
            throw ApiException.validation(field, "비밀번호를 입력해 주세요.");
        bounded(field, password);
        if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()
                || !SPECIAL.matcher(password).find())
            throw ApiException.validation(field, "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해 8자 이상이어야 합니다.");
        return password;
    }

    static boolean wellFormedToken(String token) { return token != null && TOKEN.matcher(token).matches(); }

    static String newToken() { return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy()); }

    /** A secret nobody knows: its BCrypt hash makes the old password stop working at once (M14-D11). */
    static String unknowable() { return Base64.getEncoder().encodeToString(entropy()); }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** ab***@example.com: the first two characters of the local part and the domain (one when the part is that short). */
    static String emailHint(String email) {
        int at = email.lastIndexOf('@');
        if (at < 1) return "***";
        String local = email.substring(0, at);
        int shown = local.length() <= 2 ? 1 : 2;
        return local.substring(0, shown) + "***" + email.substring(at);
    }

    private static byte[] entropy() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
