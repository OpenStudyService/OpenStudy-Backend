package com.openstudy.modules.chat.dto.response;

import java.time.Instant;

import com.openstudy.modules.task.dto.response.TaskCardView;

/**
 * {@code task} is null for a text and the reader's own view of the task for a card; body is the stored text, unchanged.
 * The two thread fields are this reader's and belong to an original: a reply has null in both. threadLastReadMessageId is
 * the position the thread's replies compare with, not necessarily one of them; null means none of them has been read.
 */
public record MessageView(String id, String channelId, ActorSummary sender, String parentId, String kind, String body,
        TaskCardView task, String clientMessageKey, long replyCount, Long threadUnreadCount, String threadLastReadMessageId,
        Instant sentAt) {}
