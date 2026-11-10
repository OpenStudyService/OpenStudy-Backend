package com.openstudy.modules.account.adapter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.openstudy.modules.account.service.PasswordResetMailSender;

/**
 * Plain-text SMTP adapter for reset links. The link carries the token in its fragment, so it never reaches a server or
 * proxy log. Provider failures deliberately lose their message and cause.
 */
public final class SmtpPasswordResetMailSender implements PasswordResetMailSender {
    private final JavaMailSender sender;
    private final String from;
    private final URI resetUrl;

    public SmtpPasswordResetMailSender(JavaMailSender sender, String from, String resetUrl) {
        if (sender == null) throw new IllegalArgumentException("Password reset SMTP transport is required");
        this.sender = sender;
        this.from = mailbox(from);
        this.resetUrl = resetUrl(resetUrl);
    }

    @Override
    public void send(String email, String name, String token) {
        try {
            String recipient = mailbox(email);
            if (name == null || name.isBlank() || name.length() > 100 || token == null || !token.matches("[A-Za-z0-9_-]{43}"))
                throw new DeliveryException();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject("OpenStudy 비밀번호 재설정");
            helper.setText(name + "님, 관리자가 OpenStudy 비밀번호 초기화를 요청했습니다.\n\n"
                    + "아래 링크에서 새 비밀번호를 정해 주세요. 링크는 1시간 동안 한 번만 쓸 수 있습니다.\n"
                    + resetUrl.toASCIIString() + "#token=" + token
                    + "\n\n기존 비밀번호로는 더 이상 로그인할 수 없습니다.\n"
                    + "요청한 적이 없다면 관리자에게 알려 주세요.\n", false);
            sender.send(message);
        } catch (MessagingException | RuntimeException failure) {
            // MailException may embed recipients, MIME content, credentials or provider responses.
            throw new DeliveryException();
        }
    }

    private static String mailbox(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 254 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new DeliveryException();
            var addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].getPersonal() != null || !value.equals(addresses[0].getAddress()))
                throw new DeliveryException();
            addresses[0].validate();
            return value;
        } catch (MessagingException | RuntimeException failure) {
            throw new IllegalArgumentException("A single valid mailbox is required");
        }
    }

    /** HTTPS, or HTTP on loopback for development; no user info, query or fragment; the path ends with /password-reset. */
    static URI resetUrl(String value) {
        try {
            var uri = URI.create(value);
            boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
                    || uri.getPath() == null || !uri.getPath().endsWith("/password-reset")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("A trusted HTTPS password reset URL ending with /password-reset is required");
        }
    }

    public static final class DeliveryException extends RuntimeException {
        public DeliveryException() { super("Password reset mail delivery failed", null, false, false); }
    }
}
