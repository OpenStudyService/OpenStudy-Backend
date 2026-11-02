package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

public record ChannelReadResult(String lastReadMessageId, Instant lastReadAt, long unreadCount) {}
