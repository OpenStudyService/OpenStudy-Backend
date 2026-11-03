package com.openstudy.modules.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.chat.dto.response.ActorSummary;
import com.openstudy.modules.chat.dto.response.CandidateList;
import com.openstudy.modules.chat.dto.response.ChannelList;
import com.openstudy.modules.chat.dto.response.ChannelMemberList;
import com.openstudy.modules.chat.dto.response.ChannelMemberView;
import com.openstudy.modules.chat.dto.response.ChannelView;
import com.openstudy.modules.chat.entity.Conversation;
import com.openstudy.modules.chat.entity.ConversationKind;
import com.openstudy.modules.chat.entity.ConversationMember;
import com.openstudy.modules.chat.repository.ConversationMemberRepository;
import com.openstudy.modules.chat.repository.ConversationRepository;
import com.openstudy.modules.chat.repository.MessageRepository;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/**
 * Channels and their members (FR-CHAT-09, ADR-M7-001 M7-D04·D07). A channel for everyone is managed by an OWNER, a branch
 * channel also by that branch's MANAGER. Managing shows the settings and the member list, never the messages. Commands
 * run under the AuthorizedCommands guard with the channel row locked; visibility, permission, version and eligibility are
 * decided there, in that order, so a caller who may not see the channel learns nothing from a 403, 409 or 422.
 */
@Service
@RequiredArgsConstructor
public class ChannelService {
    private static final String CREATE = "channels:create";
    private static final String ENTITY = "channels";
    private static final Long NO_BRANCH = 0L;
    private final ConversationRepository channels;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final ReadPositions reads;
    private final ChannelAccess access;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore requests;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ChannelList list(long actor, String scope) {
        var state = policy.current(actor);
        Set<Long> branches = policy.scope(state, scope, BranchAction.READ);
        var rows = channels.joined(actor).stream().filter(channel -> access.qualifies(state, channel))
                .filter(channel -> channel.getBranchId() == null || branches.contains(channel.getBranchId())).toList();
        return new ChannelList(views(rows, state));
    }

    @Transactional(readOnly = true)
    public PageResponse<ChannelView> manageable(long actor, String scope, ListQuery query) {
        var state = policy.current(actor);
        Set<Long> branches = policy.scope(state, scope, BranchAction.MANAGE_COLLABORATION);
        if (!state.owner() && branches.isEmpty()) throw AuthorizationService.forbidden();
        Set<Long> bound = branches.isEmpty() ? Set.of(NO_BRANCH) : branches;
        var rows = channels.manageable(state.owner(), bound, query.pageSize(), query.offset());
        return new PageResponse<>(views(rows, state),
                new PageResponse.Meta(query.page(), query.pageSize(), channels.countManageable(state.owner(), bound)));
    }

    /** A branch that does not exist is 404 for the OWNER, who would manage it; to everyone else it is 403. */
    @Transactional(readOnly = true)
    public CandidateList candidates(long actor, String branchId) {
        var state = policy.current(actor);
        Long branch = branchId == null ? null : Long.valueOf(ChannelInputs.id(branchId, "branchId"));
        if (branch == null) {
            policy.requireOwner(state);
        } else {
            if (state.owner() && !state.branches().containsKey(branch)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            policy.require(state, Set.of(branch), BranchAction.MANAGE_COLLABORATION);
        }
        return new CandidateList(employees.channelCandidates(branch).stream().map(row -> new ActorSummary(row.id(), row.name())).toList());
    }

    @Transactional(readOnly = true)
    public ChannelView get(long actor, long id) {
        var state = policy.current(actor);
        return views(List.of(access.visible(state, id)), state).getFirst();
    }

    @Transactional(readOnly = true)
    public ChannelMemberList members(long actor, long id) {
        access.visible(policy.current(actor), id);
        return memberList(id);
    }

    public ChannelView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("kind", "branchId", "name", "description", "memberIds");
        ConversationKind kind = ChannelInputs.kind(input.text("kind", 20));
        if (kind == ConversationKind.GLOBAL_CHANNEL && body.get("branchId") != null)
            throw ApiException.validation("branchId", "전체 채널에는 지점을 보낼 수 없습니다.");
        Long branch = kind == ConversationKind.BRANCH_CHANNEL ? Long.valueOf(input.id("branchId")) : null;
        String name = input.text("name", 120);
        String description = ChannelInputs.description(body.get("description"));
        Set<Long> requested = new TreeSet<>(ChannelInputs.employeeIds(body.get("memberIds"), "memberIds"));
        if (key == null || key.length() < 16 || key.length() > 128) throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        // scope only has to be well-formed and readable; the target is always the request's own.
        policy.scope(actor, scope, BranchAction.READ);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(kind.name(), branch, name, description, requested)));
        return commands.activeEmployee(actor, () -> {}, state -> {
            if (!access.manages(state, kind, branch)) throw AuthorizationService.forbidden();
            var prior = requests.lock(actor, CREATE, ENTITY, key);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    // A response stored before the split counts existed has none; it is a new channel, so 0 is right.
                    return json.readerFor(ChannelView.class).without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                            .readValue(prior.get().getResponseJson());
                }
                requests.discard(prior.get());
            }
            requireEligible(requested, branch);
            var channel = channels.saveAndFlush(new Conversation(kind, branch, name, description, actor));
            // The creator manages the channel, so the creator qualifies: an OWNER or a MANAGER of the branch.
            Set<Long> joining = new TreeSet<>(requested);
            joining.add(actor);
            for (long employee : joining) members.save(new ConversationMember(channel.getId(), employee, now));
            members.flush();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("name", name); after.put("description", description); after.put("kind", ChannelInputs.label(kind));
            after.put("memberIds", ids(joining));
            audit.record(AuditEvent.change(actor, branch, ENTITY, channel.getId(), "CREATED", null, json.writeValueAsString(after), requestId));
            var result = views(List.of(channel), state).getFirst();
            requests.complete(actor, CREATE, ENTITY, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public ChannelView update(long actor, long id, Map<String, Object> body, String requestId) {
        return commands.activeEmployee(actor, () -> channels.lock(id), state -> {
            var channel = access.visible(state, id);
            if (!access.manages(state, channel)) throw AuthorizationService.forbidden();
            if (body != null && (body.containsKey("kind") || body.containsKey("branchId")))
                throw ApiException.unprocessable("IMMUTABLE_FIELD", "채널 종류와 지점은 바꿀 수 없습니다.");
            var input = new StrictInput(body).only("version", "name", "description");
            long version = input.version("version");
            if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
            String name = input.has("name") ? input.text("name", 120) : channel.getName();
            String description = input.has("description") ? ChannelInputs.description(body.get("description")) : channel.getDescription();
            ApiVersion.requireCurrent(version, channel.getVersion(), reload(id));
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("name", channel.getName(), name, before, after);
            compare("description", channel.getDescription(), description, before, after);
            // An identical request is not a change: no version bump and no audit row.
            if (before.isEmpty()) return views(List.of(channel), state).getFirst();
            channel.edit(name, description);
            var saved = channels.saveAndFlush(channel);
            audit.record(AuditEvent.change(actor, channel.getBranchId(), ENTITY, id, "UPDATED", json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            return views(List.of(saved), state).getFirst();
        });
    }

    public ChannelMemberList replaceMembers(long actor, long id, Map<String, Object> body, String requestId) {
        return commands.activeEmployee(actor, () -> channels.lock(id), state -> {
            var channel = access.visible(state, id);
            if (!access.manages(state, channel)) throw AuthorizationService.forbidden();
            var input = new StrictInput(body).only("version", "employeeIds");
            long version = input.version("version");
            Set<Long> wanted = new TreeSet<>(ChannelInputs.employeeIds(body.get("employeeIds"), "employeeIds"));
            ApiVersion.requireCurrent(version, channel.getVersion(), reload(id));
            requireEligible(wanted, channel.getBranchId());

            Map<Long, ConversationMember> rows = members.findByConversationId(id).stream()
                    .collect(Collectors.toMap(ConversationMember::getEmployeeId, row -> row));
            Set<Long> current = rows.values().stream().filter(ConversationMember::active).map(ConversationMember::getEmployeeId)
                    .collect(Collectors.toCollection(TreeSet::new));
            // An identical list is not a change: no version bump and no audit row.
            if (current.equals(wanted)) return memberList(id);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            for (long employee : current) if (!wanted.contains(employee)) rows.get(employee).leave(now);
            Long latest = messages.latestId(id);
            for (long employee : wanted) {
                var row = rows.get(employee);
                if (row == null) members.save(new ConversationMember(id, employee, now));
                // Staying on the list is not joining again: those positions must not move.
                else if (!row.active()) row.rejoin(latest, now);
            }
            members.flush();
            channels.incrementVersions(List.of(id));
            entityManager.refresh(channel);
            audit.record(AuditEvent.change(actor, channel.getBranchId(), ENTITY, id, "MEMBERS_CHANGED",
                    json.writeValueAsString(Map.of("memberIds", ids(current))),
                    json.writeValueAsString(Map.of("memberIds", ids(wanted))), requestId));
            return memberList(id);
        });
    }

    /** Unknown and unqualified employees are the same 422, so the check does not reveal who exists. */
    private void requireEligible(Set<Long> requested, Long branch) {
        if (requested.isEmpty()) return;
        Set<Long> eligible = employees.channelCandidates(branch).stream().map(row -> Long.valueOf(row.id())).collect(Collectors.toSet());
        if (!eligible.containsAll(requested))
            throw ApiException.unprocessable("INVALID_CHANNEL_MEMBER", "채널 참여 자격이 없는 직원이 포함되어 있습니다.");
    }

    private ChannelMemberList memberList(long id) {
        var rows = members.findByConversationIdAndLeftAtIsNull(id);
        var names = employees.names(rows.stream().map(ConversationMember::getEmployeeId).toList());
        return new ChannelMemberList(rows.stream()
                .map(row -> new ChannelMemberView(new ActorSummary(Long.toString(row.getEmployeeId()), names.getOrDefault(row.getEmployeeId(), "")), row.getJoinedAt()))
                .sorted(Comparator.comparing((ChannelMemberView row) -> row.employee().name()).thenComparing(row -> Long.valueOf(row.employee().id())))
                .toList());
    }

    /** Message facts reach only those who joined; a manager from outside sees the settings and nothing else. */
    private List<ChannelView> views(List<Conversation> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(Conversation::getId).toList();
        var counts = longs(members.countActive(ids));
        Set<Long> qualified = rows.stream().filter(channel -> access.qualifies(state, channel)).map(Conversation::getId).collect(Collectors.toSet());
        Map<Long, ConversationMember> own = qualified.isEmpty() ? Map.of()
                : members.findByEmployeeIdAndLeftAtIsNullAndConversationIdIn(state.employeeId(), qualified).stream()
                        .collect(Collectors.toMap(ConversationMember::getConversationId, row -> row));
        Map<Long, ReadPositions.Unread> unread = own.isEmpty() ? Map.of() : reads.unread(state.employeeId(), own.keySet());
        Map<Long, Instant> last = new HashMap<>();
        if (!own.isEmpty()) for (Object[] row : messages.lastSent(own.keySet())) last.put(((Number) row[0]).longValue(), (Instant) row[1]);
        return rows.stream().map(channel -> {
            var mine = own.get(channel.getId());
            String read = mine == null || mine.getLastReadMessageId() == null ? null : mine.getLastReadMessageId().toString();
            var pending = unread.getOrDefault(channel.getId(), ReadPositions.Unread.NONE);
            return new ChannelView(channel.getId().toString(), ChannelInputs.label(channel.getKind()),
                    channel.getBranchId() == null ? null : channel.getBranchId().toString(), channel.getName(), channel.getDescription(),
                    counts.getOrDefault(channel.getId(), 0L), mine != null, pending.total(), pending.main(), pending.replies(), read,
                    mine == null ? null : last.get(channel.getId()), access.manages(state, channel),
                    ApiVersion.fromDatabase(channel.getVersion()), channel.getCreatedAt());
        }).toList();
    }

    private static Map<Long, Long> longs(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        return result;
    }

    private static List<String> ids(Set<Long> employees) { return employees.stream().map(String::valueOf).toList(); }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeMap, Map<String, Object> afterMap) {
        if (Objects.equals(before, after)) return;
        beforeMap.put(field, before);
        afterMap.put(field, after);
    }

    private static String reload(long id) { return "/api/channels/" + id; }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
