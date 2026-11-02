package com.openstudy.modules.locker.dto.response;

import java.time.LocalDate;
import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code LockerActiveAssignment}: the assignment occupying the locker today. */
public record LockerActiveAssignment(String id, CustomerSummary customer, LocalDate startDate, LocalDate endDate, boolean overdue) {}
