package com.openstudy.modules.sales.dto.response;

/** Contract schema {@code AggregationPartial}. The bucket flags are null outside a trend. */
public record AggregationPartial(boolean periodOpen, Boolean firstBucketPartial, Boolean lastBucketPartial) {}
