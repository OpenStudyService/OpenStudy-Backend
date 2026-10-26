package com.openstudy.modules.invitation.service;

import java.time.Instant;

/** Transient handoff after the claim commits. Never serialize or persist this object. */
public final class DeliveryAttempt {
    private final long invitationId;
    private final long jobId;
    private final long revision;
    private final String workerId;
    private final String email;
    private final String name;
    private final String token;
    private final Instant leaseUntil;

    DeliveryAttempt(long invitationId, long jobId, long revision, String workerId, String email,
            String name, String token, Instant leaseUntil) {
        this.invitationId = invitationId;
        this.jobId = jobId;
        this.revision = revision;
        this.workerId = workerId;
        this.email = email;
        this.name = name;
        this.token = token;
        this.leaseUntil = leaseUntil;
    }

    public long invitationId() { return invitationId; }
    public long jobId() { return jobId; }
    public long revision() { return revision; }
    public String workerId() { return workerId; }
    public String email() { return email; }
    public String name() { return name; }
    public String token() { return token; }
    public Instant leaseUntil() { return leaseUntil; }

    @Override
    public String toString() {
        return "DeliveryAttempt[invitationId=" + invitationId + ",jobId=" + jobId + ",revision=" + revision + "]";
    }
}
