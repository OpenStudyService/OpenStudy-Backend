package com.openstudy.modules.locker.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/** Contract schema {@code LockerCharge}: the snapshot of one payment. */
public record LockerChargeView(String id, String kind, LocalDate startDate, LocalDate endDate, int terms, long ratePer4WeeksKrw,
        long amountKrw, String status, String paymentId, Instant createdAt, Instant appliedAt) {}
