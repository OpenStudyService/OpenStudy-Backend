package com.openstudy.modules.locker.dto.response;

import java.util.List;

/** Contract schema {@code LockerOccupancy}: one item per branch in scope, by branch id. */
public record LockerOccupancy(List<LockerBranchOccupancy> items) {}
