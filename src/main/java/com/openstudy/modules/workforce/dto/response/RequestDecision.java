package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code RequestDecision}: who approved or rejected a request, and when. */
public record RequestDecision(ActorSummary by, Instant at) {}
