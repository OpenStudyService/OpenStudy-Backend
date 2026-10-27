package com.openstudy.modules.invitation.service;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit coordinator; parent configuration enables automatic SMTP dispatch only when requested. */
public final class InvitationEmailWorker {
    private final InvitationDeliveryService delivery;
    private final InvitationMailSender sender;
    private final InvitationDeliverySettings settings;

    public InvitationEmailWorker(InvitationDeliveryService delivery, InvitationMailSender sender, InvitationDeliverySettings settings) {
        this.delivery = delivery;
        this.sender = sender;
        this.settings = settings;
    }

    public int runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invitation email worker requires a transaction-free caller");
        }
        int attempted = 0;
        String workerId = UUID.randomUUID().toString();
        for (int index = 0; index < settings.batchSize(); index++) {
            var next = delivery.claimNext(workerId);
            if (next.isEmpty()) break;
            var attempt = next.get();
            if (!delivery.stillCurrent(attempt)) continue;
            attempted++;
            try {
                sender.send(attempt.email(), attempt.name(), attempt.token());
            } catch (RuntimeException failure) {
                // Transport exception messages can contain the entire MIME message or recipient.
                delivery.failed(attempt);
                continue;
            }
            // If this DB write fails after SMTP accepted the message, leave the persisted lease for
            // recovery. A later revision invalidates the uncertain link rather than resending it.
            delivery.succeeded(attempt);
        }
        return attempted;
    }
}
