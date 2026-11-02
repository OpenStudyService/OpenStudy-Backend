package com.openstudy.modules.inquiry.service;

import com.openstudy.modules.inquiry.entity.InquiryStatus;

/** Validated list and summary conditions. Shared so the two cannot accept different filters. */
public record InquiryFilters(InquiryStatus status, Long assignee, Long customer) {
    public static InquiryFilters parse(String status, String assigneeId, String customerId) {
        return new InquiryFilters(status == null ? null : InquiryInputs.status(status, "status"),
                assigneeId == null ? null : InquiryInputs.id(assigneeId, "assigneeId"),
                customerId == null ? null : InquiryInputs.id(customerId, "customerId"));
    }
}
