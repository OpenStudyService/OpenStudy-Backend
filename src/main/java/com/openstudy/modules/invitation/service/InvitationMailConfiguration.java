package com.openstudy.modules.invitation.service;

import java.time.Duration;
import java.util.Properties;
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
    @Bean
    @ConditionalOnMissingBean
    InvitationDeliverySettings invitationDeliverySettings(
            @Value("${openstudy.invitation-mail.batch-size:10}") int batchSize,
            @Value("${openstudy.invitation-mail.lease-seconds:60}") long leaseSeconds,
            @Value("${openstudy.invitation-mail.retry-delay-seconds:60}") long retrySeconds,
            @Value("${openstudy.invitation-mail.max-attempts:5}") int maxAttempts) {
        return new InvitationDeliverySettings(batchSize, Duration.ofSeconds(leaseSeconds),
                Duration.ofSeconds(retrySeconds), maxAttempts);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "openstudy.invitation-mail.enabled", havingValue = "true")
    static class Enabled {
        @Bean(name = "taskScheduler")
        @ConditionalOnMissingBean(name = "taskScheduler")
        ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("workspace-scheduling-");
            scheduler.setDaemon(true);
            return scheduler;
        }

        @Bean(name = "invitationMailTaskScheduler")
        ThreadPoolTaskScheduler invitationMailTaskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("invitation-mail-");
            scheduler.setDaemon(true);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(InvitationEmailScheduling.class)
                    .warn("Invitation delivery scheduling failed"));
            return scheduler;
        }

        @Bean
        InvitationMailSender invitationMailSender(JavaMailSender sender, InvitationDeliverySettings settings,
                @Value("${openstudy.invitation-mail.from:}") String from,
                @Value("${openstudy.invitation-mail.acceptance-url:}") String acceptanceUrl) {
            validateTransport(sender, settings.leaseDuration());
            return new SmtpInvitationMailSender(sender, from, acceptanceUrl);
        }

        @Bean
        InvitationEmailWorker invitationEmailWorker(InvitationDeliveryService delivery,
                InvitationMailSender sender, InvitationDeliverySettings settings) {
            return new InvitationEmailWorker(delivery, sender, settings);
        }

        @Bean
        InvitationEmailScheduling invitationEmailScheduling(InvitationEmailWorker worker,
                @Value("${openstudy.invitation-mail.poll-millis:1000}") long pollMillis) {
            if (pollMillis < 100 || pollMillis > 300_000) {
                throw new IllegalArgumentException("Invitation poll interval must be 100..300000 ms");
            }
            return new InvitationEmailScheduling(worker);
        }
    }

    /** Fail startup on unbounded SMTP calls or protocol debug output that could reveal a token. */
    static void validateTransport(JavaMailSender sender, Duration lease) {
        if (!(sender instanceof JavaMailSenderImpl smtp) || smtp.getHost() == null || smtp.getHost().isBlank()) {
            throw new IllegalArgumentException("A configured SMTP transport is required for invitation delivery");
        }
        String protocol = smtp.getProtocol() == null ? "smtp" : smtp.getProtocol();
        if (!"smtp".equals(protocol) && !"smtps".equals(protocol)) {
            throw new IllegalArgumentException("Invitation transport must use SMTP");
        }
        var session = smtp.getSession();
        var properties = session.getProperties();
        if (session.getDebug() || Boolean.parseBoolean(properties.getProperty("mail.debug", "false"))
                || Boolean.parseBoolean(properties.getProperty("mail.debug.auth", "false"))) {
            throw new IllegalArgumentException("SMTP debug output must be disabled for invitation delivery");
        }
        String prefix = "mail." + protocol + ".";
        long budget = timeout(properties, prefix + "connectiontimeout", 5_000)
                + timeout(properties, prefix + "timeout", 10_000)
                + timeout(properties, prefix + "writetimeout", 10_000);
        if (budget >= lease.toMillis()) throw new IllegalArgumentException("SMTP timeout budget must be shorter than the invitation lease");
    }

    private static long timeout(Properties properties, String key, long fallback) {
        try {
            long value = Long.parseLong(properties.getProperty(key, Long.toString(fallback)));
            if (value < 1 || value > 30_000) throw new NumberFormatException();
            properties.setProperty(key, Long.toString(value));
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("SMTP timeouts must be finite and between 1 and 30000 ms");
        }
    }
}
