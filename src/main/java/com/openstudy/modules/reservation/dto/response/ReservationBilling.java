package com.openstudy.modules.reservation.dto.response;

/** Contract schema {@code ReservationBilling}: the booking's snapshot and its payment, for payment readers only. */
public record ReservationBilling(long ratePerHourKrw, int hours, long amountKrw, String paymentId, String paymentStatus) {}
