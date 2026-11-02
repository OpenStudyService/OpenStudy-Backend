package com.openstudy.modules.notice.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code Notice}: the list item plus the body. */
public record NoticeDetail(String id, String title, ActorSummary author, String target, List<String> branchIds, String status,
        boolean pinned, Instant publishedAt, boolean canEdit, long version, Instant createdAt, Instant updatedAt, String body) {}
