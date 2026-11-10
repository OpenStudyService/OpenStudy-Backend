package com.openstudy.modules.customer.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code Customer}. canEdit is a display hint; every write re-checks authorization. {@code mergedInto} names
 * the customer a 병합됨 customer was merged into, and {@code absorbedCustomerCount} counts the customers merged into this
 * one (M13-D03·D07, #114).
 */
public record CustomerDetail(String id, long version, String memberNo, String name, String phone, String email,
        String memo, String status, String statusReason, String registeredBranchId, Instant registeredAt,
        Instant updatedAt, boolean canEdit, CustomerSummary mergedInto, long absorbedCustomerCount) {}
