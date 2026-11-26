package com.openstudy.modules.invitation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Created only when invitation-mail.enabled=true. Never logs provider exception messages. */
@Slf4j
@RequiredArgsConstructor
public final class InvitationEmailScheduling {

    private final InvitationEmailWorker invitationEmailWorker;

    @Scheduled(
            fixedDelayString = "${openstudy.invitation-mail.poll-millis:1000}",
            scheduler = "invitationMailTaskScheduler"
    )
    public void deliver() {
        try {
            invitationEmailWorker.runOnce();
        } catch (RuntimeException failure) {
            log.warn("Invitation delivery tick failed type={}", failure.getClass().getSimpleName());
        }
    }
}
