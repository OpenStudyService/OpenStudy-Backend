package com.openstudy.modules.customer.dto.response;

/** A customer found by an exact member number or phone (M5-D05): the minimum identification and the status label. */
public record ExactCustomerMatch(long id, CustomerSummary customer, String status) {}
