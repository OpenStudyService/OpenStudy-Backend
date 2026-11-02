package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

/** To a manager who has not joined, unreadCount is 0 and lastMessageAt and lastReadMessageId are null. */
public record ChannelView(String id, String kind, String branchId, String name, String description, long memberCount,
        boolean joined, long unreadCount, String lastReadMessageId, Instant lastMessageAt, boolean canManage, long version,
        Instant createdAt) {}
