package com.openstudy.modules.notice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.notice.dto.response.ActorSummary;
import com.openstudy.modules.notice.dto.response.NoticeDetail;
import com.openstudy.modules.notice.dto.response.NoticeListItem;
import com.openstudy.modules.notice.entity.Notice;
import com.openstudy.modules.notice.entity.NoticeStatus;
import com.openstudy.modules.notice.entity.NoticeTarget;
import com.openstudy.modules.notice.repository.NoticeRepository;
import com.openstudy.modules.notice.repository.NoticeSearchRepository;

/**
 * Internal notices (FR-NOTI-01~03, ADR-M7-001 M7-D04). A notice addressed to everyone is managed by an OWNER only; a
 * notice for selected branches by someone holding notices:write at <em>every</em> target branch, and a change of target
 * needs it at the old and the new targets. Readers see published notices that reach their scope; a notice that is not
 * published is visible only to those who may manage it, and anything else is a 404. The response lists only the target
 * branches the requester can see. The write path takes the notice row first, then re-checks visibility, permission,
 * version and state on the locked row.
 */
@Service
@RequiredArgsConstructor
public class NoticeService {
    private static final String CREATE = "notices:create";
    private static final String ENTITY = "notices";
    private final NoticeRepository repository;
    private final NoticeSearchRepository search;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final AuditTrail audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<NoticeListItem> list(long actor, String scope, ListQuery query, String status) {
        var state = policy.current(actor);
        var wanted = status == null ? null : NoticeInputs.status(status, "status");
        var page = search.find(new NoticeSearchRepository.Filter(policy.scope(state, scope, BranchAction.READ), state.owner(),
                writable(state), query.q(), wanted), query.offset(), query.pageSize());
        var targets = search.branchIds(page.rows().stream().map(Notice::getId).toList());
        var names = names(page.rows());
        var readable = readable(state);
        return new PageResponse<>(page.rows().stream()
                .map(row -> item(row, targets.getOrDefault(row.getId(), Set.of()), state, readable, names)).toList(),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public NoticeDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        var notice = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<Long> targets = new TreeSet<>(notice.getBranchIds());
        if (!visible(state, notice, targets, policy.scope(state, scope, BranchAction.READ)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return detail(notice, targets, state);
    }

    public NoticeDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("title", "body", "target", "branchIds", "pinned", "status");
        String title = input.text("title", 200);
        String content = input.text("body", 20000);
        NoticeTarget target = NoticeInputs.target(input.text("target", 20), "target");
        Set<Long> branches = resolveBranches(target, body.get("branchIds"));
        boolean pinned = input.has("pinned") && NoticeInputs.flag(body.get("pinned"), "pinned");
        NoticeStatus status = input.has("status") ? NoticeInputs.initialStatus(input.text("status", 20), "status") : NoticeStatus.DRAFT;
        validKey(key);
        // scope only has to be well-formed and readable; the target is always the request's own.
        policy.scope(actor, scope, BranchAction.READ);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(title, content, target.name(),
                new TreeSet<>(branches), pinned, status.name())));
        // Whoever may act on at least one target gets in; the exact all-targets rule is applied on the guarded state.
        return commands.inAnyBranch(actor, BranchAction.WRITE_NOTICES, () -> {}, () -> branches, state -> {
            requireManage(state, target, branches);
            var prior = requests.lock(actor, CREATE, ENTITY, key);
            Instant now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), NoticeDetail.class);
                }
                requests.discard(prior.get());
            }
            Instant publishedAt = status == NoticeStatus.PUBLISHED ? now.truncatedTo(ChronoUnit.MICROS) : null;
            var notice = repository.saveAndFlush(new Notice(actor, title, content, target, branches, pinned, status, publishedAt));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("title", title); after.put("target", NoticeInputs.label(target)); after.put("branchIds", ids(branches));
            after.put("pinned", pinned); after.put("status", NoticeInputs.label(status));
            audit.record(AuditEvent.change(actor, auditBranch(branches), ENTITY, notice.getId(), "CREATED", null,
                    json.writeValueAsString(after), requestId));
            announce(notice.getId(), target, branches, null, Set.of());
            var result = detail(notice, new TreeSet<>(branches), state);
            requests.complete(actor, CREATE, ENTITY, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public NoticeDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.containsKey("status"))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "상태는 이 요청으로 바꿀 수 없습니다. 게시와 게시 종료는 상태 변경 요청을 사용해 주세요.");
        var input = new StrictInput(body).only("version", "title", "body", "target", "branchIds", "pinned");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String title = input.has("title") ? input.text("title", 200) : null;
        String content = input.has("body") ? input.text("body", 20000) : null;
        NoticeTarget target = input.has("target") ? NoticeInputs.target(input.text("target", 20), "target") : null;
        Boolean pinned = input.has("pinned") ? Boolean.valueOf(NoticeInputs.flag(body.get("pinned"), "pinned")) : null;
        Set<Long> requested = null;
        if (target == NoticeTarget.ALL) {
            if (body.get("branchIds") != null) throw ApiException.validation("branchIds", "전체 공지에는 대상 지점을 보낼 수 없습니다.");
        } else if (input.has("branchIds")) {
            requested = NoticeInputs.branchIds(body.get("branchIds"), "branchIds");
        }
        Set<Long> requestedIds = requested;
        requireVisible(actor, id, scope);
        return commands.inAnyBranch(actor, BranchAction.WRITE_NOTICES, () -> repository.lock(id), () -> currentTargets(id), state -> {
            var notice = locked(id);
            Set<Long> current = new TreeSet<>(notice.getBranchIds());
            // Re-evaluated on the locked row with the permissions the guard just read.
            if (!visible(state, notice, current, policy.scope(state, scope, BranchAction.READ)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            requireManage(state, notice.getTarget(), current);
            NoticeTarget nextTarget = target == null ? notice.getTarget() : target;
            Set<Long> nextIds;
            if (nextTarget == NoticeTarget.ALL) {
                if (requestedIds != null || body.get("branchIds") != null)
                    throw ApiException.validation("branchIds", "전체 공지에는 대상 지점을 보낼 수 없습니다.");
                nextIds = Set.of();
            } else if (requestedIds != null) {
                nextIds = requestedIds;
            } else if (notice.getTarget() == NoticeTarget.SELECTED_BRANCHES) {
                nextIds = current;
            } else {
                throw ApiException.validation("branchIds", "선택 지점 공지는 대상 지점이 필요합니다.");
            }
            boolean retargeted = nextTarget != notice.getTarget() || !new TreeSet<>(nextIds).equals(current);
            if (retargeted) requireManage(state, nextTarget, nextIds);
            ApiVersion.requireCurrent(version, notice.getVersion(), reload(id));

            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            String nextTitle = title == null ? notice.getTitle() : title;
            String nextBody = content == null ? notice.getBody() : content;
            boolean nextPinned = pinned == null ? notice.isPinned() : pinned;
            compare("title", notice.getTitle(), nextTitle, before, after);
            compare("body", notice.getBody(), nextBody, before, after);
            compare("pinned", notice.isPinned(), nextPinned, before, after);
            if (retargeted) {
                before.put("target", NoticeInputs.label(notice.getTarget())); after.put("target", NoticeInputs.label(nextTarget));
                before.put("branchIds", ids(current)); after.put("branchIds", ids(nextIds));
            }
            // An identical request is not a change: no version bump, audit row or event.
            if (before.isEmpty()) return detail(notice, current, state);
            NoticeTarget previousTarget = notice.getTarget();
            notice.edit(nextTitle, nextBody, nextPinned);
            if (retargeted) notice.retarget(nextTarget, nextIds);
            var saved = repository.saveAndFlush(notice);
            audit.record(AuditEvent.change(actor, auditBranch(nextTarget == NoticeTarget.ALL ? current : nextIds), ENTITY, id, "UPDATED",
                    null, json.writeValueAsString(before), json.writeValueAsString(after), requestId));
            announce(id, nextTarget, nextIds, previousTarget, current);
            return detail(saved, new TreeSet<>(nextIds), state);
        });
    }

    public NoticeDetail changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        NoticeStatus next = NoticeInputs.status(input.text("status", 20), "status");
        requireVisible(actor, id, scope);
        return commands.inAnyBranch(actor, BranchAction.WRITE_NOTICES, () -> repository.lock(id), () -> currentTargets(id), state -> {
            var notice = locked(id);
            Set<Long> current = new TreeSet<>(notice.getBranchIds());
            if (!visible(state, notice, current, policy.scope(state, scope, BranchAction.READ)))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            requireManage(state, notice.getTarget(), current);
            ApiVersion.requireCurrent(version, notice.getVersion(), reload(id));
            NoticeStatus previous = notice.getStatus();
            if (previous == next) return detail(notice, current, state);
            // DRAFT -> PUBLISHED, PUBLISHED -> ARCHIVED, ARCHIVED -> PUBLISHED; a published notice never returns to a draft.
            boolean allowed = (previous == NoticeStatus.DRAFT && next == NoticeStatus.PUBLISHED)
                    || (previous == NoticeStatus.PUBLISHED && next == NoticeStatus.ARCHIVED)
                    || (previous == NoticeStatus.ARCHIVED && next == NoticeStatus.PUBLISHED);
            if (!allowed)
                throw ApiException.conflict("INVALID_STATE", "현재 상태에서는 " + NoticeInputs.label(next) + " 상태로 바꿀 수 없습니다.");
            if (next == NoticeStatus.PUBLISHED) notice.publish(clock.instant().truncatedTo(ChronoUnit.MICROS)); else notice.archive();
            var saved = repository.saveAndFlush(notice);
            audit.record(AuditEvent.change(actor, auditBranch(current), ENTITY, id, "STATUS_CHANGED", null,
                    json.writeValueAsString(Map.of("status", NoticeInputs.label(previous))),
                    json.writeValueAsString(Map.of("status", NoticeInputs.label(next))), requestId));
            announce(id, notice.getTarget(), current, notice.getTarget(), current);
            return detail(saved, current, state);
        });
    }

    // ---- rules ------------------------------------------------------------------------------------------------

    /** ALL needs an OWNER; selected branches need notices:write at every one of them. */
    private void requireManage(AuthorizationState state, NoticeTarget target, Set<Long> branches) {
        if (!manageable(state, target, branches)) throw AuthorizationService.forbidden();
    }

    private boolean manageable(AuthorizationState state, NoticeTarget target, Set<Long> branches) {
        if (!"ACTIVE".equals(state.status())) return false;
        if (target == NoticeTarget.ALL) return state.owner();
        return !branches.isEmpty() && branches.stream().allMatch(branch -> policy.permits(state, branch, BranchAction.WRITE_NOTICES));
    }

    /** Reaches the reader's scope, and is published or manageable by the reader. */
    private boolean visible(AuthorizationState state, Notice notice, Set<Long> branches, Set<Long> scope) {
        boolean reaches = notice.getTarget() == NoticeTarget.ALL ? !scope.isEmpty() : branches.stream().anyMatch(scope::contains);
        return reaches && (notice.getStatus() == NoticeStatus.PUBLISHED || manageable(state, notice.getTarget(), branches));
    }

    /** 404 for anything the reader cannot see, decided before the guard so that a hidden notice never answers 403. */
    private void requireVisible(long actor, long id, String scope) {
        var state = policy.current(actor);
        var notice = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var branches = search.branchIds(List.of(id)).getOrDefault(id, Set.of());
        if (!visible(state, notice, branches, policy.scope(state, scope, BranchAction.READ)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Set<Long> currentTargets(long id) {
        return repository.findById(id).map(notice -> (Set<Long>) new LinkedHashSet<>(notice.getBranchIds())).orElse(Set.of());
    }

    private Notice locked(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Set<Long> resolveBranches(NoticeTarget target, Object value) {
        if (target == NoticeTarget.ALL) {
            if (value != null) throw ApiException.validation("branchIds", "전체 공지에는 대상 지점을 보낼 수 없습니다.");
            return Set.of();
        }
        return NoticeInputs.branchIds(value, "branchIds");
    }

    private Set<Long> writable(AuthorizationState state) {
        return state.branches().keySet().stream().filter(branch -> policy.permits(state, branch, BranchAction.WRITE_NOTICES))
                .collect(Collectors.toSet());
    }

    private Set<Long> readable(AuthorizationState state) {
        return state.branches().keySet().stream().filter(branch -> policy.permits(state, branch, BranchAction.READ))
                .collect(Collectors.toSet());
    }

    /**
     * One invalidate row per target branch (old and new target alike, so a branch that just lost the notice reloads),
     * or one per branch for a notice that is or was addressed to everyone. The stream hides the notice ID.
     */
    private void announce(long id, NoticeTarget target, Set<Long> branches, NoticeTarget previousTarget, Set<Long> previousBranches) {
        if (target == NoticeTarget.ALL || previousTarget == NoticeTarget.ALL) {
            outbox.invalidateEveryBranch(ENTITY, id);
            return;
        }
        Set<Long> all = new TreeSet<>(branches);
        all.addAll(previousBranches);
        for (long branch : all) outbox.invalidate(branch, ENTITY, id);
    }

    // ---- responses --------------------------------------------------------------------------------------------

    private NoticeListItem item(Notice row, Set<Long> targets, AuthorizationState state, Set<Long> readable, Map<Long, String> names) {
        return new NoticeListItem(row.getId().toString(), row.getTitle(), actor(row.getAuthorEmployeeId(), names),
                NoticeInputs.label(row.getTarget()), visibleBranches(targets, readable), NoticeInputs.label(row.getStatus()),
                row.isPinned(), row.getPublishedAt(), manageable(state, row.getTarget(), targets),
                ApiVersion.fromDatabase(row.getVersion()), row.getCreatedAt(), row.getUpdatedAt());
    }

    private NoticeDetail detail(Notice row, Set<Long> targets, AuthorizationState state) {
        var names = names(List.of(row));
        return new NoticeDetail(row.getId().toString(), row.getTitle(), actor(row.getAuthorEmployeeId(), names),
                NoticeInputs.label(row.getTarget()), visibleBranches(targets, readable(state)), NoticeInputs.label(row.getStatus()),
                row.isPinned(), row.getPublishedAt(), manageable(state, row.getTarget(), targets),
                ApiVersion.fromDatabase(row.getVersion()), row.getCreatedAt(), row.getUpdatedAt(), row.getBody());
    }

    /** Only target branches the requester can see; the others are neither named nor counted. */
    private static List<String> visibleBranches(Set<Long> targets, Set<Long> readable) {
        return targets.stream().filter(readable::contains).sorted().map(String::valueOf).toList();
    }

    private Map<Long, String> names(List<Notice> rows) {
        return employees.names(rows.stream().map(Notice::getAuthorEmployeeId).distinct().toList());
    }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static List<String> ids(Set<Long> branches) {
        return branches.stream().sorted().map(String::valueOf).toList();
    }

    /** The audit row names a branch only when the notice has exactly one. */
    private static Long auditBranch(Set<Long> branches) {
        return branches.size() == 1 ? branches.iterator().next() : null;
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeMap, Map<String, Object> afterMap) {
        if (Objects.equals(before, after)) return;
        beforeMap.put(field, before);
        afterMap.put(field, after);
    }

    private static String reload(long id) { return "/api/notices/" + id; }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
