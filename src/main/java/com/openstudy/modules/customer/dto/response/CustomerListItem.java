package com.openstudy.modules.customer.dto.response;

import java.time.Instant;

/** Only what a search result needs (FR-MEM-05): email, memo and status reason stay on the detail view. */
public record CustomerListItem(String id, String memberNo, String name, String phone, String status,
        String registeredBranchId, Instant registeredAt) {}
