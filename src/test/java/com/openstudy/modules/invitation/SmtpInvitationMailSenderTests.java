package com.openstudy.modules.invitation;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.openstudy.modules.invitation.adapter.SmtpInvitationMailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpInvitationMailSenderTests {
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String FROM = "invitations@example.test";
    private static final String URL = "https://study.example.test/invitations/accept";

    @Test void actualLocalSmtpReceivesUtf8MailWithFragmentOnlyToken(CapturedOutput output) throws Exception {
        try (var receiver = new LocalSmtpReceiver()) {
            var transport = new JavaMailSenderImpl() {
                @Override public void send(MimeMessage... messages) throws MailException {
                    try { super.send(messages); }
                    catch (MailException failure) {
                        // Controlled local test data only. Keep real transport diagnostics visible
                        // without changing the production adapter's exception sanitization.
                        throw new AssertionError("Loopback SMTP transport failed: " + failure.getMessage().replace(TOKEN, "[test token]"), failure);
                    }
                }
            };
            transport.setHost("127.0.0.1");
            transport.setPort(receiver.port());
            transport.setDefaultEncoding("UTF-8");
            transport.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "3000");
            transport.getJavaMailProperties().setProperty("mail.smtp.timeout", "3000");
            transport.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "3000");
            // Avoid machine-name/reverse-DNS lookups before EHLO in the isolated receiver test.
            transport.getJavaMailProperties().setProperty("mail.smtp.localhost", "localhost");
            transport.getJavaMailProperties().setProperty("mail.host", "localhost");
            transport.getJavaMailProperties().setProperty("mail.user", "integration");
            var adapter = new SmtpInvitationMailSender(transport, FROM, URL);
            try { adapter.send("recipient@example.test", "초대받는 직원", TOKEN); }
            catch (AssertionError | RuntimeException failure) {
                throw new AssertionError("Local SMTP receiver: " + receiver.diagnostics(), failure);
            }
            var message = receiver.message();
            assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo(FROM);
            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo("recipient@example.test");
            assertThat(message.getSubject()).isNotBlank();
            String body = text(message);
            assertThat(body).contains(URL + "#token=" + TOKEN).doesNotContain("?token=", "&token=");
            assertThat(message.getSubject()).doesNotContain(TOKEN);
            assertThat(output.getAll()).doesNotContain(TOKEN);
        }
    }

    @Test void smtpFailureDoesNotExposeTokenProviderMessageOrCause(CapturedOutput output) {
        var mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("provider leaked " + TOKEN, new IllegalStateException("smtp credential " + TOKEN)))
                .when(mail).send(any(MimeMessage.class));
        var adapter = new SmtpInvitationMailSender(mail, FROM, URL);
        assertThatThrownBy(() -> adapter.send("recipient@example.test", "직원", TOKEN))
                .isInstanceOf(RuntimeException.class)
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain(TOKEN, "provider leaked", "smtp credential");
                    assertThat(error.getCause()).isNull();
                });
        assertThat(output.getAll()).doesNotContain(TOKEN, "provider leaked", "smtp credential");
    }

    @Test void invalidMailboxesAndAcceptanceUrlsFailBeforeTransport() {
        var mail = mock(JavaMailSender.class);
        for (String from : new String[] {"bad-address", "one@example.test,two@example.test", "sender@example.test\r\nBcc: other@example.test"}) {
            assertThatThrownBy(() -> new SmtpInvitationMailSender(mail, from, URL)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String url : new String[] {"http://study.example.test/invitations/accept", "https://user:secret@example.test/accept",
                "https://study.example.test/accept?token=unsafe", "https://study.example.test/accept#existing", "javascript:alert(1)"}) {
            assertThatThrownBy(() -> new SmtpInvitationMailSender(mail, FROM, url)).isInstanceOf(IllegalArgumentException.class);
        }
        var adapter = new SmtpInvitationMailSender(mail, FROM, URL);
        for (String recipient : new String[] {"bad-address", "one@example.test,two@example.test", "user@example.test\r\nBcc: other@example.test"}) {
            assertThatThrownBy(() -> adapter.send(recipient, "직원", TOKEN))
                    .isInstanceOf(SmtpInvitationMailSender.DeliveryException.class).hasNoCause();
        }
        verify(mail, never()).send(any(MimeMessage.class));
    }

    @Test void localLoopbackAcceptanceUrlIsAllowedForDevelopmentMail() {
        var mail = mock(JavaMailSender.class);
        assertThatCode(() -> new SmtpInvitationMailSender(mail, FROM, "http://localhost:5173/invitations/accept")).doesNotThrowAnyException();
        assertThatCode(() -> new SmtpInvitationMailSender(mail, FROM, "http://127.0.0.1:5173/invitations/accept")).doesNotThrowAnyException();
    }

    @Test void runtimeDebugToggleIsRejectedBeforeAnyMimeTransport(CapturedOutput output) {
        var sends = new AtomicInteger();
        var transport = new JavaMailSenderImpl() {
            @Override public void send(MimeMessage... messages) { sends.incrementAndGet(); }
        };
        var adapter = new SmtpInvitationMailSender(transport, FROM, URL);
        transport.getSession().setDebug(true);
        assertThatThrownBy(() -> adapter.send("recipient@example.test", "직원", TOKEN))
                .isInstanceOf(SmtpInvitationMailSender.DeliveryException.class).hasNoCause();
        transport.getSession().setDebug(false);
        transport.getJavaMailProperties().setProperty("mail.debug.auth", "true");
        assertThatThrownBy(() -> adapter.send("recipient@example.test", "직원", TOKEN))
                .isInstanceOf(SmtpInvitationMailSender.DeliveryException.class).hasNoCause();
        assertThat(sends.get()).isZero();
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private static String text(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String string) return string;
        if (content instanceof Multipart multipart) {
            var result = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) result.append(text(multipart.getBodyPart(i)));
            return result.toString();
        }
        throw new IllegalStateException("Unexpected test MIME content");
    }

    /** Small SMTP receiver bound only to loopback; it never forwards mail or uses an external account. */
    private static final class LocalSmtpReceiver implements AutoCloseable {
        private final ServerSocket server;
        private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<MimeMessage> received;
        private final java.util.List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        LocalSmtpReceiver() throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(15000);
            received = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(15000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                    reply(output, "220 localhost test SMTP");
                    MimeMessage message = null;
                    for (String line; (line = input.readLine()) != null;) {
                        String command = line.toUpperCase(java.util.Locale.ROOT);
                        commands.add(command.split("[ :]")[0]);
                        if (command.startsWith("EHLO") || command.startsWith("HELO")) reply(output, "250 localhost");
                        else if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) reply(output, "250 OK");
                        else if (command.equals("DATA")) {
                            reply(output, "354 End with dot");
                            commands.add("DATA-START");
                            var data = new StringBuilder();
                            while ((line = input.readLine()) != null && !line.equals(".")) {
                                if (line.startsWith("..")) line = line.substring(1);
                                data.append(line).append("\r\n");
                            }
                            commands.add("DATA-END");
                            message = new MimeMessage(Session.getInstance(new Properties()),
                                    new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.US_ASCII)));
                            reply(output, "250 queued locally");
                        } else if (command.equals("QUIT")) {
                            reply(output, "221 Bye");
                            break;
                        } else reply(output, "502 unsupported test SMTP command");
                    }
                    if (message == null) throw new IllegalStateException("No SMTP DATA received");
                    return message;
                }
            });
        }
        int port() { return server.getLocalPort(); }
        MimeMessage message() throws Exception { return received.get(10, TimeUnit.SECONDS); }
        String diagnostics() {
            try {
                received.get(1, TimeUnit.SECONDS);
                return "commands=" + commands + "; receiver completed";
            } catch (Exception failure) {
                Throwable root = failure.getCause() == null ? failure : failure.getCause();
                return "commands=" + commands + "; receiver=" + root.getClass().getSimpleName() + ":" + root.getMessage();
            }
        }
        private static void reply(OutputStreamWriter output, String line) throws Exception { output.write(line + "\r\n"); output.flush(); }
        public void close() throws Exception { server.close(); executor.shutdownNow(); }
    }
}
