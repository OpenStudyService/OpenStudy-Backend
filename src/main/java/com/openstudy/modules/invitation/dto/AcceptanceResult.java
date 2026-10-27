package com.openstudy.modules.invitation.dto;

/** Acceptance never grants business access by itself (Q1); approval and, for new accounts, login follow. */
public record AcceptanceResult(String invitationId, String status, String nextAction) {}
