package com.openstudy.modules.invitation.adapter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import com.openstudy.modules.invitation.service.InvitationMailSender;

/** Plain-text SMTP adapter. Provider failures deliberately lose their message and cause. */
public final class SmtpInvitationMailSender implements InvitationMailSender {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_MAILBOX_LENGTH = 254;
    private static final String TOKEN_FORMAT = "[A-Za-z0-9_-]{43}";
    private static final String ACCEPTANCE_PATH = "/invitations/accept";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final String SUBJECT = "OpenStudy 직원 초대";

    private final JavaMailSender javaMailSender;
    private final String from;
    private final URI acceptanceUrl;

    public SmtpInvitationMailSender(JavaMailSender javaMailSender, String from, String acceptanceUrl) {
        if (javaMailSender == null) {
            throw new IllegalArgumentException("Invitation SMTP transport is required");
        }

        this.javaMailSender = javaMailSender;
        requirePrivateTransport(javaMailSender);
        this.from = requireMailbox(from);
        this.acceptanceUrl = requireAcceptanceUrl(acceptanceUrl);
    }

    @Override
    public void send(String email, String name, String token) {
        try {
            requirePrivateTransport(javaMailSender);
            String recipient = requireMailbox(email);
            boolean validName = name != null && !name.isBlank() && name.length() <= MAX_NAME_LENGTH;
            boolean validToken = token != null && token.matches(TOKEN_FORMAT);

            if (!validName || !validToken) {
                throw new DeliveryException();
            }

            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(SUBJECT);
            helper.setText(createBody(name, token), false);
            javaMailSender.send(message);
        } catch (MessagingException | RuntimeException failure) {
            // MailException may embed recipients, MIME content, credentials or provider responses.
            throw new DeliveryException();
        }
    }

    private String createBody(String name, String token) {
        return name + "님, OpenStudy 직원 초대를 받았습니다.\n\n초대 수락 링크:\n"
                + acceptanceUrl.toASCIIString() + "#token=" + token
                + "\n\n만료되거나 재발급된 링크는 사용할 수 없습니다.\n"
                + "초대를 수락한 뒤 관리자의 승인이 필요합니다.\n";
    }

    private static void requirePrivateTransport(JavaMailSender javaMailSender) {
        if (!(javaMailSender instanceof JavaMailSenderImpl smtp)) {
            return;
        }

        Session session = smtp.getSession();
        boolean debugEnabled = session.getDebug()
                || Boolean.parseBoolean(session.getProperty("mail.debug"))
                || Boolean.parseBoolean(session.getProperty("mail.debug.auth"));

        if (debugEnabled) {
            throw new IllegalArgumentException("SMTP debug output must be disabled for invitation delivery");
        }
    }

    private static String requireMailbox(String value) {
        try {
            boolean wellFormed = value != null
                    && !value.isBlank()
                    && value.length() <= MAX_MAILBOX_LENGTH
                    && value.indexOf('\r') < 0
                    && value.indexOf('\n') < 0;

            if (!wellFormed) {
                throw new DeliveryException();
            }

            InternetAddress[] addresses = InternetAddress.parse(value, true);
            boolean singlePlainAddress = addresses.length == 1
                    && addresses[0].getPersonal() == null
                    && value.equals(addresses[0].getAddress());

            if (!singlePlainAddress) {
                throw new DeliveryException();
            }

            addresses[0].validate();
            return value;
        } catch (MessagingException | RuntimeException failure) {
            throw new IllegalArgumentException("A single valid invitation mailbox is required");
        }
    }

    private static URI requireAcceptanceUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean local = LOCAL_HOSTS.contains(uri.getHost());
            boolean trustedScheme = "https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()));
            boolean plainUrl = uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
            boolean acceptancePath = uri.getPath() != null && uri.getPath().endsWith(ACCEPTANCE_PATH);

            if (!plainUrl || !trustedScheme || !acceptancePath) {
                throw new IllegalArgumentException();
            }

            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("A trusted HTTPS invitation acceptance URL is required");
        }
    }

    public static final class DeliveryException extends RuntimeException {

        public DeliveryException() {
            super("Invitation mail delivery failed", null, false, false);
        }
    }
}
