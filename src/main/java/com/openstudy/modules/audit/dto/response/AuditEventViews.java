package com.openstudy.modules.audit.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.JsonNode;

import com.openstudy.common.web.PageResponse;

/** Response bodies of the audit search (account-collab-api.md "감사 로그 검색·상세"). IDs are decimal strings. */
public final class AuditEventViews {
    private AuditEventViews() {}

    public record Ref(String id, String name) {}

    public record Item(String id, Instant occurredAt, String action, String result, String entityType, String entityId,
            String reason, String requestId, Ref actor, Ref branch, boolean hasChanges) {}

    public record Detail(String id, Instant occurredAt, String action, String result, String entityType, String entityId,
            String reason, String requestId, Ref actor, Ref branch, boolean hasChanges, JsonNode before, JsonNode after) {}

    public record Period(LocalDate from, LocalDate to) {}

    public record Page(List<Item> items, PageResponse.Meta meta, Period period) {
        public Page { items = List.copyOf(items); }
    }
}
