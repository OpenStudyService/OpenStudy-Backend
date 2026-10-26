package com.openstudy.modules.invitation.adapter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import com.openstudy.modules.invitation.service.InvitationMailSender;

/** Plain-text SMTP adapter. Provider failures deliberately lose their message and cause. */
public final class SmtpInvitationMailSender implements InvitationMailSender {
    private final JavaMailSender sender;
    private final String from;
    private final URI acceptanceUrl;

    public SmtpInvitationMailSender(JavaMailSender sender, String from, String acceptanceUrl) {
        if (sender == null) throw new IllegalArgumentException("Invitation SMTP transport is required");
        this.sender = sender;
        requirePrivateTransport(sender);
        this.from = mailbox(from);
        this.acceptanceUrl = acceptanceUrl(acceptanceUrl);
    }

    @Override
    public void send(String email, String name, String token) {
        try {
            requirePrivateTransport(sender);
            String recipient = mailbox(email);
            if (name == null || name.isBlank() || name.length() > 100
                    || token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new DeliveryException();
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject("OpenStudy 직원 초대");
            helper.setText(name + "님, OpenStudy 직원 초대를 받았습니다.\n\n초대 수락 링크:\n"
                    + acceptanceUrl.toASCIIString() + "#token=" + token
                    + "\n\n만료되거나 재발급된 링크는 사용할 수 없습니다.\n"
                    + "초대를 수락한 뒤 관리자의 승인이 필요합니다.\n", false);
            sender.send(message);
        } catch (MessagingException | RuntimeException failure) {
            // MailException may embed recipients, MIME content, credentials or provider responses.
            throw new DeliveryException();
        }
    }

    private static void requirePrivateTransport(JavaMailSender sender) {
        if (sender instanceof JavaMailSenderImpl smtp) {
            var session = smtp.getSession();
            if (session.getDebug() || Boolean.parseBoolean(session.getProperty("mail.debug"))
                    || Boolean.parseBoolean(session.getProperty("mail.debug.auth"))) {
                throw new IllegalArgumentException("SMTP debug output must be disabled for invitation delivery");
            }
        }
    }

    private static String mailbox(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 254
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new DeliveryException();
            var addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].getPersonal() != null
                    || !value.equals(addresses[0].getAddress())) throw new DeliveryException();
            addresses[0].validate();
            return value;
        } catch (MessagingException | RuntimeException failure) {
            throw new IllegalArgumentException("A single valid invitation mailbox is required");
        }
    }

    private static URI acceptanceUrl(String value) {
        try {
            var uri = URI.create(value);
            boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !("https".equals(uri.getScheme())
                    || (local && "http".equals(uri.getScheme())))
                    || uri.getPath() == null || !uri.getPath().endsWith("/invitations/accept")) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("A trusted HTTPS invitation acceptance URL is required");
        }
    }

    public static final class DeliveryException extends RuntimeException {
        public DeliveryException() { super("Invitation mail delivery failed", null, false, false); }
    }
}
