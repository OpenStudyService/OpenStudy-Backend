package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

/**
 * {@code lastReadMessageId} is the position the thread's replies compare with, the same value as the original's
 * {@code threadLastReadMessageId}. {@code lastReadAt} is null while the baseline alone covers the thread.
 * {@code threadUnreadCount} is this thread; the other three counts are the channel's, as in {@link ChannelView}.
 */
public record ThreadReadResult(String messageId, String lastReadMessageId, Instant lastReadAt, long threadUnreadCount,
        long unreadCount, long mainUnreadCount, long replyUnreadCount) {}
