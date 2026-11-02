package com.openstudy.modules.chat.dto.response;

import java.util.List;

/** Oldest first. {@code nextCursor} is the oldest item's ID while older messages remain, otherwise null. */
public record MessageFeed(List<MessageView> items, boolean hasMore, String nextCursor) {}
