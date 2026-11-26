package com.openstudy.modules.invitation.service;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit coordinator; parent configuration enables automatic SMTP dispatch only when requested. */
@RequiredArgsConstructor
public final class InvitationEmailWorker {

    private final InvitationDeliveryService invitationDeliveryService;
    private final InvitationMailSender invitationMailSender;
    private final InvitationDeliverySettings invitationDeliverySettings;

    public int runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invitation email worker requires a transaction-free caller");
        }

        int attempted = 0;
        String workerId = UUID.randomUUID().toString();

        for (int index = 0; index < invitationDeliverySettings.batchSize(); index++) {
            Optional<DeliveryAttempt> next = invitationDeliveryService.claimNext(workerId);

            if (next.isEmpty()) {
                break;
            }

            DeliveryAttempt attempt = next.get();

            if (!invitationDeliveryService.stillCurrent(attempt)) {
                continue;
            }

            attempted++;

            try {
                invitationMailSender.send(attempt.email(), attempt.name(), attempt.token());
            } catch (RuntimeException failure) {
                // Transport exception messages can contain the entire MIME message or recipient.
                invitationDeliveryService.failed(attempt);
                continue;
            }

            // If this DB write fails after SMTP accepted the message, leave the persisted lease for
            // recovery. A later revision invalidates the uncertain link rather than resending it.
            invitationDeliveryService.succeeded(attempt);
        }

        return attempted;
    }
}
