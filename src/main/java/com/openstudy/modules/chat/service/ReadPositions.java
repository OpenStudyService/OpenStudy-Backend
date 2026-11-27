package com.openstudy.modules.chat.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.openstudy.modules.chat.entity.ConversationMember;
import com.openstudy.modules.chat.entity.ConversationThreadRead;
import com.openstudy.modules.chat.repository.ConversationThreadReadRepository;
import com.openstudy.modules.chat.repository.MessageRepository;

/**
 * What counts as read (FR-CHAT-06, M7-D08·D09). The main feed and every thread have their own position. An original is
 * unread past the member's position; a reply past the larger of the member's baseline and its thread's own position. A
 * reader's own messages never count, and sending one moves no position.
 */
@Component
@RequiredArgsConstructor
class ReadPositions {

    private final MessageRepository messageRepository;
    private final ConversationThreadReadRepository conversationThreadReadRepository;

    record Unread(long main, long replies) {

        static final Unread NONE = new Unread(0, 0);

        long total() {
            return main + replies;
        }
    }

    /** One thread for one reader. {@code position} is null while nothing in it counts as read. */
    record ThreadState(long unread, Long position) {
    }

    /** Counts of the channels the employee takes part in. A channel with nothing unread has no entry. */
    Map<Long, Unread> countUnread(long employee, Collection<Long> channels) {
        Map<Long, Long> main = toLongMap(messageRepository.countUnreadOriginals(employee, channels));
        Map<Long, Long> replies = toLongMap(messageRepository.countUnreadReplies(employee, channels));
        Map<Long, Unread> result = new HashMap<>();
        main.forEach((channel, count) -> result.put(channel, new Unread(count, replies.getOrDefault(channel, 0L))));
        replies.forEach((channel, count) -> result.putIfAbsent(channel, new Unread(0, count)));

        return result;
    }

    Unread countUnread(long employee, long channel) {
        return countUnread(employee, List.of(channel)).getOrDefault(channel, Unread.NONE);
    }

    /** Every given original has an entry. */
    Map<Long, ThreadState> findThreadStates(ConversationMember reader, Collection<Long> originals) {
        if (originals.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> unread = toLongMap(messageRepository.countUnreadByThread(
                reader.getEmployeeId(),
                reader.getConversationId(),
                originals
        ));
        Map<Long, Long> own = new HashMap<>();
        List<ConversationThreadRead> reads = conversationThreadReadRepository
                .findByConversationIdAndEmployeeIdAndParentMessageIdIn(
                        reader.getConversationId(),
                        reader.getEmployeeId(),
                        originals
                );

        for (ConversationThreadRead row : reads) {
            own.put(row.getParentMessageId(), row.getLastReadMessageId());
        }

        Map<Long, ThreadState> result = new HashMap<>();

        for (long original : originals) {
            ThreadState state = new ThreadState(
                    unread.getOrDefault(original, 0L),
                    calculatePosition(reader, own.get(original))
            );
            result.put(original, state);
        }

        return result;
    }

    /** The position a thread's replies compare with: its own, or the baseline if that is further along. */
    static Long calculatePosition(ConversationMember reader, Long own) {
        Long baseline = reader.getThreadReadBaselineMessageId();

        if (baseline == null) {
            return own;
        }

        return own == null ? baseline : Long.valueOf(Math.max(baseline, own));
    }

    private static Map<Long, Long> toLongMap(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();

        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        return result;
    }
}
