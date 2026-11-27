package com.openstudy.modules.locker.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.locker.dto.response.LockerActiveAssignment;
import com.openstudy.modules.locker.dto.response.LockerBranchOccupancy;
import com.openstudy.modules.locker.dto.response.LockerOccupancy;
import com.openstudy.modules.locker.dto.response.LockerRateResult;
import com.openstudy.modules.locker.dto.response.LockerView;
import com.openstudy.modules.locker.entity.Locker;
import com.openstudy.modules.locker.entity.LockerAssignment;
import com.openstudy.modules.locker.entity.LockerAssignmentState;
import com.openstudy.modules.locker.entity.LockerState;
import com.openstudy.modules.locker.repository.LockerAssignmentRepository;
import com.openstudy.modules.locker.repository.LockerRepository;
import com.openstudy.modules.locker.repository.LockerSearchRepository;

/**
 * Locker ledger and occupancy (FR-LOCK-01/02/05, M6-D02·D05). Any valid member of a branch reads; registering, editing,
 * stopping and the branch rate need operations:write there, also while the branch is stopped, because the ledger is an
 * operating setting. A charge keeps its own rate and an assignment stays when its locker is stopped, so nothing here
 * touches assignments. The state of a locker is never stored: it is computed from one definition for every read.
 */
@Service
@RequiredArgsConstructor
public class LockerService {

    private static final String CREATE = "lockers:create";
    private static final String APPLY_RATE = "lockers:apply-rate";
    private static final String ENTITY = "lockers";
    private static final String BRANCH_ENTITY = "branches";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "status");
    private static final int MAX_LOCKER_NO_LENGTH = 40;
    private static final int MAX_LABEL_LENGTH = 20;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);

    private final LockerRepository lockerRepository;
    private final LockerSearchRepository lockerSearchRepository;
    private final LockerAssignmentRepository lockerAssignmentRepository;
    private final CustomerService customerService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<LockerView> list(long actor, String scope, ListQuery query, String assignmentState) {
        LockerAssignmentState filter = assignmentState == null
                ? null
                : LockerInputs.parseAssignmentState(assignmentState);
        LocalDate today = LockerInputs.toBusinessDate(clock.instant());
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);
        LockerSearchRepository.Page page = lockerSearchRepository.find(
                selected,
                filter,
                today,
                query.offset(),
                query.pageSize()
        );
        List<LockerView> items = toViews(page.rows(), today);

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** One item per branch in scope. A branch without lockers still appears, with zeros. */
    @Transactional(readOnly = true)
    public LockerOccupancy occupancy(long actor, String scope) {
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);
        Instant asOf = clock.instant();
        Map<Long, LockerSearchRepository.BranchCounts> found = new HashMap<>();
        lockerSearchRepository.countByBranch(selected, LockerInputs.toBusinessDate(asOf))
                .forEach(row -> found.put(row.branchId(), row));
        List<LockerBranchOccupancy> items = new TreeSet<>(selected)
                .stream()
                .map(branch -> toBranchOccupancy(branch, found, asOf))
                .toList();

        return new LockerOccupancy(items);
    }

    /** 404 hides lockers outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public LockerView get(long actor, long id, String scope) {
        Set<Long> selected = authorizationService.scope(authorizationService.current(actor), scope, BranchAction.READ);
        Locker locker = lockerRepository.findById(id)
                .filter(found -> selected.contains(found.getBranchId()))
                .orElseThrow(LockerService::createNotFound);

        return toView(locker);
    }

    public LockerView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "lockerNo", "ratePer4WeeksKrw", "status");
        long branch = input.id("branchId");
        String lockerNo = input.text("lockerNo", MAX_LOCKER_NO_LENGTH);
        long rate = LockerInputs.parseInteger(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX);
        LockerState stored = input.has("status")
                ? LockerInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status")
                : LockerState.AVAILABLE;
        LockerInputs.requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(branch, lockerNo, rate, stored.name());
        byte[] fingerprint = LockerInputs.digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.WRITE_LOCKERS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<LockerView> replayed = replay(actor, CREATE, scopeKey, key, fingerprint, LockerView.class);

            if (replayed.isPresent()) {
                return replayed.get();
            }

            if (lockerRepository.existsByBranchIdAndLockerNo(branch, lockerNo)) {
                throw LockerConflicts.createDuplicateLockerNo();
            }

            Locker newLocker = new Locker(branch, lockerNo, rate, stored);
            Locker locker = LockerConflicts.translating(() -> lockerRepository.saveAndFlush(newLocker));
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, locker.getId(), "CREATED", null, null,
                    objectMapper.writeValueAsString(toSnapshot(locker)), requestId));
            LockerView result = toView(locker);
            completeRequest(actor, CREATE, scopeKey, key, fingerprint, CREATED, result);

            return result;
        });
    }

    /** Missing fields keep their value. Changing the rate leaves every charge's snapshot as it was. */
    public LockerView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        boolean hasImmutableField = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (hasImmutableField) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD",
                    "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        }

        StrictInput input = new StrictInput(body).only("version", "lockerNo", "ratePer4WeeksKrw");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String lockerNo = input.has("lockerNo") ? input.text("lockerNo", MAX_LOCKER_NO_LENGTH) : null;
        Long rate = input.has("ratePer4WeeksKrw")
                ? Long.valueOf(LockerInputs.parseInteger(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX))
                : null;
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> lockerRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
                    Locker locker = lockerRepository.findLocked(id).orElseThrow(LockerService::createNotFound);
                    ApiVersion.requireCurrent(version, locker.getVersion(), toReloadPath(id));
                    String newLockerNo = lockerNo == null ? locker.getLockerNo() : lockerNo;
                    long newRate = rate == null ? locker.getRatePer4WeeksKrw() : rate;
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    putIfChanged("lockerNo", locker.getLockerNo(), newLockerNo, before, after);
                    putIfChanged("ratePer4WeeksKrw", locker.getRatePer4WeeksKrw(), newRate, before, after);

                    // An identical request is not a change: no version bump, audit row or event.
                    if (before.isEmpty()) {
                        return toView(locker);
                    }

                    boolean lockerNoTaken = !newLockerNo.equals(locker.getLockerNo())
                            && lockerRepository.existsByBranchIdAndLockerNoAndIdNot(branch, newLockerNo, id);

                    if (lockerNoTaken) {
                        throw LockerConflicts.createDuplicateLockerNo();
                    }

                    locker.change(newLockerNo, newRate);
                    LockerConflicts.translating(() -> lockerRepository.saveAndFlush(locker));
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null,
                            objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after),
                            requestId));

                    return toView(locker);
                }
        );
    }

    /** Stopping refuses new assignments and extensions only; an active assignment stays and can be returned. */
    public LockerView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        LockerState next = LockerInputs.parseState(input.text("status", MAX_LABEL_LENGTH), "status");
        String reason = input.text("reason", MAX_REASON_LENGTH);
        Set<Long> selected = authorizationService.scope(actor, scope, BranchAction.READ);

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> lockerRepository.findBranch(id).filter(selected::contains),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
                    Locker locker = lockerRepository.findLocked(id).orElseThrow(LockerService::createNotFound);
                    ApiVersion.requireCurrent(version, locker.getVersion(), toReloadPath(id));
                    LockerState previous = locker.getStatus();

                    // The same state again is not a change: the current value comes back with no audit row or event.
                    if (previous == next) {
                        return toView(locker);
                    }

                    locker.changeStatus(next);
                    lockerRepository.saveAndFlush(locker);
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                            objectMapper.writeValueAsString(Map.of("status", previous.label())),
                            objectMapper.writeValueAsString(Map.of("status", next.label())), requestId));

                    return toView(locker);
                }
        );
    }

    /**
     * The same rate for every locker of the branch, blocked ones too (M6-D02). The rows are locked first, so a running
     * assignment charges either the whole old rate or the whole new one. One audit row and one event stand for the branch.
     * The branch is the path's resource: outside the actor's branches it is hidden (404), inside without the grant 403.
     */
    public LockerRateResult applyRate(
            long actor,
            long branchId,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        new StrictInput(body).only("ratePer4WeeksKrw");
        long rate = LockerInputs.parseInteger(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX);
        LockerInputs.requireValidKey(key);
        String scopeKey = Long.toString(branchId);
        byte[] fingerprint = LockerInputs.digest(objectMapper.writeValueAsString(Arrays.asList(branchId, rate)));

        return authorizedCommands.inRecordBranch(
                actor,
                BranchAction.READ,
                () -> Optional.of(branchId),
                (state, branch) -> {
                    authorizationService.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
                    Optional<LockerRateResult> replayed = replay(actor, APPLY_RATE, scopeKey, key, fingerprint,
                            LockerRateResult.class);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    List<Locker> rows = lockerRepository.findLockedInBranch(branch);
                    List<Long> previous = rows.stream()
                            .map(Locker::getRatePer4WeeksKrw)
                            .distinct()
                            .sorted()
                            .toList();
                    int updated = lockerRepository.applyRate(branch, rate);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("ratePer4WeeksKrw", rate);
                    after.put("updated", updated);
                    auditTrail.record(AuditEvent.change(actor, branch, BRANCH_ENTITY, branch, "LOCKER_RATE_APPLIED",
                            null,
                            objectMapper.writeValueAsString(Map.of("ratesPer4WeeksKrw", previous)),
                            objectMapper.writeValueAsString(after), requestId));
                    LockerRateResult result = new LockerRateResult(updated, rate);
                    completeRequest(actor, APPLY_RATE, scopeKey, key, fingerprint, OK, result);

                    return result;
                }
        );
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private <T> Optional<T> replay(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            Class<T> type
    ) {
        Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, operation, scopeKey, key);

        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            idempotencyStore.discard(prior.get());

            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), type));
    }

    private void completeRequest(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] fingerprint,
            int status,
            Object result
    ) {
        String responseJson = objectMapper.writeValueAsString(result);
        Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
        idempotencyStore.complete(actor, operation, scopeKey, key, fingerprint, status, responseJson, expiresAt);
    }

    private LockerView toView(Locker locker) {
        return toViews(List.of(locker), LockerInputs.toBusinessDate(clock.instant())).getFirst();
    }

    /** The one mapping behind every response. Blocked comes first: the table of the contract is read in order. */
    private List<LockerView> toViews(List<Locker> rows, LocalDate today) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> lockerIds = rows.stream()
                .map(Locker::getId)
                .toList();
        Map<Long, LockerAssignment> occupied = new HashMap<>();
        lockerAssignmentRepository.findOccupying(lockerIds, today)
                .forEach(row -> occupied.putIfAbsent(row.getLockerId(), row));
        List<Long> customerIds = occupied.values()
                .stream()
                .map(LockerAssignment::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> people = customerService.summaries(customerIds);

        return rows.stream()
                .map(row -> toView(row, occupied.get(row.getId()), people, today))
                .toList();
    }

    private static LockerView toView(
            Locker locker,
            LockerAssignment current,
            Map<Long, CustomerSummary> people,
            LocalDate today
    ) {
        LockerAssignmentState state;

        if (locker.getStatus() == LockerState.BLOCKED) {
            state = LockerAssignmentState.BLOCKED;
        } else if (current != null) {
            state = LockerAssignmentState.IN_USE;
        } else {
            state = LockerAssignmentState.AVAILABLE;
        }

        LockerActiveAssignment activeAssignment = current == null
                ? null
                : new LockerActiveAssignment(
                        current.getId().toString(),
                        people.get(current.getCustomerId()),
                        current.getStartDate(),
                        current.getEndDate(),
                        current.overdue(today)
                );

        return new LockerView(
                locker.getId().toString(),
                ApiVersion.fromDatabase(locker.getVersion()),
                Long.toString(locker.getBranchId()),
                locker.getLockerNo(),
                locker.getRatePer4WeeksKrw(),
                locker.getStatus().label(),
                state.label(),
                activeAssignment
        );
    }

    private static LockerBranchOccupancy toBranchOccupancy(
            long branch,
            Map<Long, LockerSearchRepository.BranchCounts> found,
            Instant asOf
    ) {
        LockerSearchRepository.BranchCounts empty = new LockerSearchRepository.BranchCounts(branch, 0, 0, 0, 0, 0);
        LockerSearchRepository.BranchCounts row = found.getOrDefault(branch, empty);

        return new LockerBranchOccupancy(
                Long.toString(branch),
                row.total(),
                row.inUse(),
                row.available(),
                row.blocked(),
                row.overdue(),
                asOf
        );
    }

    private static Map<String, Object> toSnapshot(Locker locker) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lockerNo", locker.getLockerNo());
        values.put("ratePer4WeeksKrw", locker.getRatePer4WeeksKrw());
        values.put("status", locker.getStatus().label());

        return values;
    }

    private static void putIfChanged(
            String field,
            Object before,
            Object after,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues
    ) {
        if (before.equals(after)) {
            return;
        }

        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static ResponseStatusException createNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static String toReloadPath(long id) {
        return "/api/lockers/" + id;
    }
}
