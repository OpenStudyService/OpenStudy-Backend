package com.openstudy.modules.customer.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code CustomerDuplicateGroup}: customers that share one phone and are not merged (M13-D13). */
public record CustomerDuplicateGroup(String phone, List<Member> customers) {

    /** Contract schema {@code DuplicateCustomer}. */
    public record Member(String id, String memberNo, String name, String status, String registeredBranchId, Instant registeredAt,
            long paymentCount, Instant lastActivityAt) {}
}
