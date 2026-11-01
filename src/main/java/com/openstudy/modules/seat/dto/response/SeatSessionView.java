package com.openstudy.modules.seat.dto.response;

import java.time.Instant;
import java.util.List;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code SeatSession}. The customer is the minimum summary whatever branch registered it (M5-D06). */
public record SeatSessionView(String id, long version, String status, String branchId, SeatRef seat, CustomerSummary customer,
        SeatSessionPass pass, Instant checkedInAt, Instant checkedOutAt, long usedMinutes, long deductedMinutes, long deductedKrw,
        long overageMinutes, long overageKrw, Long passRemainingMinutes, Long passRemainingCreditKrw, ActorSummary checkedInBy,
        String checkInReason, List<SeatMove> moves) {}
