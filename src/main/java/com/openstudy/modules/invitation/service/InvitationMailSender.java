package com.openstudy.modules.invitation.service;

/** Transient send boundary. Never persist or log the token or the message body. */
@FunctionalInterface
public interface InvitationMailSender {
    void send(String email, String name, String token);
}
