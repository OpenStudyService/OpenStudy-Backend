package com.openstudy.modules.notice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Optional;
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

    private static final String CREATE_OPERATION = "notices:create";
    private static final String AUDIT_ENTITY_TYPE = "notices";
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 20000;
    private static final int MAX_LABEL_LENGTH = 20;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    /** A PATCH body needs the version plus at least one field to change. */
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final String ALL_TARGET_WITH_BRANCHES = "전체 공지에는 대상 지점을 보낼 수 없습니다.";

    private final NoticeRepository noticeRepository;
    private final NoticeSearchRepository noticeSearchRepository;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<NoticeListItem> list(long actor, String scope, ListQuery query, String status) {
        AuthorizationState state = authorizationService.current(actor);
        NoticeStatus wanted = status == null ? null : NoticeInputs.toStatus(status, "status");
        NoticeSearchRepository.Filter filter = new NoticeSearchRepository.Filter(
                authorizationService.scope(state, scope, BranchAction.READ),
                state.owner(),
                findWritableBranches(state),
                query.q(),
                wanted
        );

        NoticeSearchRepository.Page page = noticeSearchRepository.find(filter, query.offset(), query.pageSize());
        List<Long> ids = page.rows()
                .stream()
                .map(Notice::getId)
                .toList();
        Map<Long, Set<Long>> targets = noticeSearchRepository.findBranchIds(ids);
        Map<Long, String> names = findAuthorNames(page.rows());
        Set<Long> readable = findReadableBranches(state);

        List<NoticeListItem> items = page.rows()
                .stream()
                .map(row -> toListItem(row, targets.getOrDefault(row.getId(), Set.of()), state, readable, names))
                .toList();

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public NoticeDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Notice notice = findNotice(id);
        Set<Long> targets = new TreeSet<>(notice.getBranchIds());

        if (!isVisible(state, notice, targets, authorizationService.scope(state, scope, BranchAction.READ))) {
            throw notFound();
        }

        return toDetail(notice, targets, state);
    }

    public NoticeDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("title", "body", "target", "branchIds", "pinned", "status");
        String title = input.text("title", MAX_TITLE_LENGTH);
        String content = input.text("body", MAX_BODY_LENGTH);
        NoticeTarget target = NoticeInputs.toTarget(input.text("target", MAX_LABEL_LENGTH), "target");
        Set<Long> branches = resolveBranches(target, body.get("branchIds"));
        boolean pinned = input.has("pinned") && NoticeInputs.parseFlag(body.get("pinned"), "pinned");
        NoticeStatus status = input.has("status")
                ? NoticeInputs.toInitialStatus(input.text("status", MAX_LABEL_LENGTH), "status")
                : NoticeStatus.DRAFT;
        requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always the request's own.
        authorizationService.scope(actor, scope, BranchAction.READ);
        List<Object> fingerprintInput = Arrays.asList(
                title,
                content,
                target.name(),
                new TreeSet<>(branches),
                pinned,
                status.name()
        );
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        // Whoever may act on at least one target gets in; the exact all-targets rule is applied on the guarded state.
        return authorizedCommands.inAnyBranch(actor, BranchAction.WRITE_NOTICES, () -> {}, () -> branches, state -> {
            requireManageable(state, target, branches);
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE_OPERATION, AUDIT_ENTITY_TYPE, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    return replay(prior.get(), fingerprint);
                }

                idempotencyStore.discard(prior.get());
            }

            Instant publishedAt = status == NoticeStatus.PUBLISHED ? now.truncatedTo(ChronoUnit.MICROS) : null;
            Notice notice = noticeRepository.saveAndFlush(
                    new Notice(actor, title, content, target, branches, pinned, status, publishedAt)
            );

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("title", title);
            after.put("target", NoticeInputs.toLabel(target));
            after.put("branchIds", toIds(branches));
            after.put("pinned", pinned);
            after.put("status", NoticeInputs.toLabel(status));
            recordChange(actor, toAuditBranch(branches), notice.getId(), "CREATED", null, after, requestId);

            NoticeDetail result = toDetail(notice, new TreeSet<>(branches), state);
            idempotencyStore.complete(
                    actor,
                    CREATE_OPERATION,
                    AUDIT_ENTITY_TYPE,
                    key,
                    fingerprint,
                    CREATED_STATUS,
                    objectMapper.writeValueAsString(result),
                    now.plus(KEY_RETENTION)
            );
            return result;
        });
    }

    public NoticeDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.containsKey("status")) {
            throw ApiException.unprocessable(
                    "IMMUTABLE_FIELD",
                    "상태는 이 요청으로 바꿀 수 없습니다. 게시와 게시 종료는 상태 변경 요청을 사용해 주세요."
            );
        }

        StrictInput input = new StrictInput(body).only("version", "title", "body", "target", "branchIds", "pinned");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String title = input.has("title") ? input.text("title", MAX_TITLE_LENGTH) : null;
        String content = input.has("body") ? input.text("body", MAX_BODY_LENGTH) : null;
        NoticeTarget target = input.has("target")
                ? NoticeInputs.toTarget(input.text("target", MAX_LABEL_LENGTH), "target")
                : null;
        Boolean pinned = input.has("pinned")
                ? Boolean.valueOf(NoticeInputs.parseFlag(body.get("pinned"), "pinned"))
                : null;
        Set<Long> requested = null;

        if (target == NoticeTarget.ALL) {
            if (body.get("branchIds") != null) {
                throw ApiException.validation("branchIds", ALL_TARGET_WITH_BRANCHES);
            }
        } else if (input.has("branchIds")) {
            requested = NoticeInputs.parseBranchIds(body.get("branchIds"), "branchIds");
        }

        Set<Long> requestedIds = requested;
        requireVisible(actor, id, scope);

        return authorizedCommands.inAnyBranch(
                actor,
                BranchAction.WRITE_NOTICES,
                () -> noticeRepository.lock(id),
                () -> findCurrentTargets(id),
                state -> {
                    Notice notice = findNotice(id);
                    Set<Long> current = new TreeSet<>(notice.getBranchIds());

                    // Re-evaluated on the locked row with the permissions the guard just read.
                    Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

                    if (!isVisible(state, notice, current, selected)) {
                        throw notFound();
                    }

                    requireManageable(state, notice.getTarget(), current);
                    NoticeTarget nextTarget = target == null ? notice.getTarget() : target;
                    Set<Long> nextIds = resolveNextIds(notice, nextTarget, current, requestedIds, body);
                    boolean retargeted = nextTarget != notice.getTarget() || !new TreeSet<>(nextIds).equals(current);

                    if (retargeted) {
                        requireManageable(state, nextTarget, nextIds);
                    }

                    ApiVersion.requireCurrent(version, notice.getVersion(), toReloadPath(id));

                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    String nextTitle = title == null ? notice.getTitle() : title;
                    String nextBody = content == null ? notice.getBody() : content;
                    boolean nextPinned = pinned == null ? notice.isPinned() : pinned;
                    putIfChanged("title", notice.getTitle(), nextTitle, before, after);
                    putIfChanged("body", notice.getBody(), nextBody, before, after);
                    putIfChanged("pinned", notice.isPinned(), nextPinned, before, after);

                    if (retargeted) {
                        before.put("target", NoticeInputs.toLabel(notice.getTarget()));
                        after.put("target", NoticeInputs.toLabel(nextTarget));
                        before.put("branchIds", toIds(current));
                        after.put("branchIds", toIds(nextIds));
                    }

                    // An identical request is not a change: no version bump or audit row.
                    if (before.isEmpty()) {
                        return toDetail(notice, current, state);
                    }

                    notice.edit(nextTitle, nextBody, nextPinned);

                    if (retargeted) {
                        notice.retarget(nextTarget, nextIds);
                    }

                    Notice saved = noticeRepository.saveAndFlush(notice);
                    Set<Long> auditBranches = nextTarget == NoticeTarget.ALL ? current : nextIds;
                    recordChange(actor, toAuditBranch(auditBranches), id, "UPDATED", before, after, requestId);

                    return toDetail(saved, new TreeSet<>(nextIds), state);
                }
        );
    }

    public NoticeDetail changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        NoticeStatus next = NoticeInputs.toStatus(input.text("status", MAX_LABEL_LENGTH), "status");
        requireVisible(actor, id, scope);

        return authorizedCommands.inAnyBranch(
                actor,
                BranchAction.WRITE_NOTICES,
                () -> noticeRepository.lock(id),
                () -> findCurrentTargets(id),
                state -> {
                    Notice notice = findNotice(id);
                    Set<Long> current = new TreeSet<>(notice.getBranchIds());

                    Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

                    if (!isVisible(state, notice, current, selected)) {
                        throw notFound();
                    }

                    requireManageable(state, notice.getTarget(), current);
                    ApiVersion.requireCurrent(version, notice.getVersion(), toReloadPath(id));
                    NoticeStatus previous = notice.getStatus();

                    if (previous == next) {
                        return toDetail(notice, current, state);
                    }

                    if (!isAllowedTransition(previous, next)) {
                        throw ApiException.conflict(
                                "INVALID_STATE",
                                "현재 상태에서는 " + NoticeInputs.toLabel(next) + " 상태로 바꿀 수 없습니다."
                        );
                    }

                    if (next == NoticeStatus.PUBLISHED) {
                        notice.publish(clock.instant().truncatedTo(ChronoUnit.MICROS));
                    } else {
                        notice.archive();
                    }

                    Notice saved = noticeRepository.saveAndFlush(notice);
                    recordChange(
                            actor,
                            toAuditBranch(current),
                            id,
                            "STATUS_CHANGED",
                            Map.of("status", NoticeInputs.toLabel(previous)),
                            Map.of("status", NoticeInputs.toLabel(next)),
                            requestId
                    );

                    return toDetail(saved, current, state);
                }
        );
    }

    // ---- rules ------------------------------------------------------------------------------------------------

    /** DRAFT -> PUBLISHED, PUBLISHED -> ARCHIVED, ARCHIVED -> PUBLISHED; a published notice never returns to a draft. */
    private static boolean isAllowedTransition(NoticeStatus previous, NoticeStatus next) {
        return (previous == NoticeStatus.DRAFT && next == NoticeStatus.PUBLISHED)
                || (previous == NoticeStatus.PUBLISHED && next == NoticeStatus.ARCHIVED)
                || (previous == NoticeStatus.ARCHIVED && next == NoticeStatus.PUBLISHED);
    }

    /** The target branches after an update; checked on the locked row. */
    private static Set<Long> resolveNextIds(
            Notice notice,
            NoticeTarget nextTarget,
            Set<Long> current,
            Set<Long> requestedIds,
            Map<String, Object> body
    ) {
        if (nextTarget == NoticeTarget.ALL) {
            if (requestedIds != null || body.get("branchIds") != null) {
                throw ApiException.validation("branchIds", ALL_TARGET_WITH_BRANCHES);
            }

            return Set.of();
        }

        if (requestedIds != null) {
            return requestedIds;
        }

        if (notice.getTarget() == NoticeTarget.SELECTED_BRANCHES) {
            return current;
        }

        throw ApiException.validation("branchIds", "선택 지점 공지는 대상 지점이 필요합니다.");
    }

    /** ALL needs an OWNER; selected branches need notices:write at every one of them. */
    private void requireManageable(AuthorizationState state, NoticeTarget target, Set<Long> branches) {
        if (!isManageable(state, target, branches)) {
            throw AuthorizationService.forbidden();
        }
    }

    private boolean isManageable(AuthorizationState state, NoticeTarget target, Set<Long> branches) {
        if (!"ACTIVE".equals(state.status())) {
            return false;
        }

        if (target == NoticeTarget.ALL) {
            return state.owner();
        }

        return !branches.isEmpty()
                && branches.stream()
                        .allMatch(branch -> authorizationService.permits(state, branch, BranchAction.WRITE_NOTICES));
    }

    /** Reaches the reader's scope, and is published or manageable by the reader. */
    private boolean isVisible(AuthorizationState state, Notice notice, Set<Long> branches, Set<Long> scope) {
        boolean reaches = notice.getTarget() == NoticeTarget.ALL
                ? !scope.isEmpty()
                : branches.stream().anyMatch(scope::contains);

        return reaches
                && (notice.getStatus() == NoticeStatus.PUBLISHED || isManageable(state, notice.getTarget(), branches));
    }

    /** 404 for anything the reader cannot see, decided before the guard so that a hidden notice never answers 403. */
    private void requireVisible(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Notice notice = findNotice(id);
        Set<Long> branches = noticeSearchRepository.findBranchIds(List.of(id)).getOrDefault(id, Set.of());

        if (!isVisible(state, notice, branches, authorizationService.scope(state, scope, BranchAction.READ))) {
            throw notFound();
        }
    }

    private Set<Long> findCurrentTargets(long id) {
        return noticeRepository.findById(id)
                .map(notice -> (Set<Long>) new LinkedHashSet<>(notice.getBranchIds()))
                .orElse(Set.of());
    }

    private Notice findNotice(long id) {
        return noticeRepository.findById(id).orElseThrow(NoticeService::notFound);
    }

    private static Set<Long> resolveBranches(NoticeTarget target, Object value) {
        if (target == NoticeTarget.ALL) {
            if (value != null) {
                throw ApiException.validation("branchIds", ALL_TARGET_WITH_BRANCHES);
            }

            return Set.of();
        }

        return NoticeInputs.parseBranchIds(value, "branchIds");
    }

    private Set<Long> findWritableBranches(AuthorizationState state) {
        return state.branches()
                .keySet()
                .stream()
                .filter(branch -> authorizationService.permits(state, branch, BranchAction.WRITE_NOTICES))
                .collect(Collectors.toSet());
    }

    private Set<Long> findReadableBranches(AuthorizationState state) {
        return state.branches()
                .keySet()
                .stream()
                .filter(branch -> authorizationService.permits(state, branch, BranchAction.READ))
                .collect(Collectors.toSet());
    }

    private NoticeDetail replay(IdempotencyRecord prior, byte[] fingerprint) {
        if (!MessageDigest.isEqual(fingerprint, prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return objectMapper.readValue(prior.getResponseJson(), NoticeDetail.class);
    }

    private void recordChange(
            long actor,
            Long branch,
            long id,
            String action,
            Map<String, Object> before,
            Map<String, Object> after,
            String requestId
    ) {
        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        AuditEvent event = AuditEvent.change(
                actor,
                branch,
                AUDIT_ENTITY_TYPE,
                id,
                action,
                null,
                beforeJson,
                objectMapper.writeValueAsString(after),
                requestId
        );

        auditTrail.record(event);
    }

    // ---- responses --------------------------------------------------------------------------------------------

    private NoticeListItem toListItem(
            Notice row,
            Set<Long> targets,
            AuthorizationState state,
            Set<Long> readable,
            Map<Long, String> names
    ) {
        return new NoticeListItem(
                row.getId().toString(),
                row.getTitle(),
                toActor(row.getAuthorEmployeeId(), names),
                NoticeInputs.toLabel(row.getTarget()),
                toVisibleBranches(targets, readable),
                NoticeInputs.toLabel(row.getStatus()),
                row.isPinned(),
                row.getPublishedAt(),
                isManageable(state, row.getTarget(), targets),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private NoticeDetail toDetail(Notice row, Set<Long> targets, AuthorizationState state) {
        Map<Long, String> names = findAuthorNames(List.of(row));

        return new NoticeDetail(
                row.getId().toString(),
                row.getTitle(),
                toActor(row.getAuthorEmployeeId(), names),
                NoticeInputs.toLabel(row.getTarget()),
                toVisibleBranches(targets, findReadableBranches(state)),
                NoticeInputs.toLabel(row.getStatus()),
                row.isPinned(),
                row.getPublishedAt(),
                isManageable(state, row.getTarget(), targets),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getBody()
        );
    }

    /** Only target branches the requester can see; the others are neither named nor counted. */
    private static List<String> toVisibleBranches(Set<Long> targets, Set<Long> readable) {
        return targets.stream()
                .filter(readable::contains)
                .sorted()
                .map(String::valueOf)
                .toList();
    }

    private Map<Long, String> findAuthorNames(List<Notice> rows) {
        List<Long> authors = rows.stream()
                .map(Notice::getAuthorEmployeeId)
                .distinct()
                .toList();

        return employeeAccountService.names(authors);
    }

    private static ActorSummary toActor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static List<String> toIds(Set<Long> branches) {
        return branches.stream()
                .sorted()
                .map(String::valueOf)
                .toList();
    }

    /** The audit row names a branch only when the notice has exactly one. */
    private static Long toAuditBranch(Set<Long> branches) {
        return branches.size() == 1 ? branches.iterator().next() : null;
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
        return "/api/notices/" + id;
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
