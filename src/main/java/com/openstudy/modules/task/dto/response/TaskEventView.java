package com.openstudy.modules.task.dto.response;

import java.time.Instant;
import java.util.Map;

/** Contract schema {@code TaskEvent}. */
public record TaskEventView(String id, String type, ActorSummary actor, Map<String, Object> before,
        Map<String, Object> after, Instant at) {}
