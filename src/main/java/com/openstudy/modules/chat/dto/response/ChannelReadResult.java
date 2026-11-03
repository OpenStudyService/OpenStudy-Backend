package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

/** The main feed's position; the counts are the channel's, as in {@link ChannelView}. */
public record ChannelReadResult(String lastReadMessageId, Instant lastReadAt, long unreadCount, long mainUnreadCount, long replyUnreadCount) {}
