package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

/** {@code task} stays null until #77 fills it per reader; body is the stored text, unchanged. */
public record MessageView(String id, String channelId, ActorSummary sender, String parentId, String kind, String body,
        Object task, String clientMessageKey, long replyCount, Instant sentAt) {}
