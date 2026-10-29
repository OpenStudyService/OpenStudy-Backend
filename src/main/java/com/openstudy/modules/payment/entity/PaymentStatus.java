package com.openstudy.modules.payment.entity;

/** PENDING stays in the V1 enum; a Mock payment is decided within the request and never stored as PENDING. */
public enum PaymentStatus { PENDING, APPROVED, FAILED, REFUNDED }
