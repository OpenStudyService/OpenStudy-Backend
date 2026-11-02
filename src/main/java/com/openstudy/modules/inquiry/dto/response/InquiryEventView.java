package com.openstudy.modules.inquiry.dto.response;

import java.time.Instant;

/** Contract schema {@code InquiryEvent}. {@code oldStatus} is null only for the registration. */
public record InquiryEventView(String id, ActorSummary actor, String oldStatus, String newStatus, String message, Instant at) {}
