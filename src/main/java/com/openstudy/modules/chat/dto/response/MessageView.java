package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

import com.openstudy.modules.task.dto.response.TaskCardView;

/** {@code task} is null for a text and the reader's own view of the task for a card; body is the stored text, unchanged. */
public record MessageView(String id, String channelId, ActorSummary sender, String parentId, String kind, String body,
        TaskCardView task, String clientMessageKey, long replyCount, Instant sentAt) {}
