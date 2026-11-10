package com.openstudy.modules.account.service;

/** Transient send boundary. Never persist or log the token or the message body. */
@FunctionalInterface
public interface PasswordResetMailSender {
    void send(String email, String name, String token);
}
