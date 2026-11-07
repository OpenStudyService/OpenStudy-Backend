package com.openstudy.modules.facility.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.facility.dto.response.ActorSummary;
import com.openstudy.modules.facility.dto.response.FacilityDetail;
import com.openstudy.modules.facility.dto.response.FacilityLastInspection;
import com.openstudy.modules.facility.dto.response.FacilityListItem;
import com.openstudy.modules.facility.dto.response.FacilityOpenWork;
import com.openstudy.modules.facility.dto.response.FacilitySummary;
import com.openstudy.modules.facility.dto.response.FacilityTypeView;
import com.openstudy.modules.facility.entity.Facility;
import com.openstudy.modules.facility.entity.FacilityStatus;
import com.openstudy.modules.facility.entity.FacilityType;
import com.openstudy.modules.facility.entity.FacilityWork;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;
import com.openstudy.modules.facility.repository.FacilityRepository;
import com.openstudy.modules.facility.repository.FacilitySearchRepository;
import com.openstudy.modules.facility.repository.FacilityTypeRepository;
import com.openstudy.modules.facility.repository.FacilityWorkRepository;

/**
 * Branch facilities and their inspections (FR-FAC-01~05, ADR-M7-001 M7-D03·D05·D06). The facility's condition and the
 * processing status of its open inspection work are separate; the assignee lives on that work. Every command that opens
 * or closes a work holds the facility row, which is what keeps exactly one open inspection per facility.
 */
@Service
@RequiredArgsConstructor
public class FacilityService {
    /** "Today" and the registration date are Korean dates (M7-D06). */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String CREATE = "facilities:create";
    private static final String COMPLETE = "facilities:complete-inspection";
    private static final String ENTITY = "facilities";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "lastInspectedOn");
    private final FacilityRepository repository;
    private final FacilityWorkRepository works;
    private final FacilityTypeRepository types;
    private final FacilitySearchRepository search;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final IdempotencyStore requests;
    private final AuditTrail audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<FacilityListItem> list(long actor, String scope, ListQuery query, FacilityFilters filters) {
        var state = policy.current(actor);
        Instant now = clock.instant();
        var sort = switch (query.sort()) {
            case "name" -> FacilitySearchRepository.Sort.NAME;
            case "createdAt" -> FacilitySearchRepository.Sort.CREATED_AT;
            default -> FacilitySearchRepository.Sort.NEXT_INSPECTION_ON;
        };
        var page = search.find(filter(policy.scope(state, scope, BranchAction.READ), query.q(), filters, now), sort,
                query.direction() == ListQuery.Direction.DESC, query.offset(), query.pageSize());
        return new PageResponse<>(items(page.rows(), state, today(now)),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public FacilitySummary summary(long actor, String scope, String q, FacilityFilters filters) {
        var state = policy.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, Set.of("id"), "id").q();
        Map<Long, FacilitySearchRepository.BranchCounts> found = new HashMap<>();
        search.counts(filter(selected, text, filters, now)).forEach(row -> found.put(row.branchId(), row));
        var branches = new ArrayList<FacilitySummary.BranchCounts>();
        var totals = new long[6];
        for (long branch : new TreeSet<>(selected)) {
            var row = found.getOrDefault(branch, new FacilitySearchRepository.BranchCounts(branch, 0, 0, 0, 0, 0, 0));
            branches.add(new FacilitySummary.BranchCounts(Long.toString(branch), row.total(), row.normal(), row.checkNeeded(),
                    row.broken(), row.outOfService(), row.inspectionDue()));
            totals[0] += row.total(); totals[1] += row.normal(); totals[2] += row.checkNeeded(); totals[3] += row.broken();
            totals[4] += row.outOfService(); totals[5] += row.inspectionDue();
        }
        return new FacilitySummary(now, new FacilitySummary.Counts(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5]), branches);
    }

    @Transactional(readOnly = true)
    public FacilityDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        return detail(visible(state, id, scope), state, today(clock.instant()));
    }

    public FacilityDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "typeId", "name", "location", "description", "assigneeId",
                "inspectionIntervalDays", "lastInspectedOn", "lastInspectionResult", "status");
        long branch = input.id("branchId");
        long typeId = input.id("typeId");
        String name = input.text("name", 120);
        String location = FacilityInputs.optionalText(body, "location", 200);
        String description = FacilityInputs.optionalText(body, "description", 20000);
        Long assignee = FacilityInputs.optionalId(body, "assigneeId");
        Integer interval = body.get("inspectionIntervalDays") == null ? null : FacilityInputs.days(body.get("inspectionIntervalDays"), "inspectionIntervalDays");
        LocalDate lastOn = FacilityInputs.optionalDate(body, "lastInspectedOn");
        String lastResult = body.get("lastInspectionResult") == null ? null : input.text("lastInspectionResult", 5000);
        if (lastResult != null && lastOn == null)
            throw ApiException.validation("lastInspectionResult", "점검 결과는 마지막 점검일과 함께 입력해 주세요.");
        FacilityStatus status = body.get("status") == null ? FacilityStatus.NORMAL : FacilityInputs.status(input.text("status", 20), "status");
        validKey(key);
        if (lastOn != null && lastOn.isAfter(today(clock.instant())))
            throw ApiException.validation("lastInspectedOn", "오늘 이후 날짜는 마지막 점검일로 입력할 수 없습니다.");
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, typeId, name, location, description, assignee,
                interval, lastOn == null ? null : lastOn.toString(), lastResult, status.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_FACILITIES, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), FacilityDetail.class);
                }
                requests.discard(prior.get());
            }
            FacilityType type = types.findById(typeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (assignee != null) requireAssignable(assignee, branch);
            int days = interval == null ? type.getDefaultInspectionDays() : interval;
            var facility = repository.saveAndFlush(new Facility(branch, typeId, name, location, description, status, days, lastOn));
            // The facility and its first work are one transaction; a facility never exists without an open inspection.
            if (lastResult != null) works.save(FacilityWork.finished(facility.getId(), assignee, lastResult, actor, now));
            works.save(FacilityWork.open(facility.getId(), assignee));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("typeId", Long.toString(typeId)); after.put("name", name); after.put("location", location);
            after.put("description", description); after.put("status", status.label()); after.put("inspectionIntervalDays", days);
            after.put("lastInspectedOn", lastOn == null ? null : lastOn.toString());
            after.put("assigneeId", assignee == null ? null : Long.toString(assignee));
            audit.record(AuditEvent.change(actor, branch, ENTITY, facility.getId(), "CREATED", null, json.writeValueAsString(after), requestId));
            var result = detail(facility, state, today(now));
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public FacilityDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점과 마지막 점검일은 이 요청으로 바꿀 수 없습니다. 마지막 점검일은 점검 완료로만 바뀝니다.");
        var input = new StrictInput(body).only("version", "typeId", "name", "location", "description", "inspectionIntervalDays",
                "status", "assigneeId", "workStatus");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        Long typeId = input.has("typeId") ? Long.valueOf(input.id("typeId")) : null;
        String name = input.has("name") ? input.text("name", 120) : null;
        String location = FacilityInputs.optionalText(body, "location", 200);
        String description = FacilityInputs.optionalText(body, "description", 20000);
        Integer interval = input.has("inspectionIntervalDays") ? Integer.valueOf(FacilityInputs.days(body.get("inspectionIntervalDays"), "inspectionIntervalDays")) : null;
        FacilityStatus status = input.has("status") ? FacilityInputs.status(input.text("status", 20), "status") : null;
        Long assignee = FacilityInputs.optionalId(body, "assigneeId");
        FacilityWorkStatus workStatus = input.has("workStatus") ? FacilityInputs.workStatus(input.text("workStatus", 20), "workStatus") : null;
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_FACILITIES, () -> repository.lock(id), state -> {
            var facility = locked(id);
            ApiVersion.requireCurrent(version, facility.getVersion(), reload(id));
            var work = openWork(facility);
            long newType = typeId == null ? facility.getTypeId() : typeId;
            String newName = name == null ? facility.getName() : name;
            String newLocation = input.has("location") ? location : facility.getLocation();
            String newDescription = input.has("description") ? description : facility.getDescription();
            int newInterval = interval == null ? facility.getInspectionIntervalDays() : interval;
            FacilityStatus newStatus = status == null ? facility.getStatus() : status;
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("typeId", Long.toString(facility.getTypeId()), Long.toString(newType), before, after);
            compare("name", facility.getName(), newName, before, after);
            compare("location", facility.getLocation(), newLocation, before, after);
            compare("description", facility.getDescription(), newDescription, before, after);
            compare("inspectionIntervalDays", facility.getInspectionIntervalDays(), newInterval, before, after);
            compare("status", facility.getStatus().label(), newStatus.label(), before, after);
            boolean facilityChanged = !before.isEmpty();
            Long newAssignee = input.has("assigneeId") ? assignee : work.getAssignedEmployeeId();
            FacilityWorkStatus newWorkStatus = workStatus == null ? work.getStatus() : workStatus;
            compare("assigneeId", text(work.getAssignedEmployeeId()), text(newAssignee), before, after);
            compare("workStatus", work.getStatus().label(), newWorkStatus.label(), before, after);
            // An identical request is not a change: no version bump, audit row or event.
            if (before.isEmpty()) return detail(facility, state, today(clock.instant()));
            if (newType != facility.getTypeId() && !types.existsById(newType)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (newAssignee != null && !Objects.equals(newAssignee, work.getAssignedEmployeeId())) requireAssignable(newAssignee, branch);
            if (facilityChanged) facility.change(newType, newName, newLocation, newDescription, newStatus, newInterval);
            work.assign(newAssignee);
            work.process(newWorkStatus);
            persist(facility, facilityChanged);
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null, json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            return detail(current(id), state, today(clock.instant()));
        });
    }

    /**
     * Closes the open inspection, records the facility's new condition and last inspection date, and opens the next
     * inspection for the same assignee, all in one transaction (M7-D03).
     */
    public FacilityDetail completeInspection(long actor, long id, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "result", "completedOn", "facilityStatus");
        long version = input.version("version");
        String result = input.text("result", 5000);
        LocalDate completedOn = FacilityInputs.date(input.text("completedOn", 10), "completedOn");
        FacilityStatus status = FacilityInputs.status(input.text("facilityStatus", 20), "facilityStatus");
        validKey(key);
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(id, version, result, completedOn.toString(), status.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> repository.lock(id), state -> {
            var facility = locked(id);
            var work = openWork(facility);
            // The assignee of the work being closed, or a facilities:write holder; both must still belong to the branch.
            if (!canComplete(state, facility, work)) throw AuthorizationService.forbidden();
            var prior = requests.lock(actor, COMPLETE, scopeKey, key);
            Instant now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), FacilityDetail.class);
                }
                requests.discard(prior.get());
            }
            ApiVersion.requireCurrent(version, facility.getVersion(), reload(id));
            if (completedOn.isAfter(today(now))) throw ApiException.validation("completedOn", "오늘 이후 날짜는 점검 완료일로 입력할 수 없습니다.");
            LocalDate previousOn = facility.getLastInspectedOn();
            if (previousOn != null && completedOn.isBefore(previousOn))
                throw ApiException.validation("completedOn", "마지막 점검일보다 이전 날짜는 입력할 수 없습니다.");
            FacilityStatus previousStatus = facility.getStatus();
            Long assignee = work.getAssignedEmployeeId();
            long closed = work.getId();
            work.complete(result, actor, now);
            facility.recordInspection(status, completedOn);
            persist(facility, previousStatus != status || !completedOn.equals(previousOn));
            var next = works.save(FacilityWork.open(id, assignee));
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            before.put("status", previousStatus.label()); before.put("lastInspectedOn", previousOn == null ? null : previousOn.toString());
            before.put("workId", Long.toString(closed));
            after.put("status", status.label()); after.put("lastInspectedOn", completedOn.toString());
            after.put("workId", Long.toString(closed)); after.put("nextWorkId", next.getId().toString()); after.put("result", result);
            audit.record(AuditEvent.change(actor, branch, ENTITY, id, "INSPECTION_COMPLETED", null, json.writeValueAsString(before),
                    json.writeValueAsString(after), requestId));
            var view = detail(current(id), state, today(now));
            requests.complete(actor, COMPLETE, scopeKey, key, fingerprint, 200, json.writeValueAsString(view), now.plus(Duration.ofHours(24)));
            return view;
        });
    }

    /** The facility row changes the version by itself; when only the open work changed, the version is advanced explicitly. */
    private void persist(Facility facility, boolean facilityChanged) {
        if (facilityChanged) repository.flush();
        else repository.incrementVersion(facility.getId());
    }

    private Facility current(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Facility locked(long id) {
        return repository.locked(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The facility's single open inspection work, read under the facility lock. A facility created by a command always has
     * one; if hand-edited data has none, one is opened here so the invariant holds from this point on.
     */
    private FacilityWork openWork(Facility facility) {
        var open = works.lockedOpenInspections(facility.getId());
        return open.isEmpty() ? works.save(FacilityWork.open(facility.getId(), null)) : open.get(open.size() - 1);
    }

    /** 404 hides facilities outside the reader's scope, so IDs are not an existence oracle. */
    private Facility visible(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        return repository.findById(id).filter(facility -> selected.contains(facility.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean canEdit(AuthorizationState state, Facility facility) {
        return policy.permits(state, facility.getBranchId(), BranchAction.WRITE_FACILITIES);
    }

    private boolean canComplete(AuthorizationState state, Facility facility, FacilityWork work) {
        return policy.permits(state, facility.getBranchId(), BranchAction.READ)
                && (Objects.equals(work == null ? null : work.getAssignedEmployeeId(), state.employeeId())
                || policy.permits(state, facility.getBranchId(), BranchAction.WRITE_FACILITIES));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employees.assignable(employee, branch))
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        if (Objects.equals(before, after)) return;
        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static String text(Long value) { return value == null ? null : Long.toString(value); }

    private static String reload(long id) { return "/api/facilities/" + id; }

    private static LocalDate today(Instant now) { return LocalDate.ofInstant(now, SEOUL); }

    /** Registration date when the facility was never inspected (M7-D06), otherwise last inspection plus the interval. */
    static LocalDate nextInspectionOn(Facility facility) {
        return facility.getLastInspectedOn() == null ? LocalDate.ofInstant(facility.getCreatedAt(), SEOUL)
                : facility.getLastInspectedOn().plusDays(facility.getInspectionIntervalDays());
    }

    /** Due when the next date is today or earlier, unless the facility is out of service. */
    static boolean inspectionDue(Facility facility, LocalDate today) {
        return facility.getStatus() != FacilityStatus.OUT_OF_SERVICE && !nextInspectionOn(facility).isAfter(today);
    }

    private FacilitySearchRepository.Filter filter(Set<Long> selected, String q, FacilityFilters filters, Instant now) {
        return new FacilitySearchRepository.Filter(selected, q, filters.type(), filters.assignee(), filters.workStatus(),
                filters.inspectionDue(), filters.status(), today(now), SEOUL.getRules().getOffset(now).getId());
    }

    /** What a list needs about its rows, fetched once per page: the types, the open works and the people's names. */
    private record Lookup(Map<Long, FacilityType> types, Map<Long, FacilityWork> open, Map<Long, String> names) {}

    private Lookup lookup(List<Facility> rows, List<Long> extraPeople) {
        Map<Long, FacilityType> typeById = new HashMap<>();
        types.findAllById(rows.stream().map(Facility::getTypeId).distinct().toList()).forEach(type -> typeById.put(type.getId(), type));
        Map<Long, FacilityWork> open = new HashMap<>();
        // Ordered by id, so the newest open work of a facility is the one kept, as in the list query's join.
        works.openInspections(rows.stream().map(Facility::getId).toList()).forEach(work -> open.put(work.getFacilityId(), work));
        var people = new ArrayList<>(extraPeople);
        open.values().stream().map(FacilityWork::getAssignedEmployeeId).filter(Objects::nonNull).forEach(people::add);
        return new Lookup(typeById, open, people.isEmpty() ? Map.of() : employees.names(people));
    }

    private List<FacilityListItem> items(List<Facility> rows, AuthorizationState state, LocalDate today) {
        if (rows.isEmpty()) return List.of();
        var lookup = lookup(rows, List.of());
        return rows.stream().map(facility -> {
            var work = lookup.open().get(facility.getId());
            return new FacilityListItem(facility.getId().toString(), Long.toString(facility.getBranchId()),
                    type(lookup, facility), facility.getName(), facility.getLocation(), facility.getStatus().label(),
                    facility.getInspectionIntervalDays(), facility.getLastInspectedOn(), nextInspectionOn(facility),
                    inspectionDue(facility, today), openWork(work, lookup.names()), canEdit(state, facility),
                    canComplete(state, facility, work), ApiVersion.fromDatabase(facility.getVersion()),
                    facility.getCreatedAt(), facility.getUpdatedAt());
        }).toList();
    }

    private FacilityDetail detail(Facility facility, AuthorizationState state, LocalDate today) {
        var finished = works.lastFinishedInspection(facility.getId());
        var lookup = lookup(List.of(facility), finished.map(FacilityWork::getCompletedBy).stream().filter(Objects::nonNull).toList());
        var work = lookup.open().get(facility.getId());
        // The result belongs to the latest finished inspection, dated by the facility's last inspection date.
        FacilityLastInspection last = finished.filter(row -> facility.getLastInspectedOn() != null)
                .map(row -> new FacilityLastInspection(facility.getLastInspectedOn(), row.getResultNote(),
                        row.getCompletedBy() == null ? null : actor(row.getCompletedBy(), lookup.names()))).orElse(null);
        return new FacilityDetail(facility.getId().toString(), Long.toString(facility.getBranchId()), type(lookup, facility),
                facility.getName(), facility.getLocation(), facility.getStatus().label(), facility.getInspectionIntervalDays(),
                facility.getLastInspectedOn(), nextInspectionOn(facility), inspectionDue(facility, today),
                openWork(work, lookup.names()), canEdit(state, facility), canComplete(state, facility, work),
                ApiVersion.fromDatabase(facility.getVersion()), facility.getCreatedAt(), facility.getUpdatedAt(),
                facility.getDescription(), last);
    }

    private static FacilityTypeView type(Lookup lookup, Facility facility) {
        return FacilityTypeService.view(lookup.types().get(facility.getTypeId()));
    }

    private static FacilityOpenWork openWork(FacilityWork work, Map<Long, String> names) {
        if (work == null) return null;
        return new FacilityOpenWork(work.getId().toString(), work.getStatus().label(),
                work.getAssignedEmployeeId() == null ? null : actor(work.getAssignedEmployeeId(), names), work.getOpenedAt());
    }

    private static ActorSummary actor(long id, Map<Long, String> names) {
        return new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
