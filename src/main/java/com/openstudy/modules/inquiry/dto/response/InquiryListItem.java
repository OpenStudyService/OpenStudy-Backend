package com.openstudy.modules.inquiry.dto.response;

import java.time.Instant;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/**
 * Contract schema {@code InquiryListItem}. The customer is the minimum identification only (no phone, e-mail or memo).
 * canChangeAssignee only guides the screen; the command checks again.
 */
public record InquiryListItem(String id, String branchId, CustomerSummary customer, String subject, String source, String status,
        ActorSummary assignee, ActorSummary createdBy, Instant answeredAt, boolean canChangeAssignee, long version,
        Instant createdAt, Instant updatedAt) {}
