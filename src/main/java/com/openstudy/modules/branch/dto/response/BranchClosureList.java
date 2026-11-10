package com.openstudy.modules.branch.dto.response;

import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code BranchClosureList}: date order, unpaged. */
public record BranchClosureList(String branchId, LocalDate from, LocalDate to, List<BranchClosureView> items) {}
