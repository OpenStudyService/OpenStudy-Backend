package com.openstudy.modules.locker.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
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
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
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
    private static final Set<String> IMMUTABLE = Set.of("branchId", "status");
    private final LockerRepository repository;
    private final LockerSearchRepository search;
    private final LockerAssignmentRepository assignments;
    private final CustomerService customers;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<LockerView> list(long actor, String scope, ListQuery query, String assignmentState) {
        LockerAssignmentState filter = assignmentState == null ? null : LockerInputs.assignmentState(assignmentState);
        LocalDate today = LockerInputs.today(clock.instant());
        var page = search.find(policy.scope(policy.current(actor), scope, BranchAction.READ), filter, today, query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), today), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** One item per branch in scope. A branch without lockers still appears, with zeros. */
    @Transactional(readOnly = true)
    public LockerOccupancy occupancy(long actor, String scope) {
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        var asOf = clock.instant();
        Map<Long, LockerSearchRepository.BranchCounts> found = new HashMap<>();
        search.counts(selected, LockerInputs.today(asOf)).forEach(row -> found.put(row.branchId(), row));
        var items = new TreeSet<>(selected).stream().map(branch -> {
            var row = found.getOrDefault(branch, new LockerSearchRepository.BranchCounts(branch, 0, 0, 0, 0, 0));
            return new LockerBranchOccupancy(Long.toString(branch), row.total(), row.inUse(), row.available(), row.blocked(), row.overdue(), asOf);
        }).toList();
        return new LockerOccupancy(items);
    }

    /** 404 hides lockers outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public LockerView get(long actor, long id, String scope) {
        Set<Long> selected = policy.scope(policy.current(actor), scope, BranchAction.READ);
        var locker = repository.findById(id).filter(found -> selected.contains(found.getBranchId())).orElseThrow(LockerService::notFound);
        return view(locker);
    }

    public LockerView create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "lockerNo", "ratePer4WeeksKrw", "status");
        long branch = input.id("branchId");
        String lockerNo = input.text("lockerNo", 40);
        long rate = LockerInputs.integer(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX);
        LockerState stored = input.has("status") ? LockerInputs.state(input.text("status", 20), "status") : LockerState.AVAILABLE;
        LockerInputs.validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = LockerInputs.digest(json.writeValueAsString(Arrays.asList(branch, lockerNo, rate, stored.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_LOCKERS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var replayed = replay(actor, CREATE, scopeKey, key, fingerprint, LockerView.class);
            if (replayed.isPresent()) return replayed.get();
            if (repository.existsByBranchIdAndLockerNo(branch, lockerNo)) throw LockerConflicts.duplicateLockerNo();
            var locker = LockerConflicts.translating(() -> repository.saveAndFlush(new Locker(branch, lockerNo, rate, stored)));
            audit.record(AuditEvent.change(actor, branch, ENTITY, locker.getId(), "CREATED", null, null,
                    json.writeValueAsString(snapshot(locker)), requestId));
            var result = view(locker);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Missing fields keep their value. Changing the rate leaves every charge's snapshot as it was. */
    public LockerView update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·운영 상태는 이 요청으로 바꿀 수 없습니다. 운영 상태는 상태 변경 요청을 사용해 주세요.");
        var input = new StrictInput(body).only("version", "lockerNo", "ratePer4WeeksKrw");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String lockerNo = input.has("lockerNo") ? input.text("lockerNo", 40) : null;
        Long rate = input.has("ratePer4WeeksKrw") ? Long.valueOf(LockerInputs.integer(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX)) : null;
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> repository.branchOf(id).filter(selected::contains), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
            var locker = repository.locked(id).orElseThrow(LockerService::notFound);
            ApiVersion.requireCurrent(version, locker.getVersion(), reload(id));
            String newLockerNo = lockerNo == null ? locker.getLockerNo() : lockerNo;
            long newRate = rate == null ? locker.getRatePer4WeeksKrw() : rate;
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("lockerNo", locker.getLockerNo(), newLockerNo, before, after);
            compare("ratePer4WeeksKrw", locker.getRatePer4WeeksKrw(), newRate, before, after);
            // An identical request is not a change: no version bump, audit row or event.
            if (before.isEmpty()) return view(locker);
            if (!newLockerNo.equals(locker.getLockerNo()) && repository.existsByBranchIdAndLockerNoAndIdNot(branch, newLockerNo, id))
                throw LockerConflicts.duplicateLockerNo();
            locker.change(newLockerNo, newRate);
            LockerConflicts.translating(() -> repository.saveAndFlush(locker));
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null, json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            return view(locker);
        });
    }

    /** Stopping refuses new assignments and extensions only; an active assignment stays and can be returned. */
    public LockerView changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        LockerState next = LockerInputs.state(input.text("status", 20), "status");
        String reason = input.text("reason", 500);
        Set<Long> selected = policy.scope(actor, scope, BranchAction.READ);
        return commands.inRecordBranch(actor, BranchAction.READ, () -> repository.branchOf(id).filter(selected::contains), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
            var locker = repository.locked(id).orElseThrow(LockerService::notFound);
            ApiVersion.requireCurrent(version, locker.getVersion(), reload(id));
            LockerState previous = locker.getStatus();
            // The same state again is not a change: the current value comes back with no audit row or event.
            if (previous == next) return view(locker);
            locker.changeStatus(next);
            repository.saveAndFlush(locker);
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "STATUS_CHANGED", reason,
                    json.writeValueAsString(Map.of("status", previous.label())),
                    json.writeValueAsString(Map.of("status", next.label())), requestId));
            return view(locker);
        });
    }

    /**
     * The same rate for every locker of the branch, blocked ones too (M6-D02). The rows are locked first, so a running
     * assignment charges either the whole old rate or the whole new one. One audit row and one event stand for the branch.
     * The branch is the path's resource: outside the actor's branches it is hidden (404), inside without the grant 403.
     */
    public LockerRateResult applyRate(long actor, long branchId, String key, Map<String, Object> body, String requestId) {
        new StrictInput(body).only("ratePer4WeeksKrw");
        long rate = LockerInputs.integer(body, "ratePer4WeeksKrw", 1, ApiVersion.MAX);
        LockerInputs.validKey(key);
        String scopeKey = Long.toString(branchId);
        byte[] fingerprint = LockerInputs.digest(json.writeValueAsString(Arrays.asList(branchId, rate)));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> Optional.of(branchId), (state, branch) -> {
            policy.require(state, Set.of(branch), BranchAction.WRITE_LOCKERS);
            var replayed = replay(actor, APPLY_RATE, scopeKey, key, fingerprint, LockerRateResult.class);
            if (replayed.isPresent()) return replayed.get();
            var rows = repository.lockedInBranch(branch);
            var previous = rows.stream().map(Locker::getRatePer4WeeksKrw).distinct().sorted().toList();
            int updated = repository.applyRate(branch, rate);
            var after = new LinkedHashMap<String, Object>();
            after.put("ratePer4WeeksKrw", rate);
            after.put("updated", updated);
            audit.record(AuditEvent.change(actor, branch, "branches", branch, "LOCKER_RATE_APPLIED", null,
                    json.writeValueAsString(Map.of("ratesPer4WeeksKrw", previous)), json.writeValueAsString(after), requestId));
            var result = new LockerRateResult(updated, rate);
            requests.complete(actor, APPLY_RATE, scopeKey, key, fingerprint, 200, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private <T> Optional<T> replay(long actor, String operation, String scopeKey, String key, byte[] fingerprint, Class<T> type) {
        var prior = requests.lock(actor, operation, scopeKey, key);
        if (prior.isEmpty()) return Optional.empty();
        if (!clock.instant().isBefore(prior.get().getExpiresAt())) {
            requests.discard(prior.get());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        return Optional.of(json.readValue(prior.get().getResponseJson(), type));
    }

    private LockerView view(Locker locker) {
        return views(List.of(locker), LockerInputs.today(clock.instant())).getFirst();
    }

    /** The one mapping behind every response. Blocked comes first: the table of the contract is read in order. */
    private List<LockerView> views(List<Locker> rows, LocalDate today) {
        if (rows.isEmpty()) return List.of();
        Map<Long, LockerAssignment> occupied = new HashMap<>();
        assignments.occupying(rows.stream().map(Locker::getId).toList(), today).forEach(row -> occupied.putIfAbsent(row.getLockerId(), row));
        var people = customers.summaries(occupied.values().stream().map(LockerAssignment::getCustomerId).toList());
        return rows.stream().map(row -> {
            var current = occupied.get(row.getId());
            var state = row.getStatus() == LockerState.BLOCKED ? LockerAssignmentState.BLOCKED
                    : current != null ? LockerAssignmentState.IN_USE : LockerAssignmentState.AVAILABLE;
            return new LockerView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), Long.toString(row.getBranchId()),
                    row.getLockerNo(), row.getRatePer4WeeksKrw(), row.getStatus().label(), state.label(),
                    current == null ? null : new LockerActiveAssignment(current.getId().toString(), people.get(current.getCustomerId()),
                            current.getStartDate(), current.getEndDate(), current.overdue(today)));
        }).toList();
    }

    private static Map<String, Object> snapshot(Locker locker) {
        var values = new LinkedHashMap<String, Object>();
        values.put("lockerNo", locker.getLockerNo());
        values.put("ratePer4WeeksKrw", locker.getRatePer4WeeksKrw());
        values.put("status", locker.getStatus().label());
        return values;
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        if (before.equals(after)) return;
        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    private static String reload(long id) { return "/api/lockers/" + id; }
}
