package com.openstudy.modules.branch.dto.response;

import java.time.LocalDate;

/** Contract schema {@code HomeClosureItem}: one closure the home summary announces. */
public record ClosureNotice(String branchId, String branchName, LocalDate date, String note, boolean today) {}
