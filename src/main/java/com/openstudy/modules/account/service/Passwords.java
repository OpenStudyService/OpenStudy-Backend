package com.openstudy.modules.account.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;

/** Account credential inputs and reset tokens. Tokens are stored only as their SHA-256 hash. */
public final class Passwords {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ENTROPY_BYTES = 32;
    private static final String MASK = "***";

    private Passwords() {}

    /** The body holds exactly the given string fields; anything else is VALIDATION_FAILED. */
    static Map<String, String> requireStringFields(Map<String, Object> body, Set<String> names) {
        if (body == null) {
            throw ApiException.validation("body", "요청 본문이 필요합니다.");
        }

        for (String key : body.keySet()) {
            if (!names.contains(key)) {
                throw ApiException.validation(key, "허용되지 않은 필드입니다.");
            }
        }

        Map<String, String> values = new HashMap<>();
        for (String name : names) {
            Object value = body.get(name);
            if (!(value instanceof String text) || text.isEmpty()) {
                throw ApiException.validation(name, "필수 입력입니다.");
            }
            values.put(name, text);
        }

        return values;
    }

    static boolean isWellFormedToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    static String createToken() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes());
    }

    /** A secret nobody knows: its BCrypt hash makes the old password stop working at once (M14-D11). */
    static String createUnknowableSecret() {
        return Base64.getEncoder().encodeToString(randomBytes());
    }

    static byte[] hashToken(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** ab***@example.com: the first two characters of the local part and the domain (one when the part is that short). */
    static String toEmailHint(String email) {
        int at = email.lastIndexOf('@');
        if (at < 1) {
            return MASK;
        }

        String localPart = email.substring(0, at);
        int shownLength = localPart.length() <= 2 ? 1 : 2;

        return localPart.substring(0, shownLength) + MASK + email.substring(at);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
