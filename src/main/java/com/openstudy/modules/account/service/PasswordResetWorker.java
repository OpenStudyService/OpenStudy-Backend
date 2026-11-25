package com.openstudy.modules.account.service;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.openstudy.modules.account.service.PasswordResetDelivery.Attempt;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;

/** Claims, sends and finishes reset mails one at a time, like the invitation mail worker. */
@RequiredArgsConstructor
public final class PasswordResetWorker {

    private final PasswordResetDelivery delivery;
    private final PasswordResetMailSender sender;
    private final InvitationDeliverySettings settings;

    public int runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Password reset worker requires a transaction-free caller");
        }

        String workerId = UUID.randomUUID().toString();
        int attempted = 0;

        for (int index = 0; index < settings.batchSize(); index++) {
            Optional<Attempt> next = delivery.claimNext(workerId);
            if (next.isEmpty()) {
                break;
            }

            Attempt attempt = next.get();
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
