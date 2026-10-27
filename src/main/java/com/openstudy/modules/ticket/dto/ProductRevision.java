package com.openstudy.modules.ticket.dto;

import tools.jackson.databind.JsonNode;

/** {@code before}는 직전 revision의 스냅샷이고 최초 이력은 null이다. 저장된 스냅샷 JSON을 그대로 전달한다. */
public record ProductRevision(String id, String productId, long version, String changedAt, String actorId,
        String changeType, JsonNode before, JsonNode after) {}
