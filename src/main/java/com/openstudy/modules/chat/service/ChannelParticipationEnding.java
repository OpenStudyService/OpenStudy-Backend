package com.openstudy.modules.chat.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.chat.entity.Conversation;
import com.openstudy.modules.chat.repository.ConversationMemberRepository;
import com.openstudy.modules.chat.repository.ConversationRepository;
import com.openstudy.modules.employee.dto.EmployeeAccessReduced;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/**
 * M7-D07: when an employee loses access, the channels they no longer qualify for end in the same transaction, under the
 * guard the command already holds. The rule is evaluated again rather than inferred from the change, so a channel for
 * everyone ends only with the last active membership. Getting access back never rejoins anyone. A member list changed
 * here advances the channel version like any other change. DM rows stay out of it until M14 defines them.
 * <p>This keeps running while team chat is off (#167): the remaining rows stay consistent with who still has access, so
 * turning the flag back on never shows a channel to someone who lost access in the meantime.
 */
@Component
@RequiredArgsConstructor
public class ChannelParticipationEnding {
    private final ConversationRepository channels;
    private final ConversationMemberRepository members;
    private final EmployeeAccountService employees;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccessReduced(EmployeeAccessReduced event) {
        long employee = event.employeeId();
        var ended = channels.joined(employee).stream().filter(channel -> !employees.channelEligible(employee, channel.getBranchId()))
                .map(Conversation::getId).toList();
        if (ended.isEmpty()) return;
        members.end(employee, ended, clock.instant().truncatedTo(ChronoUnit.MICROS));
        channels.incrementVersions(ended);
    }
}
