package com.openstudy.modules.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Optional;
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
import com.openstudy.common.idempotency.IdempotencyRecord;
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
    private static final int MAX_KIND_LENGTH = 20;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final MessageRepository messageRepository;
    private final ReadPositions readPositions;
    private final ChannelAccess channelAccess;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ChannelList list(long actor, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> branches = authorizationService.scope(state, scope, BranchAction.READ);
        List<Conversation> rows = conversationRepository.findJoined(actor)
                .stream()
                .filter(channel -> channelAccess.qualifies(state, channel))
                .filter(channel -> channel.getBranchId() == null || branches.contains(channel.getBranchId()))
                .toList();

        return new ChannelList(toViews(rows, state));
    }

    @Transactional(readOnly = true)
    public PageResponse<ChannelView> manageable(long actor, String scope, ListQuery query) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> branches = authorizationService.scope(state, scope, BranchAction.MANAGE_COLLABORATION);

        if (!state.owner() && branches.isEmpty()) {
            throw AuthorizationService.forbidden();
        }

        Set<Long> bound = branches.isEmpty() ? Set.of(NO_BRANCH) : branches;
        List<Conversation> rows = conversationRepository.findManageable(state.owner(), bound, query.pageSize(),
                query.offset());
        List<ChannelView> items = toViews(rows, state);
        long total = conversationRepository.countManageable(state.owner(), bound);

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), total));
    }

    /** A branch that does not exist is 404 for the OWNER, who would manage it; to everyone else it is 403. */
    @Transactional(readOnly = true)
    public CandidateList candidates(long actor, String branchId) {
        AuthorizationState state = authorizationService.current(actor);
        Long branch = branchId == null ? null : Long.valueOf(ChannelInputs.parseId(branchId, "branchId"));

        if (branch == null) {
            authorizationService.requireOwner(state);
        } else {
            if (state.owner() && !state.branches().containsKey(branch)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }

            authorizationService.require(state, Set.of(branch), BranchAction.MANAGE_COLLABORATION);
        }

        List<ActorSummary> candidates = employeeAccountService.channelCandidates(branch)
                .stream()
                .map(row -> new ActorSummary(row.id(), row.name()))
                .toList();

        return new CandidateList(candidates);
    }

    @Transactional(readOnly = true)
    public ChannelView get(long actor, long id) {
        AuthorizationState state = authorizationService.current(actor);
        Conversation channel = channelAccess.findVisibleChannel(state, id);

        return toViews(List.of(channel), state).getFirst();
    }

    @Transactional(readOnly = true)
    public ChannelMemberList members(long actor, long id) {
        channelAccess.findVisibleChannel(authorizationService.current(actor), id);

        return toMemberList(id);
    }

    public ChannelView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("kind", "branchId", "name", "description", "memberIds");
        ConversationKind kind = ChannelInputs.parseKind(input.text("kind", MAX_KIND_LENGTH));

        if (kind == ConversationKind.GLOBAL_CHANNEL && body.get("branchId") != null) {
            throw ApiException.validation("branchId", "전체 채널에는 지점을 보낼 수 없습니다.");
        }

        Long branch = kind == ConversationKind.BRANCH_CHANNEL ? Long.valueOf(input.id("branchId")) : null;
        String name = input.text("name", MAX_NAME_LENGTH);
        String description = ChannelInputs.parseDescription(body.get("description"));
        Set<Long> requested = new TreeSet<>(ChannelInputs.parseEmployeeIds(body.get("memberIds"), "memberIds"));
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }

        // scope only has to be well-formed and readable; the target is always the request's own.
        authorizationService.scope(actor, scope, BranchAction.READ);
        List<Object> fingerprintInput = Arrays.asList(kind.name(), branch, name, description, requested);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.activeEmployee(actor, () -> {}, state -> {
            if (!channelAccess.manages(state, kind, branch)) {
                throw AuthorizationService.forbidden();
            }

            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, ENTITY, key);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    }

                    // A response stored before the split counts existed has none; it is a new channel, so 0 is right.
                    return objectMapper.readerFor(ChannelView.class)
                            .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                            .readValue(prior.get().getResponseJson());
                }

                idempotencyStore.discard(prior.get());
            }

            requireEligible(requested, branch);
            Conversation channel = conversationRepository.saveAndFlush(
                    new Conversation(kind, branch, name, description, actor)
            );
            // The creator manages the channel, so the creator qualifies: an OWNER or a MANAGER of the branch.
            Set<Long> joining = new TreeSet<>(requested);
            joining.add(actor);

            for (long employee : joining) {
                conversationMemberRepository.save(new ConversationMember(channel.getId(), employee, now));
            }

            conversationMemberRepository.flush();

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("name", name);
            after.put("description", description);
            after.put("kind", ChannelInputs.toLabel(kind));
            after.put("memberIds", toIdStrings(joining));
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, channel.getId(), "CREATED", null,
                    objectMapper.writeValueAsString(after), requestId));

            ChannelView result = toViews(List.of(channel), state).getFirst();
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, ENTITY, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    public ChannelView update(long actor, long id, Map<String, Object> body, String requestId) {
        return authorizedCommands.activeEmployee(actor, () -> conversationRepository.lock(id), state -> {
            Conversation channel = channelAccess.findVisibleChannel(state, id);

            if (!channelAccess.manages(state, channel)) {
                throw AuthorizationService.forbidden();
            }

            boolean hasImmutableField = body != null && (body.containsKey("kind") || body.containsKey("branchId"));

            if (hasImmutableField) {
                throw ApiException.unprocessable("IMMUTABLE_FIELD", "채널 종류와 지점은 바꿀 수 없습니다.");
            }

            StrictInput input = new StrictInput(body).only("version", "name", "description");
            long version = input.version("version");

            if (body.size() < MIN_UPDATE_FIELDS) {
                throw ApiException.validation("body", "변경할 항목이 필요합니다.");
            }

            String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : channel.getName();
            String description = input.has("description")
                    ? ChannelInputs.parseDescription(body.get("description"))
                    : channel.getDescription();
            ApiVersion.requireCurrent(version, channel.getVersion(), toReloadPath(id));
            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            putIfChanged("name", channel.getName(), name, before, after);
            putIfChanged("description", channel.getDescription(), description, before, after);

            // An identical request is not a change: no version bump and no audit row.
            if (before.isEmpty()) {
                return toViews(List.of(channel), state).getFirst();
            }

            channel.edit(name, description);
            Conversation saved = conversationRepository.saveAndFlush(channel);
            auditTrail.record(AuditEvent.change(actor, channel.getBranchId(), ENTITY, id, "UPDATED",
                    objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after), requestId));

            return toViews(List.of(saved), state).getFirst();
        });
    }

    public ChannelMemberList replaceMembers(long actor, long id, Map<String, Object> body, String requestId) {
        return authorizedCommands.activeEmployee(actor, () -> conversationRepository.lock(id), state -> {
            Conversation channel = channelAccess.findVisibleChannel(state, id);

            if (!channelAccess.manages(state, channel)) {
                throw AuthorizationService.forbidden();
            }

            StrictInput input = new StrictInput(body).only("version", "employeeIds");
            long version = input.version("version");
            Set<Long> wanted = new TreeSet<>(ChannelInputs.parseEmployeeIds(body.get("employeeIds"), "employeeIds"));
            ApiVersion.requireCurrent(version, channel.getVersion(), toReloadPath(id));
            requireEligible(wanted, channel.getBranchId());

            Map<Long, ConversationMember> rows = conversationMemberRepository.findByConversationId(id)
                    .stream()
                    .collect(Collectors.toMap(ConversationMember::getEmployeeId, row -> row));
            Set<Long> current = rows.values()
                    .stream()
                    .filter(ConversationMember::isActive)
                    .map(ConversationMember::getEmployeeId)
                    .collect(Collectors.toCollection(TreeSet::new));

            // An identical list is not a change: no version bump and no audit row.
            if (current.equals(wanted)) {
                return toMemberList(id);
            }

            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

            for (long employee : current) {
                if (!wanted.contains(employee)) {
                    rows.get(employee).leave(now);
                }
            }

            Long latest = messageRepository.findLatestId(id);

            for (long employee : wanted) {
                ConversationMember row = rows.get(employee);

                if (row == null) {
                    conversationMemberRepository.save(new ConversationMember(id, employee, now));
                } else if (!row.isActive()) {
                    // Staying on the list is not joining again: those positions must not move.
                    row.rejoin(latest, now);
                }
            }

            conversationMemberRepository.flush();
            conversationRepository.incrementVersions(List.of(id));
            entityManager.refresh(channel);
            auditTrail.record(AuditEvent.change(actor, channel.getBranchId(), ENTITY, id, "MEMBERS_CHANGED",
                    objectMapper.writeValueAsString(Map.of("memberIds", toIdStrings(current))),
                    objectMapper.writeValueAsString(Map.of("memberIds", toIdStrings(wanted))), requestId));

            return toMemberList(id);
        });
    }

    /** Unknown and unqualified employees are the same 422, so the check does not reveal who exists. */
    private void requireEligible(Set<Long> requested, Long branch) {
        if (requested.isEmpty()) {
            return;
        }

        Set<Long> eligible = employeeAccountService.channelCandidates(branch)
                .stream()
                .map(row -> Long.valueOf(row.id()))
                .collect(Collectors.toSet());

        if (!eligible.containsAll(requested)) {
            throw ApiException.unprocessable("INVALID_CHANNEL_MEMBER", "채널 참여 자격이 없는 직원이 포함되어 있습니다.");
        }
    }

    private ChannelMemberList toMemberList(long id) {
        List<ConversationMember> rows = conversationMemberRepository.findByConversationIdAndLeftAtIsNull(id);
        List<Long> employeeIds = rows.stream()
                .map(ConversationMember::getEmployeeId)
                .toList();
        Map<Long, String> names = employeeAccountService.names(employeeIds);
        Comparator<ChannelMemberView> byName = Comparator.comparing((ChannelMemberView row) -> row.employee().name())
                .thenComparing(row -> Long.valueOf(row.employee().id()));
        List<ChannelMemberView> members = rows.stream()
                .map(row -> toMemberView(row, names))
                .sorted(byName)
                .toList();

        return new ChannelMemberList(members);
    }

    private static ChannelMemberView toMemberView(ConversationMember row, Map<Long, String> names) {
        ActorSummary employee = new ActorSummary(
                Long.toString(row.getEmployeeId()),
                names.getOrDefault(row.getEmployeeId(), "")
        );

        return new ChannelMemberView(employee, row.getJoinedAt());
    }

    /** Message facts reach only those who joined; a manager from outside sees the settings and nothing else. */
    private List<ChannelView> toViews(List<Conversation> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> ids = rows.stream()
                .map(Conversation::getId)
                .toList();
        Map<Long, Long> counts = toLongMap(conversationMemberRepository.countActive(ids));
        Set<Long> qualified = rows.stream()
                .filter(channel -> channelAccess.qualifies(state, channel))
                .map(Conversation::getId)
                .collect(Collectors.toSet());
        Map<Long, ConversationMember> own = qualified.isEmpty()
                ? Map.of()
                : conversationMemberRepository
                        .findByEmployeeIdAndLeftAtIsNullAndConversationIdIn(state.employeeId(), qualified)
                        .stream()
                        .collect(Collectors.toMap(ConversationMember::getConversationId, row -> row));
        Map<Long, ReadPositions.Unread> unread = own.isEmpty()
                ? Map.of()
                : readPositions.countUnread(state.employeeId(), own.keySet());
        Map<Long, Instant> last = new HashMap<>();

        if (!own.isEmpty()) {
            for (Object[] row : messageRepository.findLastSent(own.keySet())) {
                last.put(((Number) row[0]).longValue(), (Instant) row[1]);
            }
        }

        return rows.stream()
                .map(channel -> toView(channel, own.get(channel.getId()), counts, unread, last, state))
                .toList();
    }

    private ChannelView toView(
            Conversation channel,
            ConversationMember mine,
            Map<Long, Long> counts,
            Map<Long, ReadPositions.Unread> unread,
            Map<Long, Instant> last,
            AuthorizationState state
    ) {
        String read = mine == null || mine.getLastReadMessageId() == null
                ? null
                : mine.getLastReadMessageId().toString();
        ReadPositions.Unread pending = unread.getOrDefault(channel.getId(), ReadPositions.Unread.NONE);

        return new ChannelView(
                channel.getId().toString(),
                ChannelInputs.toLabel(channel.getKind()),
                channel.getBranchId() == null ? null : channel.getBranchId().toString(),
                channel.getName(),
                channel.getDescription(),
                counts.getOrDefault(channel.getId(), 0L),
                mine != null,
                pending.total(),
                pending.main(),
                pending.replies(),
                read,
                mine == null ? null : last.get(channel.getId()),
                channelAccess.manages(state, channel),
                ApiVersion.fromDatabase(channel.getVersion()),
                channel.getCreatedAt()
        );
    }

    private static Map<Long, Long> toLongMap(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();

        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        return result;
    }

    private static List<String> toIdStrings(Set<Long> employees) {
        return employees.stream()
                .map(String::valueOf)
                .toList();
    }

    private static void putIfChanged(
            String field,
            Object before,
            Object after,
            Map<String, Object> beforeMap,
            Map<String, Object> afterMap
    ) {
        if (Objects.equals(before, after)) {
            return;
        }

        beforeMap.put(field, before);
        afterMap.put(field, after);
    }

    private static String toReloadPath(long id) {
        return "/api/channels/" + id;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
