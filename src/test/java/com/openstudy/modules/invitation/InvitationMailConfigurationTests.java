package com.openstudy.modules.invitation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.openstudy.modules.invitation.service.InvitationDeliveryService;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;
import com.openstudy.modules.invitation.service.InvitationEmailScheduling;
import com.openstudy.modules.invitation.service.InvitationEmailWorker;
import com.openstudy.modules.invitation.service.InvitationMailConfiguration;
import com.openstudy.modules.invitation.service.InvitationMailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InvitationMailConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InvitationMailConfiguration.class);

    @Test void deliveryAndItsSchedulersAreOffByDefaultWithoutSmtpConfiguration() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(InvitationDeliverySettings.class)
                    .doesNotHaveBean(InvitationEmailWorker.class).doesNotHaveBean(InvitationMailSender.class)
                    .doesNotHaveBean(InvitationEmailScheduling.class).doesNotHaveBean("invitationMailTaskScheduler");
        });
    }

    @Test void invalidBatchAndLeaseSettingsFailStartupInsteadOfRunningUnboundedWork() {
        for (String property : new String[] {"openstudy.invitation-mail.batch-size=0", "openstudy.invitation-mail.max-attempts=0",
                "openstudy.invitation-mail.lease-seconds=0", "openstudy.invitation-mail.retry-delay-seconds=-1"}) {
            context.withPropertyValues(property).run(application -> assertThat(application).hasFailed());
        }
    }

    @Test void validEnabledConfigurationUsesSeparateMailAndWorkspaceSchedulers() {
        enabled(smtp()).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(InvitationMailSender.class)
                    .hasSingleBean(InvitationEmailWorker.class).hasSingleBean(InvitationEmailScheduling.class);
            var workspace = application.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var invitation = application.getBean("invitationMailTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(invitation).isNotSameAs(workspace);
            assertThat(workspace.getThreadNamePrefix()).isEqualTo("workspace-scheduling-");
            assertThat(invitation.getThreadNamePrefix()).isEqualTo("invitation-mail-");
            assertThat(application.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(2);
        });
    }

    @Test void enablingDeliveryRequiresExplicitValidSenderAndAcceptanceRoute() {
        enabled(new JavaMailSenderImpl()).run(application -> assertThat(application).hasFailed());
        var transport = smtp();
        enabled(transport).withPropertyValues("openstudy.invitation-mail.acceptance-url=https://example.test/not-the-acceptance-route")
                .run(application -> assertThat(application).hasFailed());
        enabled(smtp()).withPropertyValues("openstudy.invitation-mail.poll-millis=0")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test void enabledTransportRejectsDebugUnboundedTimeoutsAndBudgetBeyondLease() {
        for (String property : new String[] {"mail.debug=true", "mail.debug.auth=true", "mail.smtp.timeout=0",
                "mail.smtp.connectiontimeout=-1", "mail.smtp.writetimeout=not-a-number"}) {
            var transport = smtp();
            int split = property.indexOf('=');
            transport.getJavaMailProperties().setProperty(property.substring(0, split), property.substring(split + 1));
            enabled(transport).run(application -> assertThat(application).hasFailed());
        }
        enabled(smtp()).withPropertyValues("openstudy.invitation-mail.lease-seconds=20")
                .run(application -> assertThat(application).hasFailed());
    }

    private ApplicationContextRunner enabled(JavaMailSender transport) {
        return context.withBean(JavaMailSender.class, () -> transport)
                .withBean(InvitationDeliveryService.class, () -> mock(InvitationDeliveryService.class))
                .withPropertyValues("openstudy.invitation-mail.enabled=true", "openstudy.invitation-mail.from=invitations@example.test",
                        "openstudy.invitation-mail.acceptance-url=https://example.test/invitations/accept");
    }
    private static JavaMailSenderImpl smtp() {
        var mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1");
        mail.setPort(9);
        return mail;
    }
}
