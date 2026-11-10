package com.openstudy.modules.customer.dto.response;

import java.util.List;

/** Contract schema {@code CustomerMergeList}: newest first, unpaged. */
public record CustomerMergeList(List<CustomerMergeRecord> items) {}
