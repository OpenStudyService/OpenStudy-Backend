package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code RequestCancellation}: 신청 취소 by the requester or 승인 취소 by a manager. */
public record RequestCancellation(String kind, ActorSummary by, Instant at) {}
