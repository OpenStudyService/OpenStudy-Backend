package com.openstudy.modules.invitation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Created only when invitation-mail.enabled=true. Never logs provider exception messages. */
public final class InvitationEmailScheduling {
    private static final Logger log = LoggerFactory.getLogger(InvitationEmailScheduling.class);
    private final InvitationEmailWorker worker;
    public InvitationEmailScheduling(InvitationEmailWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${openstudy.invitation-mail.poll-millis:1000}", scheduler = "invitationMailTaskScheduler")
    public void deliver() {
        try { worker.runOnce(); }
        catch (RuntimeException failure) {
            log.warn("Invitation delivery tick failed type={}", failure.getClass().getSimpleName());
        }
    }
}
