package com.openstudy.modules.sales.dto.response;

/** The totals object of contract schema {@code SalesTrend}: the measure's sum, the other side null. */
public record SalesTrendTotals(RevenueAmounts revenue, PassVolume passes) {}
