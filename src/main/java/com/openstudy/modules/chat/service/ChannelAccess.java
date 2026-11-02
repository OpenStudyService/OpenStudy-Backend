package com.openstudy.modules.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.chat.entity.Conversation;
import com.openstudy.modules.chat.entity.ConversationKind;
import com.openstudy.modules.chat.entity.ConversationMember;
import com.openstudy.modules.chat.repository.ConversationMemberRepository;
import com.openstudy.modules.chat.repository.ConversationRepository;

/**
 * The one place that answers who may see a channel. A participant has an open row <em>and</em> qualifies right now, so a
 * row that somehow outlived the employee's access opens nothing (M7-D07). Managing a channel never grants its messages.
 * Everything that is not visible is the same 404, and a DM is simply not a channel here.
 */
@Component
@RequiredArgsConstructor
class ChannelAccess {
    private final ConversationRepository channels;
    private final ConversationMemberRepository members;
    private final AuthorizationService policy;

    /** A channel, never a DM. Decides nothing about the reader. */
    private Conversation channel(long id) {
        return channels.findById(id).filter(row -> row.getKind() != ConversationKind.DM)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The reader's open participation in the channel, or 404. Messages, replies and the read position all pass here. */
    ConversationMember participation(AuthorizationState state, long id) {
        if (!qualifies(state, channel(id))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return members.findByConversationIdAndEmployeeIdAndLeftAtIsNull(id, state.employeeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Channel settings and the member list: participants and managers. Anyone else gets 404. */
    Conversation visible(AuthorizationState state, long id) {
        var channel = channel(id);
        boolean joined = qualifies(state, channel)
                && members.findByConversationIdAndEmployeeIdAndLeftAtIsNull(id, state.employeeId()).isPresent();
        if (!joined && !manages(state, channel)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return channel;
    }

    /** M7-D07: the channel's branch, or any branch for a channel addressed to everyone; an OWNER qualifies everywhere. */
    boolean qualifies(AuthorizationState state, Conversation channel) {
        if (!"ACTIVE".equals(state.status())) return false;
        if (state.owner()) return true;
        if (channel.getKind() == ConversationKind.BRANCH_CHANNEL) return policy.permits(state, channel.getBranchId(), BranchAction.READ);
        return !policy.scope(state, "all", BranchAction.READ).isEmpty();
    }

    /** M7-D04. */
    boolean manages(AuthorizationState state, Conversation channel) {
        return manages(state, channel.getKind(), channel.getBranchId());
    }

    boolean manages(AuthorizationState state, ConversationKind kind, Long branch) {
        if (!"ACTIVE".equals(state.status())) return false;
        return kind == ConversationKind.GLOBAL_CHANNEL ? state.owner() : policy.permits(state, branch, BranchAction.MANAGE_COLLABORATION);
    }
}
