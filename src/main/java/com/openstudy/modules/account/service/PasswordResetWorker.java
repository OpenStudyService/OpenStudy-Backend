package com.openstudy.modules.account.service;

import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.openstudy.modules.invitation.service.InvitationDeliverySettings;

/** Claims, sends and finishes reset mails one at a time, like the invitation mail worker. */
public final class PasswordResetWorker {
    private final PasswordResetDelivery delivery;
    private final PasswordResetMailSender sender;
    private final InvitationDeliverySettings settings;

    public PasswordResetWorker(PasswordResetDelivery delivery, PasswordResetMailSender sender, InvitationDeliverySettings settings) {
        this.delivery = delivery;
        this.sender = sender;
        this.settings = settings;
    }

    public int runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Password reset worker requires a transaction-free caller");
        String workerId = UUID.randomUUID().toString();
        int attempted = 0;
        for (int index = 0; index < settings.batchSize(); index++) {
            var next = delivery.claimNext(workerId);
            if (next.isEmpty()) break;
            var attempt = next.get();
            attempted++;
            try {
                sender.send(attempt.email(), attempt.name(), attempt.token());
            } catch (RuntimeException failure) {
                // Transport exception messages can contain the whole message or the recipient.
                delivery.failed(attempt);
                continue;
            }
            // If this write fails after SMTP accepted the mail, the lease runs out and a new link replaces the sent one.
            delivery.succeeded(attempt);
        }
        return attempted;
    }
}
