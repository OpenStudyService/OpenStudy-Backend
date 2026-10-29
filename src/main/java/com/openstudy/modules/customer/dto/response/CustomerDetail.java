package com.openstudy.modules.customer.dto.response;

import java.time.Instant;

/** Contract schema {@code Customer}. canEdit is a display hint; every write re-checks authorization. */
public record CustomerDetail(String id, long version, String memberNo, String name, String phone, String email,
        String memo, String status, String statusReason, String registeredBranchId, Instant registeredAt,
        Instant updatedAt, boolean canEdit) {}
