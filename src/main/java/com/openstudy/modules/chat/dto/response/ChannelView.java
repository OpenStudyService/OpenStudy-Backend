package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

/**
 * To a manager who has not joined, the counts are 0 and lastMessageAt and lastReadMessageId are null.
 * {@code unreadCount} is {@code mainUnreadCount + replyUnreadCount}: unread originals plus unread replies of every thread.
 * lastReadMessageId is for comparing IDs only: a position from before the threads split, or set by joining again, can be
 * a reply's ID.
 */
public record ChannelView(String id, String kind, String branchId, String name, String description, long memberCount,
        boolean joined, long unreadCount, long mainUnreadCount, long replyUnreadCount, String lastReadMessageId,
        Instant lastMessageAt, boolean canManage, long version, Instant createdAt) {}
