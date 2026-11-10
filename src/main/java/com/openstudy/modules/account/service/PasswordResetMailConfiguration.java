package com.openstudy.modules.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.openstudy.modules.account.adapter.SmtpPasswordResetMailSender;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;
import com.openstudy.modules.invitation.service.InvitationMailConfiguration;

/**
 * Reset mail goes out through the invitation SMTP setup and is switched on with it (openstudy.invitation-mail.enabled). Then
 * openstudy.password-reset.url is required: startup fails without it, because a reset makes the old password stop working
 * at once (M14-D11) and a request nobody can deliver would lock the employee out.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "openstudy.invitation-mail.enabled", havingValue = "true")
public class PasswordResetMailConfiguration {
    @Bean(name = "passwordResetMailTaskScheduler")
    ThreadPoolTaskScheduler passwordResetMailTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("password-reset-mail-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(failure -> LoggerFactory.getLogger(Scheduling.class).warn("Password reset delivery scheduling failed"));
        return scheduler;
    }

    @Bean
    PasswordResetMailSender passwordResetMailSender(JavaMailSender sender, InvitationDeliverySettings settings,
            @Value("${openstudy.invitation-mail.from:}") String from, @Value("${openstudy.password-reset.url:}") String resetUrl) {
        InvitationMailConfiguration.validateTransport(sender, settings.leaseDuration());
        return new SmtpPasswordResetMailSender(sender, from, resetUrl);
    }

    @Bean
    PasswordResetWorker passwordResetWorker(PasswordResetDelivery delivery, PasswordResetMailSender sender,
            InvitationDeliverySettings settings) {
        return new PasswordResetWorker(delivery, sender, settings);
    }

    @Bean
    Scheduling passwordResetScheduling(PasswordResetWorker worker) { return new Scheduling(worker); }

    /** Polls with the invitation interval. Never logs provider exception messages. */
    public static final class Scheduling {
        private static final Logger log = LoggerFactory.getLogger(Scheduling.class);
        private final PasswordResetWorker worker;

        Scheduling(PasswordResetWorker worker) { this.worker = worker; }

        @Scheduled(fixedDelayString = "${openstudy.invitation-mail.poll-millis:1000}", scheduler = "passwordResetMailTaskScheduler")
        public void deliver() {
            try {
                worker.runOnce();
            } catch (RuntimeException failure) {
                log.warn("Password reset delivery tick failed type={}", failure.getClass().getSimpleName());
            }
        }
    }
}
