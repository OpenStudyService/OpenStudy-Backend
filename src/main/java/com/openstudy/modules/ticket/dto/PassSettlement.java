package com.openstudy.modules.ticket.dto;

/**
 * What one seat session cost its pass (seat-usage-api.md "계산 규칙"). usedMinutes is every minute of the stay; the
 * deducted fields are what the balance really lost, and the overage fields what it could not cover.
 */
public record PassSettlement(long usedMinutes, long deductedMinutes, long deductedKrw, long overageMinutes, long overageKrw) {}
