package com.openstudy.modules.facility.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final Set<String> SUMMARY_SORT_FIELDS = Set.of("id");
    private static final String SUMMARY_DEFAULT_SORT = "id";
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_LOCATION_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 20000;
    private static final int MAX_RESULT_LENGTH = 5000;
    private static final int MAX_LABEL_LENGTH = 20;
    private static final int DATE_LENGTH = 10;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final FacilityRepository facilityRepository;
    private final FacilityWorkRepository facilityWorkRepository;
    private final FacilityTypeRepository facilityTypeRepository;
    private final FacilitySearchRepository facilitySearchRepository;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final Clock clock;

    /** What a list needs about its rows, fetched once per page: the types, the open works and the people's names. */
    private record Lookup(Map<Long, FacilityType> types, Map<Long, FacilityWork> open, Map<Long, String> names) {
    }

    @Transactional(readOnly = true)
    public PageResponse<FacilityListItem> list(long actor, String scope, ListQuery query, FacilityFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        FacilitySearchRepository.Sort sort = switch (query.sort()) {
            case "name" -> FacilitySearchRepository.Sort.NAME;
            case "createdAt" -> FacilitySearchRepository.Sort.CREATED_AT;
            default -> FacilitySearchRepository.Sort.NEXT_INSPECTION_ON;
        };
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        FacilitySearchRepository.Filter filter = toSearchFilter(selected, query.q(), filters, now);
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        FacilitySearchRepository.Page page = facilitySearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );
        List<FacilityListItem> items = toListItems(page.rows(), state, toBusinessDate(now));

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public FacilitySummary summary(long actor, String scope, String q, FacilityFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, SUMMARY_SORT_FIELDS, SUMMARY_DEFAULT_SORT).q();
        Map<Long, FacilitySearchRepository.BranchCounts> found = new HashMap<>();
        facilitySearchRepository.countByBranch(toSearchFilter(selected, text, filters, now))
                .forEach(row -> found.put(row.branchId(), row));
        List<FacilitySummary.BranchCounts> branches = new ArrayList<>();
        long total = 0;
        long normal = 0;
        long checkNeeded = 0;
        long broken = 0;
        long outOfService = 0;
        long inspectionDue = 0;

        for (long branch : new TreeSet<>(selected)) {
            FacilitySearchRepository.BranchCounts empty = new FacilitySearchRepository.BranchCounts(branch, 0, 0, 0, 0,
                    0, 0);
            FacilitySearchRepository.BranchCounts row = found.getOrDefault(branch, empty);
            branches.add(new FacilitySummary.BranchCounts(
                    Long.toString(branch),
                    row.total(),
                    row.normal(),
                    row.checkNeeded(),
                    row.broken(),
                    row.outOfService(),
                    row.inspectionDue()
            ));
            total += row.total();
            normal += row.normal();
            checkNeeded += row.checkNeeded();
            broken += row.broken();
            outOfService += row.outOfService();
            inspectionDue += row.inspectionDue();
        }

        FacilitySummary.Counts totals = new FacilitySummary.Counts(
                total,
                normal,
                checkNeeded,
                broken,
                outOfService,
                inspectionDue
        );

        return new FacilitySummary(now, totals, branches);
    }

    @Transactional(readOnly = true)
    public FacilityDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Facility facility = findVisibleFacility(state, id, scope);

        return toDetail(facility, state, toBusinessDate(clock.instant()));
    }

    public FacilityDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only(
                "branchId",
                "typeId",
                "name",
                "location",
                "description",
                "assigneeId",
                "inspectionIntervalDays",
                "lastInspectedOn",
                "lastInspectionResult",
                "status"
        );
        long branch = input.id("branchId");
        long typeId = input.id("typeId");
        String name = input.text("name", MAX_NAME_LENGTH);
        String location = FacilityInputs.parseOptionalText(body, "location", MAX_LOCATION_LENGTH);
        String description = FacilityInputs.parseOptionalText(body, "description", MAX_DESCRIPTION_LENGTH);
        Long assignee = FacilityInputs.parseOptionalId(body, "assigneeId");
        Integer interval = body.get("inspectionIntervalDays") == null
                ? null
                : FacilityInputs.parseDays(body.get("inspectionIntervalDays"), "inspectionIntervalDays");
        LocalDate lastOn = FacilityInputs.parseOptionalDate(body, "lastInspectedOn");
        String lastResult = body.get("lastInspectionResult") == null
                ? null
                : input.text("lastInspectionResult", MAX_RESULT_LENGTH);

        if (lastResult != null && lastOn == null) {
            throw ApiException.validation("lastInspectionResult", "점검 결과는 마지막 점검일과 함께 입력해 주세요.");
        }

        FacilityStatus status = body.get("status") == null
                ? FacilityStatus.NORMAL
                : FacilityInputs.parseStatus(input.text("status", MAX_LABEL_LENGTH), "status");
        requireValidKey(key);

        if (lastOn != null && lastOn.isAfter(toBusinessDate(clock.instant()))) {
            throw ApiException.validation("lastInspectedOn", "오늘 이후 날짜는 마지막 점검일로 입력할 수 없습니다.");
        }

        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(
                branch,
                typeId,
                name,
                location,
                description,
                assignee,
                interval,
                lastOn == null ? null : lastOn.toString(),
                lastResult,
                status.name()
        );
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.WRITE_FACILITIES, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();
            Optional<FacilityDetail> storedResult = findStoredResult(prior, now, fingerprint);

            if (storedResult.isPresent()) {
                return storedResult.get();
            }

            FacilityType type = facilityTypeRepository.findById(typeId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            if (assignee != null) {
                requireAssignable(assignee, branch);
            }

            int days = interval == null ? type.getDefaultInspectionDays() : interval;
            Facility newFacility = new Facility(branch, typeId, name, location, description, status, days, lastOn);
            Facility facility = facilityRepository.saveAndFlush(newFacility);

            // The facility and its first work are one transaction; a facility never exists without an open inspection.
            if (lastResult != null) {
                facilityWorkRepository.save(FacilityWork.finished(facility.getId(), assignee, lastResult, actor, now));
            }

            facilityWorkRepository.save(FacilityWork.open(facility.getId(), assignee));

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("typeId", Long.toString(typeId));
            after.put("name", name);
            after.put("location", location);
            after.put("description", description);
            after.put("status", status.label());
            after.put("inspectionIntervalDays", days);
            after.put("lastInspectedOn", lastOn == null ? null : lastOn.toString());
            after.put("assigneeId", assignee == null ? null : Long.toString(assignee));
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, facility.getId(), "CREATED", null,
                    objectMapper.writeValueAsString(after), requestId));

            FacilityDetail result = toDetail(facility, state, toBusinessDate(now));
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, scopeKey, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    public FacilityDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        boolean hasImmutableField = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (hasImmutableField) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD",
                    "지점과 마지막 점검일은 이 요청으로 바꿀 수 없습니다. 마지막 점검일은 점검 완료로만 바뀝니다.");
        }

        StrictInput input = new StrictInput(body).only(
                "version",
                "typeId",
                "name",
                "location",
                "description",
                "inspectionIntervalDays",
                "status",
                "assigneeId",
                "workStatus"
        );

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        Long typeId = input.has("typeId") ? Long.valueOf(input.id("typeId")) : null;
        String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : null;
        String location = FacilityInputs.parseOptionalText(body, "location", MAX_LOCATION_LENGTH);
        String description = FacilityInputs.parseOptionalText(body, "description", MAX_DESCRIPTION_LENGTH);
        Integer interval = input.has("inspectionIntervalDays")
                ? Integer.valueOf(FacilityInputs.parseDays(body.get("inspectionIntervalDays"),
                        "inspectionIntervalDays"))
                : null;
        FacilityStatus status = input.has("status")
                ? FacilityInputs.parseStatus(input.text("status", MAX_LABEL_LENGTH), "status")
                : null;
        Long assignee = FacilityInputs.parseOptionalId(body, "assigneeId");
        FacilityWorkStatus workStatus = input.has("workStatus")
                ? FacilityInputs.parseWorkStatus(input.text("workStatus", MAX_LABEL_LENGTH), "workStatus")
                : null;
        long branch = findVisibleFacility(authorizationService.current(actor), id, scope).getBranchId();

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.WRITE_FACILITIES,
                () -> facilityRepository.lock(id),
                state -> {
                    Facility facility = findLockedFacility(id);
                    ApiVersion.requireCurrent(version, facility.getVersion(), toReloadPath(id));
                    FacilityWork work = findOrOpenWork(facility);
                    long newType = typeId == null ? facility.getTypeId() : typeId;
                    String newName = name == null ? facility.getName() : name;
                    String newLocation = input.has("location") ? location : facility.getLocation();
                    String newDescription = input.has("description") ? description : facility.getDescription();
                    int newInterval = interval == null ? facility.getInspectionIntervalDays() : interval;
                    FacilityStatus newStatus = status == null ? facility.getStatus() : status;
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    putIfChanged("typeId", Long.toString(facility.getTypeId()), Long.toString(newType), before, after);
                    putIfChanged("name", facility.getName(), newName, before, after);
                    putIfChanged("location", facility.getLocation(), newLocation, before, after);
                    putIfChanged("description", facility.getDescription(), newDescription, before, after);
                    putIfChanged("inspectionIntervalDays", facility.getInspectionIntervalDays(), newInterval, before,
                            after);
                    putIfChanged("status", facility.getStatus().label(), newStatus.label(), before, after);
                    boolean facilityChanged = !before.isEmpty();
                    Long newAssignee = input.has("assigneeId") ? assignee : work.getAssignedEmployeeId();
                    FacilityWorkStatus newWorkStatus = workStatus == null ? work.getStatus() : workStatus;
                    putIfChanged("assigneeId", toIdText(work.getAssignedEmployeeId()), toIdText(newAssignee), before,
                            after);
                    putIfChanged("workStatus", work.getStatus().label(), newWorkStatus.label(), before, after);

                    // An identical request is not a change: no version bump, audit row or event.
                    if (before.isEmpty()) {
                        return toDetail(facility, state, toBusinessDate(clock.instant()));
                    }

                    if (newType != facility.getTypeId() && !facilityTypeRepository.existsById(newType)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }

                    boolean assigneeChanged = newAssignee != null
                            && !Objects.equals(newAssignee, work.getAssignedEmployeeId());

                    if (assigneeChanged) {
                        requireAssignable(newAssignee, branch);
                    }

                    if (facilityChanged) {
                        facility.change(newType, newName, newLocation, newDescription, newStatus, newInterval);
                    }

                    work.assign(newAssignee);
                    work.process(newWorkStatus);
                    persist(facility, facilityChanged);
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "UPDATED", null,
                            objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after),
                            requestId));

                    return toDetail(findCurrentFacility(id), state, toBusinessDate(clock.instant()));
                }
        );
    }

    /**
     * Closes the open inspection, records the facility's new condition and last inspection date, and opens the next
     * inspection for the same assignee, all in one transaction (M7-D03).
     */
    public FacilityDetail completeInspection(
            long actor,
            long id,
            String scope,
            String key,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body).only("version", "result", "completedOn", "facilityStatus");
        long version = input.version("version");
        String result = input.text("result", MAX_RESULT_LENGTH);
        LocalDate completedOn = FacilityInputs.parseDate(input.text("completedOn", DATE_LENGTH), "completedOn");
        FacilityStatus status = FacilityInputs.parseStatus(input.text("facilityStatus", MAX_LABEL_LENGTH),
                "facilityStatus");
        requireValidKey(key);
        long branch = findVisibleFacility(authorizationService.current(actor), id, scope).getBranchId();
        String scopeKey = Long.toString(id);
        List<Object> fingerprintInput = Arrays.asList(id, version, result, completedOn.toString(), status.name());
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.READ,
                () -> facilityRepository.lock(id),
                state -> {
                    Facility facility = findLockedFacility(id);
                    FacilityWork work = findOrOpenWork(facility);

                    // The assignee of the work being closed, or a facilities:write holder; both must still belong to the
                    // branch.
                    if (!canComplete(state, facility, work)) {
                        throw AuthorizationService.forbidden();
                    }

                    Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, COMPLETE, scopeKey, key);
                    Instant now = clock.instant();
                    Optional<FacilityDetail> storedResult = findStoredResult(prior, now, fingerprint);

                    if (storedResult.isPresent()) {
                        return storedResult.get();
                    }

                    ApiVersion.requireCurrent(version, facility.getVersion(), toReloadPath(id));

                    if (completedOn.isAfter(toBusinessDate(now))) {
                        throw ApiException.validation("completedOn", "오늘 이후 날짜는 점검 완료일로 입력할 수 없습니다.");
                    }

                    LocalDate previousOn = facility.getLastInspectedOn();

                    if (previousOn != null && completedOn.isBefore(previousOn)) {
                        throw ApiException.validation("completedOn", "마지막 점검일보다 이전 날짜는 입력할 수 없습니다.");
                    }

                    FacilityStatus previousStatus = facility.getStatus();
                    Long assignee = work.getAssignedEmployeeId();
                    long closed = work.getId();
                    work.complete(result, actor, now);
                    facility.recordInspection(status, completedOn);
                    boolean facilityChanged = previousStatus != status || !completedOn.equals(previousOn);
                    persist(facility, facilityChanged);
                    FacilityWork next = facilityWorkRepository.save(FacilityWork.open(id, assignee));

                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("status", previousStatus.label());
                    before.put("lastInspectedOn", previousOn == null ? null : previousOn.toString());
                    before.put("workId", Long.toString(closed));
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("status", status.label());
                    after.put("lastInspectedOn", completedOn.toString());
                    after.put("workId", Long.toString(closed));
                    after.put("nextWorkId", next.getId().toString());
                    after.put("result", result);
                    auditTrail.record(AuditEvent.change(actor, branch, ENTITY, id, "INSPECTION_COMPLETED", null,
                            objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after),
                            requestId));

                    FacilityDetail view = toDetail(findCurrentFacility(id), state, toBusinessDate(now));
                    String responseJson = objectMapper.writeValueAsString(view);
                    Instant expiresAt = now.plus(RESULT_RETENTION);
                    idempotencyStore.complete(actor, COMPLETE, scopeKey, key, fingerprint, OK, responseJson, expiresAt);

                    return view;
                }
        );
    }

    /** Registration date when the facility was never inspected (M7-D06), otherwise last inspection plus the interval. */
    static LocalDate calculateNextInspectionOn(Facility facility) {
        if (facility.getLastInspectedOn() == null) {
            return LocalDate.ofInstant(facility.getCreatedAt(), SEOUL);
        }

        return facility.getLastInspectedOn().plusDays(facility.getInspectionIntervalDays());
    }

    /** Due when the next date is today or earlier, unless the facility is out of service. */
    static boolean isInspectionDue(Facility facility, LocalDate today) {
        return facility.getStatus() != FacilityStatus.OUT_OF_SERVICE
                && !calculateNextInspectionOn(facility).isAfter(today);
    }

    /** A live record answers the first result or, for another input, a conflict; an expired one is forgotten. */
    private Optional<FacilityDetail> findStoredResult(
            Optional<IdempotencyRecord> prior,
            Instant now,
            byte[] fingerprint
    ) {
        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!now.isBefore(prior.get().getExpiresAt())) {
            idempotencyStore.discard(prior.get());

            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), FacilityDetail.class));
    }

    /** The facility row changes the version by itself; when only the open work changed, the version is advanced explicitly. */
    private void persist(Facility facility, boolean facilityChanged) {
        if (facilityChanged) {
            facilityRepository.flush();
        } else {
            facilityRepository.incrementVersion(facility.getId());
        }
    }

    private Facility findCurrentFacility(long id) {
        return facilityRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Facility findLockedFacility(long id) {
        return facilityRepository.findLocked(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The facility's single open inspection work, read under the facility lock. A facility created by a command always has
     * one; if hand-edited data has none, one is opened here so the invariant holds from this point on.
     */
    private FacilityWork findOrOpenWork(Facility facility) {
        List<FacilityWork> open = facilityWorkRepository.findLockedOpenInspections(facility.getId());

        if (open.isEmpty()) {
            return facilityWorkRepository.save(FacilityWork.open(facility.getId(), null));
        }

        return open.get(open.size() - 1);
    }

    /** 404 hides facilities outside the reader's scope, so IDs are not an existence oracle. */
    private Facility findVisibleFacility(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        return facilityRepository.findById(id)
                .filter(facility -> selected.contains(facility.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean canEdit(AuthorizationState state, Facility facility) {
        return authorizationService.permits(state, facility.getBranchId(), BranchAction.WRITE_FACILITIES);
    }

    private boolean canComplete(AuthorizationState state, Facility facility, FacilityWork work) {
        Long workAssignee = work == null ? null : work.getAssignedEmployeeId();

        return authorizationService.permits(state, facility.getBranchId(), BranchAction.READ)
                && (Objects.equals(workAssignee, state.employeeId())
                || authorizationService.permits(state, facility.getBranchId(), BranchAction.WRITE_FACILITIES));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employeeAccountService.assignable(employee, branch)) {
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
        }
    }

    private FacilitySearchRepository.Filter toSearchFilter(
            Set<Long> selected,
            String q,
            FacilityFilters filters,
            Instant now
    ) {
        return new FacilitySearchRepository.Filter(
                selected,
                q,
                filters.type(),
                filters.assignee(),
                filters.workStatus(),
                filters.inspectionDue(),
                filters.status(),
                toBusinessDate(now),
                SEOUL.getRules().getOffset(now).getId()
        );
    }

    private Lookup findLookup(List<Facility> rows, List<Long> extraPeople) {
        List<Long> typeIds = rows.stream()
                .map(Facility::getTypeId)
                .distinct()
                .toList();
        Map<Long, FacilityType> typeById = new HashMap<>();
        facilityTypeRepository.findAllById(typeIds).forEach(type -> typeById.put(type.getId(), type));
        List<Long> facilityIds = rows.stream()
                .map(Facility::getId)
                .toList();
        Map<Long, FacilityWork> open = new HashMap<>();
        // Ordered by id, so the newest open work of a facility is the one kept, as in the list query's join.
        facilityWorkRepository.findOpenInspections(facilityIds).forEach(work -> open.put(work.getFacilityId(), work));
        List<Long> people = new ArrayList<>(extraPeople);
        open.values()
                .stream()
                .map(FacilityWork::getAssignedEmployeeId)
                .filter(Objects::nonNull)
                .forEach(people::add);
        Map<Long, String> names = people.isEmpty() ? Map.of() : employeeAccountService.names(people);

        return new Lookup(typeById, open, names);
    }

    private List<FacilityListItem> toListItems(List<Facility> rows, AuthorizationState state, LocalDate today) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Lookup lookup = findLookup(rows, List.of());

        return rows.stream()
                .map(facility -> toListItem(facility, lookup, state, today))
                .toList();
    }

    private FacilityListItem toListItem(Facility facility, Lookup lookup, AuthorizationState state, LocalDate today) {
        FacilityWork work = lookup.open().get(facility.getId());

        return new FacilityListItem(
                facility.getId().toString(),
                Long.toString(facility.getBranchId()),
                toTypeView(lookup, facility),
                facility.getName(),
                facility.getLocation(),
                facility.getStatus().label(),
                facility.getInspectionIntervalDays(),
                facility.getLastInspectedOn(),
                calculateNextInspectionOn(facility),
                isInspectionDue(facility, today),
                toOpenWork(work, lookup.names()),
                canEdit(state, facility),
                canComplete(state, facility, work),
                ApiVersion.fromDatabase(facility.getVersion()),
                facility.getCreatedAt(),
                facility.getUpdatedAt()
        );
    }

    private FacilityDetail toDetail(Facility facility, AuthorizationState state, LocalDate today) {
        Optional<FacilityWork> finished = facilityWorkRepository.findLastFinishedInspection(facility.getId());
        List<Long> finishers = finished.map(FacilityWork::getCompletedBy)
                .stream()
                .filter(Objects::nonNull)
                .toList();
        Lookup lookup = findLookup(List.of(facility), finishers);
        FacilityWork work = lookup.open().get(facility.getId());
        // The result belongs to the latest finished inspection, dated by the facility's last inspection date.
        FacilityLastInspection last = finished.filter(row -> facility.getLastInspectedOn() != null)
                .map(row -> toLastInspection(facility, row, lookup.names()))
                .orElse(null);

        return new FacilityDetail(
                facility.getId().toString(),
                Long.toString(facility.getBranchId()),
                toTypeView(lookup, facility),
                facility.getName(),
                facility.getLocation(),
                facility.getStatus().label(),
                facility.getInspectionIntervalDays(),
                facility.getLastInspectedOn(),
                calculateNextInspectionOn(facility),
                isInspectionDue(facility, today),
                toOpenWork(work, lookup.names()),
                canEdit(state, facility),
                canComplete(state, facility, work),
                ApiVersion.fromDatabase(facility.getVersion()),
                facility.getCreatedAt(),
                facility.getUpdatedAt(),
                facility.getDescription(),
                last
        );
    }

    private static FacilityLastInspection toLastInspection(
            Facility facility,
            FacilityWork row,
            Map<Long, String> names
    ) {
        ActorSummary completedBy = row.getCompletedBy() == null ? null : toActorSummary(row.getCompletedBy(), names);

        return new FacilityLastInspection(facility.getLastInspectedOn(), row.getResultNote(), completedBy);
    }

    private static FacilityTypeView toTypeView(Lookup lookup, Facility facility) {
        return FacilityTypeService.toView(lookup.types().get(facility.getTypeId()));
    }

    private static FacilityOpenWork toOpenWork(FacilityWork work, Map<Long, String> names) {
        if (work == null) {
            return null;
        }

        ActorSummary assignee = work.getAssignedEmployeeId() == null
                ? null
                : toActorSummary(work.getAssignedEmployeeId(), names);

        return new FacilityOpenWork(work.getId().toString(), work.getStatus().label(), assignee, work.getOpenedAt());
    }

    private static ActorSummary toActorSummary(long id, Map<Long, String> names) {
        return new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void putIfChanged(
            String field,
            Object before,
            Object after,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues
    ) {
        if (Objects.equals(before, after)) {
            return;
        }

        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static String toIdText(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static String toReloadPath(long id) {
        return "/api/facilities/" + id;
    }

    private static LocalDate toBusinessDate(Instant now) {
        return LocalDate.ofInstant(now, SEOUL);
    }

    private static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
