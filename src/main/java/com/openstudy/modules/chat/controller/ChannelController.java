package com.openstudy.modules.chat.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.chat.dto.response.CandidateList;
import com.openstudy.modules.chat.dto.response.ChannelList;
import com.openstudy.modules.chat.dto.response.ChannelMemberList;
import com.openstudy.modules.chat.dto.response.ChannelReadResult;
import com.openstudy.modules.chat.dto.response.ChannelView;
import com.openstudy.modules.chat.dto.response.MessageFeed;
import com.openstudy.modules.chat.dto.response.MessageView;
import com.openstudy.modules.chat.dto.response.ThreadReadResult;
import com.openstudy.modules.chat.service.ChannelService;
import com.openstudy.modules.chat.service.MessageService;

/**
 * There is deliberately no DELETE for a channel, no edit or delete for a message, and no DM. {@code before} and
 * {@code limit} arrive as text so that a non-participant gets 404 whatever they send (MessageService).
 * <p>Team chat left the service (ADR-M10-002 M10-D10, #167): unless {@code openstudy.team-chat.enabled} is true this
 * controller is not registered, so every {@code /api/channels/**} request ends as an unknown path (404) before any
 * channel or message is read. The tables and services stay; participation still ends with access (ChannelParticipationEnding).
 */
@RestController
@ConditionalOnProperty(name = "openstudy.team-chat.enabled", havingValue = "true")
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private static final Set<String> MANAGEABLE_SORT_FIELDS = Set.of("name");
    private static final String MANAGEABLE_DEFAULT_SORT = "name";

    private final ChannelService channelService;
    private final MessageService messageService;

    @GetMapping
    public ChannelList listMyChannels(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return channelService.list(actorId(actor), scope);
    }

    @PostMapping
    public ResponseEntity<ChannelView> createChannel(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        ChannelView result = channelService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/channels/" + result.id())).body(result);
    }

    @GetMapping("/manageable")
    public PageResponse<ChannelView> listManageableChannels(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, MANAGEABLE_SORT_FIELDS,
                MANAGEABLE_DEFAULT_SORT);

        return channelService.manageable(actorId, scope, query);
    }

    @GetMapping("/member-candidates")
    public CandidateList listChannelMemberCandidates(
            Authentication actor,
            @RequestParam(required = false) String branchId
    ) {
        return channelService.candidates(actorId(actor), branchId);
    }

    @GetMapping("/{id}")
    public ChannelView getChannel(Authentication actor, @PathVariable long id) {
        return channelService.get(actorId(actor), id);
    }

    @PatchMapping("/{id}")
    public ChannelView updateChannel(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return channelService.update(actorId(actor), id, body, requestId);
    }

    @GetMapping("/{id}/members")
    public ChannelMemberList listChannelMembers(Authentication actor, @PathVariable long id) {
        return channelService.members(actorId(actor), id);
    }

    @PutMapping("/{id}/members")
    public ChannelMemberList replaceChannelMembers(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return channelService.replaceMembers(actorId(actor), id, body, requestId);
    }

    @PostMapping("/{id}/read")
    public ChannelReadResult markChannelRead(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        return messageService.markRead(actorId(actor), id, body);
    }

    @GetMapping("/{id}/messages")
    public MessageFeed listChannelMessages(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String limit
    ) {
        return messageService.list(actorId(actor), id, before, limit);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageView> sendChannelMessage(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        MessageService.Sent sent = messageService.send(actorId(actor), id, body);

        return toSentResponse(id, sent);
    }

    @PostMapping("/{id}/task-cards")
    public ResponseEntity<MessageView> shareTaskCard(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body
    ) {
        MessageService.Sent sent = messageService.shareTaskCard(actorId(actor), id, body);

        return toSentResponse(id, sent);
    }

    @GetMapping("/{id}/messages/{messageId}")
    public MessageView getChannelMessage(Authentication actor, @PathVariable long id, @PathVariable long messageId) {
        return messageService.get(actorId(actor), id, messageId);
    }

    @GetMapping("/{id}/messages/{messageId}/replies")
    public MessageFeed listMessageReplies(
            Authentication actor,
            @PathVariable long id,
            @PathVariable long messageId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String limit
    ) {
        return messageService.replies(actorId(actor), id, messageId, before, limit);
    }

    @PostMapping("/{id}/messages/{messageId}/read")
    public ThreadReadResult markThreadRead(
            Authentication actor,
            @PathVariable long id,
            @PathVariable long messageId,
            @RequestBody Map<String, Object> body
    ) {
        return messageService.markThreadRead(actorId(actor), id, messageId, body);
    }

    /** A repeated send answers 200 with the stored message; a new one is 201 with its location. */
    private static ResponseEntity<MessageView> toSentResponse(long channelId, MessageService.Sent sent) {
        if (!sent.created()) {
            return ResponseEntity.ok(sent.message());
        }

        URI location = URI.create("/api/channels/" + channelId + "/messages/" + sent.message().id());

        return ResponseEntity.created(location).body(sent.message());
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
