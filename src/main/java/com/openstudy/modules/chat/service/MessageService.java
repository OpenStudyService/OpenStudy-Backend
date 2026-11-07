package com.openstudy.modules.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.chat.dto.response.ActorSummary;
import com.openstudy.modules.chat.dto.response.ChannelReadResult;
import com.openstudy.modules.chat.dto.response.MessageFeed;
import com.openstudy.modules.chat.dto.response.MessageView;
import com.openstudy.modules.chat.dto.response.ThreadReadResult;
import com.openstudy.modules.chat.entity.ConversationMember;
import com.openstudy.modules.chat.entity.ConversationThreadRead;
import com.openstudy.modules.chat.entity.ConversationThreadReadId;
import com.openstudy.modules.chat.entity.Message;
import com.openstudy.modules.chat.entity.MessageKind;
import com.openstudy.modules.chat.repository.ConversationMemberRepository;
import com.openstudy.modules.chat.repository.ConversationRepository;
import com.openstudy.modules.chat.repository.ConversationThreadReadRepository;
import com.openstudy.modules.chat.repository.MessageRepository;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.task.service.TaskCardService;

/**
 * Messages, threads and the read positions (FR-CHAT-02·04~07). Every path first asks {@link ChannelAccess#participation}:
 * a non-participant gets the same 404 for a channel, a message and a reply, a manager included, and before any input is
 * looked at. Sends and read positions go through the guard, which is also what makes IDs follow commit order: a reader
 * can never pass a message that commits later with a smaller ID. The body is stored and returned as sent. A read
 * position concerns only its reader: moving one writes no audit row.
 */
@Service
@RequiredArgsConstructor
public class MessageService {
    private final ConversationRepository channels;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final ConversationThreadReadRepository threadReads;
    private final ReadPositions reads;
    private final ChannelAccess access;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final TaskCardService taskCards;
    private final Clock clock;

    public record Sent(MessageView message, boolean created) {}

    @Transactional(readOnly = true)
    public MessageFeed list(long actor, long channel, String before, String limit) {
        var state = policy.current(actor);
        var member = access.participation(state, channel);
        int size = ChannelInputs.limit(limit);
        return feed(messages.originals(channel, ChannelInputs.before(before), Limit.of(size + 1)), size, state, member);
    }

    @Transactional(readOnly = true)
    public MessageView get(long actor, long channel, long id) {
        var state = policy.current(actor);
        var member = access.participation(state, channel);
        return view(found(channel, id), state, member);
    }

    /** The target has to be a message of this channel. Asked of a reply, the answer is an empty feed. */
    @Transactional(readOnly = true)
    public MessageFeed replies(long actor, long channel, long id, String before, String limit) {
        var state = policy.current(actor);
        var member = access.participation(state, channel);
        found(channel, id);
        int size = ChannelInputs.limit(limit);
        return feed(messages.replies(channel, id, ChannelInputs.before(before), Limit.of(size + 1)), size, state, member);
    }

    /**
     * Under the guard a repeat normally finds the stored row. A repeat that still reaches the insert loses to
     * uk_messages_1 and rolls back, and the retry then answers from the row that won, by the same rules.
     */
    public Sent send(long actor, long channel, Map<String, Object> body) {
        return store(actor, channel, body, false);
    }

    /**
     * A card is a message with the same key rules, so it shares this path. The task is checked for the sender after the
     * input and before the key: a task the sender cannot open is 404 even for a repeat, and it says nothing more.
     */
    public Sent shareTaskCard(long actor, long channel, Map<String, Object> body) {
        return store(actor, channel, body, true);
    }

    private Sent store(long actor, long channel, Map<String, Object> body, boolean card) {
        try {
            return attempt(actor, channel, body, card);
        } catch (DuplicateKeyException exception) {
            return attempt(actor, channel, body, card);
        }
    }

    private Sent attempt(long actor, long channel, Map<String, Object> body, boolean card) {
        return commands.activeEmployee(actor, () -> channels.lock(channel), state -> {
            var member = access.participation(state, channel);
            var input = card ? new StrictInput(body).only("clientMessageKey", "taskId", "text", "parentId")
                    : new StrictInput(body).only("clientMessageKey", "text", "parentId");
            String key = ChannelInputs.clientKey(body.get("clientMessageKey"));
            Long task = card ? input.id("taskId") : null;
            String text = card && !body.containsKey("text") ? null : ChannelInputs.messageText(body.get("text"));
            Long parent = body.get("parentId") == null ? null : Long.valueOf(input.id("parentId"));
            if (card && !taskCards.accessible(state, task)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (parent != null && found(channel, parent).getParentMessageId() != null)
                throw ApiException.unprocessable("INVALID_PARENT_MESSAGE", "답글에는 다시 답글을 달 수 없습니다.");
            var prior = messages.findByConversationIdAndSenderEmployeeIdAndClientMessageKey(channel, actor, key);
            if (prior.isPresent()) {
                if (!sameInput(prior.get(), task, text, parent))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return new Sent(view(prior.get(), state, member), false);
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var row = card ? Message.taskCard(channel, actor, parent, task, text, key, now) : Message.text(channel, actor, parent, text, key, now);
            var saved = DuplicateKeys.translate(() -> messages.saveAndFlush(row));
            return new Sent(view(saved, state, member), true);
        });
    }

    /** The main feed's position, so only an original can be it. Replies keep their own positions. */
    public ChannelReadResult markRead(long actor, long channel, Map<String, Object> body) {
        return commands.activeEmployee(actor, () -> channels.lock(channel), state -> {
            var member = access.participation(state, channel);
            long message = new StrictInput(body).only("messageId").id("messageId");
            if (found(channel, message).getParentMessageId() != null) throw invalidReadTarget("본문 읽음 위치에는 원문만 지정할 수 있습니다.");
            member.advanceReadPosition(message, clock.instant().truncatedTo(ChronoUnit.MICROS));
            members.saveAndFlush(member);
            var unread = reads.unread(actor, channel);
            return new ChannelReadResult(member.getLastReadMessageId() == null ? null : member.getLastReadMessageId().toString(),
                    member.getLastReadAt(), unread.total(), unread.main(), unread.replies());
        });
    }

    /**
     * One thread's position. It moves only past what already counts as read, so a reply the baseline covers writes
     * nothing. The channel lock makes reading and writing the row one step: the first row of a thread cannot be inserted
     * twice, and a slower request for an earlier reply finds the further position.
     */
    public ThreadReadResult markThreadRead(long actor, long channel, long original, Map<String, Object> body) {
        return commands.activeEmployee(actor, () -> channels.lock(channel), state -> {
            var member = access.participation(state, channel);
            long reply = new StrictInput(body).only("replyId").id("replyId");
            if (found(channel, original).getParentMessageId() != null) throw invalidReadTarget("스레드 읽음 위치는 원문에만 둘 수 있습니다.");
            if (!Objects.equals(found(channel, reply).getParentMessageId(), original)) throw invalidReadTarget("이 스레드의 답글이 아닙니다.");
            var row = threadReads.findById(new ConversationThreadReadId(channel, actor, original)).orElse(null);
            Long current = ReadPositions.position(member, row == null ? null : row.getLastReadMessageId());
            if (current == null || reply > current) {
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (row == null) row = new ConversationThreadRead(channel, actor, original, reply, now);
                else row.advance(reply, now);
                row = threadReads.saveAndFlush(row);
            }
            var unread = reads.unread(actor, channel);
            var thread = reads.threads(member, List.of(original)).get(original);
            return new ThreadReadResult(Long.toString(original), thread.position() == null ? null : thread.position().toString(),
                    row == null ? null : row.getLastReadAt(), thread.unread(), unread.total(), unread.main(), unread.replies());
        });
    }

    private static ApiException invalidReadTarget(String message) {
        return ApiException.unprocessable("INVALID_READ_TARGET", message);
    }

    private Message found(long channel, long id) {
        return messages.findByIdAndConversationId(id, channel).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** {@code task} is null for a text. A card never equals a text, whatever its optional text says, and the reverse. */
    private static boolean sameInput(Message prior, Long task, String text, Long parent) {
        return prior.getKind() == (task == null ? MessageKind.TEXT : MessageKind.TASK_CARD)
                && Objects.equals(prior.getRelatedTaskId(), task) && Objects.equals(prior.getBody(), text)
                && Objects.equals(prior.getParentMessageId(), parent);
    }

    /** {@code rows} are newest first with up to one extra row, which only says that more exist. */
    private MessageFeed feed(List<Message> rows, int size, AuthorizationState reader, ConversationMember member) {
        boolean more = rows.size() > size;
        var page = new ArrayList<>(more ? rows.subList(0, size) : rows);
        Collections.reverse(page);
        var items = views(page, reader, member);
        return new MessageFeed(items, more, more ? items.getFirst().id() : null);
    }

    private MessageView view(Message row, AuthorizationState reader, ConversationMember member) {
        return views(List.of(row), reader, member).getFirst();
    }

    /** Cards are read from their tasks now, for this reader: the stored row never decides what a card shows. */
    private List<MessageView> views(List<Message> rows, AuthorizationState reader, ConversationMember member) {
        if (rows.isEmpty()) return List.of();
        var names = employees.names(rows.stream().map(Message::getSenderEmployeeId).distinct().toList());
        Map<Long, Long> replies = new HashMap<>();
        for (Object[] row : messages.replyCounts(rows.stream().map(Message::getId).toList()))
            replies.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        var cards = taskCards.cards(reader, rows.stream().map(Message::getRelatedTaskId).filter(Objects::nonNull).distinct().toList());
        var threads = reads.threads(member, rows.stream().filter(row -> row.getParentMessageId() == null).map(Message::getId).toList());
        return rows.stream().map(row -> {
            var thread = threads.get(row.getId());
            return new MessageView(row.getId().toString(), Long.toString(row.getConversationId()),
                    new ActorSummary(Long.toString(row.getSenderEmployeeId()), names.getOrDefault(row.getSenderEmployeeId(), "")),
                    row.getParentMessageId() == null ? null : row.getParentMessageId().toString(), ChannelInputs.label(row.getKind()),
                    row.getBody(), row.getRelatedTaskId() == null ? null : cards.get(row.getRelatedTaskId()), row.getClientMessageKey(),
                    replies.getOrDefault(row.getId(), 0L), thread == null ? null : Long.valueOf(thread.unread()),
                    thread == null || thread.position() == null ? null : thread.position().toString(), row.getSentAt());
        }).toList();
    }
}
