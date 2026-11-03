package com.openstudy.modules.chat.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {
    private final ChannelService channels;
    private final MessageService messages;

    @GetMapping
    public ChannelList list(Authentication actor, @RequestParam(defaultValue = "all") String scope) {
        return channels.list(actor(actor), scope);
    }

    @PostMapping
    public ResponseEntity<ChannelView> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        var result = channels.create(actor(actor), scope, key, body, ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/channels/" + result.id())).body(result);
    }

    @GetMapping("/manageable")
    public PageResponse<ChannelView> manageable(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize) {
        return channels.manageable(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("name"), "name"));
    }

    @GetMapping("/member-candidates")
    public CandidateList candidates(Authentication actor, @RequestParam(required = false) String branchId) {
        return channels.candidates(actor(actor), branchId);
    }

    @GetMapping("/{id}")
    public ChannelView get(Authentication actor, @PathVariable long id) {
        return channels.get(actor(actor), id);
    }

    @PatchMapping("/{id}")
    public ChannelView update(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return channels.update(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @GetMapping("/{id}/members")
    public ChannelMemberList members(Authentication actor, @PathVariable long id) {
        return channels.members(actor(actor), id);
    }

    @PutMapping("/{id}/members")
    public ChannelMemberList replaceMembers(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return channels.replaceMembers(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/{id}/read")
    public ChannelReadResult read(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return messages.markRead(actor(actor), id, body);
    }

    @GetMapping("/{id}/messages")
    public MessageFeed messages(Authentication actor, @PathVariable long id, @RequestParam(required = false) String before,
            @RequestParam(required = false) String limit) {
        return messages.list(actor(actor), id, before, limit);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageView> send(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        var sent = messages.send(actor(actor), id, body);
        if (!sent.created()) return ResponseEntity.ok(sent.message());
        return ResponseEntity.created(URI.create("/api/channels/" + id + "/messages/" + sent.message().id())).body(sent.message());
    }

    @PostMapping("/{id}/task-cards")
    public ResponseEntity<MessageView> shareTaskCard(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        var sent = messages.shareTaskCard(actor(actor), id, body);
        if (!sent.created()) return ResponseEntity.ok(sent.message());
        return ResponseEntity.created(URI.create("/api/channels/" + id + "/messages/" + sent.message().id())).body(sent.message());
    }

    @GetMapping("/{id}/messages/{messageId}")
    public MessageView message(Authentication actor, @PathVariable long id, @PathVariable long messageId) {
        return messages.get(actor(actor), id, messageId);
    }

    @GetMapping("/{id}/messages/{messageId}/replies")
    public MessageFeed replies(Authentication actor, @PathVariable long id, @PathVariable long messageId,
            @RequestParam(required = false) String before, @RequestParam(required = false) String limit) {
        return messages.replies(actor(actor), id, messageId, before, limit);
    }

    @PostMapping("/{id}/messages/{messageId}/read")
    public ThreadReadResult readThread(Authentication actor, @PathVariable long id, @PathVariable long messageId, @RequestBody Map<String, Object> body) {
        return messages.markThreadRead(actor(actor), id, messageId, body);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
