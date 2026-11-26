package com.openstudy.modules.invitation.service;

import java.time.Duration;
import java.util.Properties;
import jakarta.mail.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.openstudy.modules.invitation.adapter.SmtpInvitationMailSender;

@Configuration(proxyBeanMethods = false)
public class InvitationMailConfiguration {

    private static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 5_000;
    private static final long DEFAULT_READ_TIMEOUT_MILLIS = 10_000;
    private static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 10_000;
    private static final long MAX_TIMEOUT_MILLIS = 30_000;

    @Bean
    @ConditionalOnMissingBean
    InvitationDeliverySettings invitationDeliverySettings(
            @Value("${openstudy.invitation-mail.batch-size:10}") int batchSize,
            @Value("${openstudy.invitation-mail.lease-seconds:60}") long leaseSeconds,
            @Value("${openstudy.invitation-mail.retry-delay-seconds:60}") long retrySeconds,
            @Value("${openstudy.invitation-mail.max-attempts:5}") int maxAttempts
    ) {
        return new InvitationDeliverySettings(
                batchSize,
                Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(retrySeconds),
                maxAttempts
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "openstudy.invitation-mail.enabled", havingValue = "true")
    @Slf4j(topic = "com.openstudy.modules.invitation.service.InvitationEmailScheduling")
    static class Enabled {

        private static final long MIN_POLL_MILLIS = 100;
        private static final long MAX_POLL_MILLIS = 300_000;

        @Bean(name = "taskScheduler")
        @ConditionalOnMissingBean(name = "taskScheduler")
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("workspace-scheduling-");
            scheduler.setDaemon(true);

            return scheduler;
        }

        @Bean(name = "invitationMailTaskScheduler")
        ThreadPoolTaskScheduler invitationMailTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("invitation-mail-");
            scheduler.setDaemon(true);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setErrorHandler(failure -> log.warn("Invitation delivery scheduling failed"));

            return scheduler;
        }

        @Bean
        InvitationMailSender invitationMailSender(
                JavaMailSender javaMailSender,
                InvitationDeliverySettings invitationDeliverySettings,
                @Value("${openstudy.invitation-mail.from:}") String from,
                @Value("${openstudy.invitation-mail.acceptance-url:}") String acceptanceUrl
        ) {
            validateTransport(javaMailSender, invitationDeliverySettings.leaseDuration());

            return new SmtpInvitationMailSender(javaMailSender, from, acceptanceUrl);
        }

        @Bean
        InvitationEmailWorker invitationEmailWorker(
                InvitationDeliveryService invitationDeliveryService,
                InvitationMailSender invitationMailSender,
                InvitationDeliverySettings invitationDeliverySettings
        ) {
            return new InvitationEmailWorker(
                    invitationDeliveryService,
                    invitationMailSender,
                    invitationDeliverySettings
            );
        }

        @Bean
        InvitationEmailScheduling invitationEmailScheduling(
                InvitationEmailWorker invitationEmailWorker,
                @Value("${openstudy.invitation-mail.poll-millis:1000}") long pollMillis
        ) {
            if (pollMillis < MIN_POLL_MILLIS || pollMillis > MAX_POLL_MILLIS) {
                throw new IllegalArgumentException("Invitation poll interval must be 100..300000 ms");
            }

            return new InvitationEmailScheduling(invitationEmailWorker);
        }
    }

    /** Fail startup on unbounded SMTP calls or protocol debug output that could reveal a token. */
    public static void validateTransport(JavaMailSender javaMailSender, Duration lease) {
        if (!(javaMailSender instanceof JavaMailSenderImpl smtp)
                || smtp.getHost() == null
                || smtp.getHost().isBlank()) {
            throw new IllegalArgumentException("A configured SMTP transport is required for invitation delivery");
        }

        String protocol = smtp.getProtocol() == null ? "smtp" : smtp.getProtocol();

        if (!"smtp".equals(protocol) && !"smtps".equals(protocol)) {
            throw new IllegalArgumentException("Invitation transport must use SMTP");
        }

        Session session = smtp.getSession();
        Properties properties = session.getProperties();
        boolean debugEnabled = session.getDebug()
                || Boolean.parseBoolean(properties.getProperty("mail.debug", "false"))
                || Boolean.parseBoolean(properties.getProperty("mail.debug.auth", "false"));

        if (debugEnabled) {
            throw new IllegalArgumentException("SMTP debug output must be disabled for invitation delivery");
        }

        String prefix = "mail." + protocol + ".";
        long budget = readTimeout(properties, prefix + "connectiontimeout", DEFAULT_CONNECTION_TIMEOUT_MILLIS)
                + readTimeout(properties, prefix + "timeout", DEFAULT_READ_TIMEOUT_MILLIS)
                + readTimeout(properties, prefix + "writetimeout", DEFAULT_WRITE_TIMEOUT_MILLIS);

        if (budget >= lease.toMillis()) {
            throw new IllegalArgumentException("SMTP timeout budget must be shorter than the invitation lease");
        }
    }

    /** Reads a timeout, writes the default back when unset, and rejects values outside 1..30000 ms. */
    private static long readTimeout(Properties properties, String key, long fallback) {
        try {
            long value = Long.parseLong(properties.getProperty(key, Long.toString(fallback)));

            if (value < 1 || value > MAX_TIMEOUT_MILLIS) {
                throw new NumberFormatException();
            }

            properties.setProperty(key, Long.toString(value));
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("SMTP timeouts must be finite and between 1 and 30000 ms");
        }
    }
}
