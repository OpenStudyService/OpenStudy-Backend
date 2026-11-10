package com.openstudy.modules.employee.dto;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Storage keys of profile photos and the app path shown for them (#120, account-collab-api.md "프로필 사진", M14-D13).
 * A key is {@code avatars/<employee>/<uuid>.<jpg|png|webp>} and stays on the server: responses carry
 * {@code /api/employees/<employee>/avatar?v=<uuid>} instead, so a new photo has a new path and no cache shows the old one.
 * A stored value of any other shape (a legacy URL) is treated as no photo.
 */
public final class AvatarKeys {
    private static final Pattern KEY = Pattern.compile("avatars/([1-9][0-9]{0,18})/([0-9a-f]{32})\\.(jpg|png|webp)");

    public enum Format {
        JPG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp");

        public final String extension;
        public final String contentType;

        Format(String extension, String contentType) { this.extension = extension; this.contentType = contentType; }
    }

    public record Parsed(long employeeId, String version, Format format) {}

    private AvatarKeys() {}

    public static String newKey(long employeeId, Format format) {
        return "avatars/" + employeeId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + format.extension;
    }

    public static Optional<Parsed> parse(String key) {
        if (key == null) return Optional.empty();
        var matcher = KEY.matcher(key);
        if (!matcher.matches()) return Optional.empty();
        Format format = switch (matcher.group(3)) {
            case "jpg" -> Format.JPG;
            case "png" -> Format.PNG;
            default -> Format.WEBP;
        };
        return Optional.of(new Parsed(Long.parseLong(matcher.group(1)), matcher.group(2), format));
    }

    /** The path for a stored key of this employee, or "" when there is no usable photo. */
    public static String path(long employeeId, String key) {
        return parse(key).filter(parsed -> parsed.employeeId() == employeeId)
                .map(parsed -> "/api/employees/" + employeeId + "/avatar?v=" + parsed.version()).orElse("");
    }
}
