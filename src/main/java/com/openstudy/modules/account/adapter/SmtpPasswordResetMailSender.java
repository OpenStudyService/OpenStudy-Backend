package com.openstudy.modules.account.adapter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import com.openstudy.modules.account.service.PasswordResetMailSender;

/**
 * Plain-text SMTP adapter for password reset links.
 *
 * <p>The reset token is placed in the URL fragment, which is excluded
 * from the initial HTTP request. Provider exception details are not
 * propagated to avoid exposing sensitive information.
 */
public final class SmtpPasswordResetMailSender implements PasswordResetMailSender {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private final JavaMailSender sender;
    private final String from;
    private final URI resetUrl;

    public SmtpPasswordResetMailSender(JavaMailSender sender, String from, String resetUrl) {
        if (sender == null) {
            throw new IllegalArgumentException("Password reset SMTP transport is required");
        }

        this.sender = sender;
        this.from = validateMailbox(from);
        this.resetUrl = validateResetUrl(resetUrl);
    }

    @Override
    public void send(String email, String name, String token) {
        try {
            String recipient = validateMailbox(email);
            validateResetRequest(name, token);

            MimeMessage message = createMessage(recipient, name, token);
            sender.send(message);
        } catch (MessagingException | RuntimeException failure) {
            // Do not expose provider messages, recipients or credentials.
            throw new DeliveryException();
        }
    }

    private MimeMessage createMessage(String recipient, String name, String token) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());

        String link = resetUrl.toASCIIString() + "#token=" + token;

        helper.setFrom(from);
        helper.setTo(recipient);
        helper.setSubject("OpenStudy 비밀번호 재설정");
        helper.setText("""
                %s님, 관리자가 OpenStudy 비밀번호 초기화를 요청했습니다.

                아래 링크에서 새 비밀번호를 정해 주세요. 링크는 1시간 동안 한 번만 쓸 수 있습니다.
                %s

                기존 비밀번호로는 더 이상 로그인할 수 없습니다.
                요청한 적이 없다면 관리자에게 알려 주세요.
                """.formatted(name, link), false);

        return message;
    }

    private static void validateResetRequest(String name, String token) {
        if (name == null
                || name.isBlank()
                || name.length() > MAX_NAME_LENGTH
                || token == null
                || !TOKEN_PATTERN.matcher(token).matches()
        ) {
            throw new DeliveryException();
        }
    }

    private static String validateMailbox(String value) {
        try {
            if (value == null
                    || value.isBlank()
                    || value.length() > MAX_EMAIL_LENGTH
                    || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0
            ) {
                throw new DeliveryException();
            }

            InternetAddress[] addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1
                    || addresses[0].getPersonal() != null
                    || !value.equals(addresses[0].getAddress())
            ) {
                throw new DeliveryException();
            }

            addresses[0].validate();
            return value;
        } catch (MessagingException | RuntimeException failure) {
            throw new IllegalArgumentException("A single valid mailbox is required");
        }
    }

    /**
     * Requires HTTPS, except HTTP on loopback for development.
     * User info, query and fragment are prohibited.
     * The path must end with /password-reset.
     */
    static URI validateResetUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean local = LOOPBACK_HOSTS.contains(uri.getHost());

            boolean invalidAuthority = uri.getHost() == null || uri.getUserInfo() != null;
            boolean invalidParameters = uri.getRawQuery() != null || uri.getRawFragment() != null;
            boolean invalidScheme = !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())));
            boolean invalidPath = uri.getPath() == null || !uri.getPath().endsWith("/password-reset");

            if (invalidAuthority
                    || invalidParameters
                    || invalidScheme
                    || invalidPath
            ) {
                throw new IllegalArgumentException();
            }

            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("A trusted HTTPS password reset URL ending with /password-reset is required");
        }
    }

    public static final class DeliveryException extends RuntimeException {
        public DeliveryException() {
            super("Password reset mail delivery failed", null, false, false);
        }
    }
}
