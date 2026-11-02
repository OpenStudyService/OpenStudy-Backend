package com.openstudy.modules.notice.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Contract schema {@code NoticeListItem}. {@code branchIds} holds only the target branches the requester can see and is
 * empty for a notice addressed to everyone. canEdit only guides the screen; every command checks again.
 */
public record NoticeListItem(String id, String title, ActorSummary author, String target, List<String> branchIds, String status,
        boolean pinned, Instant publishedAt, boolean canEdit, long version, Instant createdAt, Instant updatedAt) {}
