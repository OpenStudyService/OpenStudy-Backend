package com.openstudy.modules.inquiry.dto.response;

import java.time.Instant;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code Inquiry}: the list item plus the content and the current answer. */
public record InquiryDetail(String id, String branchId, CustomerSummary customer, String subject, String source, String status,
        ActorSummary assignee, ActorSummary createdBy, Instant answeredAt, boolean canChangeAssignee, long version,
        Instant createdAt, Instant updatedAt, String body, String answer, ActorSummary answeredBy) {}
