package com.openstudy.modules.home.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.branch.dto.response.ClosureNotice;
import com.openstudy.modules.branch.service.BranchClosureService;
import com.openstudy.modules.facility.dto.response.FacilityListItem;
import com.openstudy.modules.facility.service.FacilityFilters;
import com.openstudy.modules.facility.service.FacilityService;
import com.openstudy.modules.home.dto.response.HomeSummary;
import com.openstudy.modules.home.dto.response.HomeSummary.ActorSummary;
import com.openstudy.modules.home.dto.response.HomeSummary.Link;
import com.openstudy.modules.home.dto.response.HomeSummary.Ratio;
import com.openstudy.modules.home.dto.response.HomeSummary.Source;
import com.openstudy.modules.inquiry.dto.response.InquiryListItem;
import com.openstudy.modules.inquiry.dto.response.InquirySummary;
import com.openstudy.modules.inquiry.service.InquiryFilters;
import com.openstudy.modules.inquiry.service.InquiryService;
import com.openstudy.modules.notice.service.NoticeService;
import com.openstudy.modules.seat.dto.response.SeatCounts;
import com.openstudy.modules.seat.service.SeatService;
import com.openstudy.modules.task.dto.response.TaskListItem;
import com.openstudy.modules.task.dto.response.TaskSummary;
import com.openstudy.modules.task.service.TaskFilters;
import com.openstudy.modules.task.service.TaskService;
import com.openstudy.modules.workforce.dto.response.ShiftColleague;
import com.openstudy.modules.workforce.dto.response.ShiftView;
import com.openstudy.modules.workforce.service.ShiftService;

/**
 * GET /home/summary (aggregation-api.md "운영 홈 요약", M9-D08). Each card calls the public service of its source feature
 * with the requester's own ID, so it can never count or show a row the source list would hide. Nothing is aggregated
 * again here except sums of the sources' own counts.
 */
@Service
@RequiredArgsConstructor
public class HomeSummaryService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int CARD_ITEMS = 5;

    private static final String ALL_SCOPE = "all";
    private static final String OPEN_TODO = "할 일";
    private static final String OPEN_IN_PROGRESS = "진행 중";
    private static final String DONE = "완료";
    private static final String INQUIRY_RECEIVED = "접수";
    private static final String INQUIRY_IN_PROGRESS = "처리 중";
    private static final String SHIFT_PLANNED = "예정";
    private static final String PUBLISHED = "게시 중";
    private static final int SHIFT_PAGE_SIZE = 100;
    private static final String EMPLOYEE_ID_FORMAT = "[1-9][0-9]{0,18}";

    private final AuthorizationService authorizationService;
    private final SeatService seatService;
    private final TaskService taskService;
    private final InquiryService inquiryService;
    private final FacilityService facilityService;
    private final ShiftService shiftService;
    private final NoticeService noticeService;
    private final BranchClosureService branchClosureService;
    private final Clock clock;

    /** The task condition after the M9-D08 defaults: OWNER sees the scope, everyone else their own tasks. */
    record TaskCondition(boolean mine, String assigneeId, boolean defaulted) {

        String toMineFlag() {
            return mine ? "true" : null;
        }
    }

    record Day(LocalDate date, Instant start, Instant end, Instant asOf) {
    }

    @Transactional(readOnly = true)
    public HomeSummary getSummary(long actor, String scope, String mine, String assigneeId) {
        Boolean requestedMine = parseFlag(mine);
        String assignee = assigneeId == null ? null : parseEmployeeId(assigneeId);

        if (Boolean.TRUE.equals(requestedMine) && assignee != null) {
            throw ApiException.validation("assigneeId", "mine=true와 담당자를 함께 지정할 수 없습니다.");
        }

        AuthorizationState state = authorizationService.current(actor);
        String selected = scope == null ? ALL_SCOPE : scope;
        Set<Long> branches = new TreeSet<>(authorizationService.scope(state, selected, BranchAction.READ));
        boolean defaulted = requestedMine == null && assignee == null;
        boolean mineByDefault = requestedMine != null ? requestedMine : !state.owner();
        TaskCondition condition = new TaskCondition(assignee == null && mineByDefault, assignee, defaulted);

        Instant asOf = clock.instant();
        LocalDate date = LocalDate.ofInstant(asOf, SEOUL);
        Instant dayStart = date.atStartOfDay(SEOUL).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
        String listBranch = ALL_SCOPE.equals(selected) ? null : selected;
        Day day = new Day(date, dayStart, dayEnd, asOf);

        HomeSummary.Meta meta = new HomeSummary.Meta(
                branches.stream().map(String::valueOf).toList(),
                ALL_SCOPE.equals(selected),
                date,
                dayStart,
                dayEnd,
                SEOUL.getId(),
                asOf,
                new HomeSummary.TaskFilter(condition.mine(), condition.assigneeId(), defaulted)
        );

        // The cards read their sources in this order, as before.
        HomeSummary.SeatCard seats = toSeatCard(actor, selected, listBranch);
        HomeSummary.TaskCard tasks = toTaskCard(actor, selected, listBranch, condition, day);
        HomeSummary.InquiryCard inquiries = toInquiryCard(actor, selected, listBranch);
        HomeSummary.FacilityCard facilities = toFacilityCard(actor, selected, listBranch);
        HomeSummary.ShiftCard shifts = toShiftCard(actor, state, selected, listBranch, branches, day);
        HomeSummary.NoticeCard notices = toNoticeCard(actor, selected);
        List<ClosureNotice> closures = branchClosureService.upcoming(branches, date);

        return new HomeSummary(meta, seats, tasks, inquiries, facilities, shifts, notices, closures);
    }

    // ---- seats ---------------------------------------------------------------------------------------------------

    private HomeSummary.SeatCard toSeatCard(long actor, String scope, String listBranch) {
        List<SeatCounts> counts = seatService.occupancy(actor, scope).items();
        long total = 0;
        long inUse = 0;
        long available = 0;
        long blocked = 0;
        List<HomeSummary.SeatBranch> branches = new ArrayList<>();

        for (SeatCounts row : counts) {
            total += row.total();
            inUse += row.inUse();
            available += row.available();
            blocked += row.blocked();
            Ratio occupancy = Ratio.of(row.inUse(), row.total());
            branches.add(new HomeSummary.SeatBranch(row.branchId(), row.total(), row.inUse(), occupancy));
        }

        branches.sort(Comparator.comparing(branch -> Long.parseLong(branch.branchId())));

        return new HomeSummary.SeatCard(
                total,
                inUse,
                available,
                blocked,
                Ratio.of(inUse, total),
                branches,
                toLink("seat-board", listBranch, null, Map.of()),
                toSource("getSeatOccupancy", toQuery("scope", scope), null)
        );
    }

    // ---- tasks ---------------------------------------------------------------------------------------------------

    private HomeSummary.TaskCard toTaskCard(
            long actor,
            String scope,
            String listBranch,
            TaskCondition condition,
            Day day
    ) {
        HomeSummary.TaskCounts today = countTasks(actor, scope, condition.assigneeId(), condition.toMineFlag(), day);
        HomeSummary.TaskCounts mine = countTasks(actor, scope, null, "true", day);
        String due = day.end().toString();
        List<HomeSummary.TaskItem> open = Stream.of(OPEN_TODO, OPEN_IN_PROGRESS)
                .flatMap(status -> findOpenTasks(actor, scope, condition, status, due).stream())
                .sorted(Comparator.comparing(TaskListItem::dueAt).thenComparing(task -> Long.parseLong(task.id())))
                .limit(CARD_ITEMS)
                .map(HomeSummaryService::toTaskItem)
                .toList();

        Map<String, String> filter = toTaskQuery(scope, condition);
        Map<String, String> listQuery = new LinkedHashMap<>(filter);
        listQuery.put("dueTo", due);
        String completedFilter = "completedAt ∈ [" + day.start() + ", " + day.end() + ")"
                + " (GET /tasks has no completion time filter)";
        List<Source> sources = List.of(
                toSource(
                        "listTasks",
                        copyWith(listQuery, "status", OPEN_TODO),
                        "today.open = total of this query + total with status=진행 중"
                ),
                toSource("listTasks", copyWith(listQuery, "status", OPEN_IN_PROGRESS), null),
                toSource("getTaskSummary", listQuery, "today.overdue = totals.overdue"),
                toSource("listTasks", copyWith(filter, "status", DONE), completedFilter)
        );

        Map<String, String> linkQuery = new LinkedHashMap<>(filter);
        linkQuery.remove("scope");

        return new HomeSummary.TaskCard(today, mine, open, toLink("task-list", listBranch, null, linkQuery), sources);
    }

    private List<TaskListItem> findOpenTasks(
            long actor,
            String scope,
            TaskCondition condition,
            String status,
            String due
    ) {
        ListQuery query = new ListQuery(1, CARD_ITEMS, "", "dueAt", ListQuery.Direction.ASC);
        TaskFilters filters = TaskFilters.parse(
                status,
                condition.assigneeId(),
                condition.toMineFlag(),
                null,
                due,
                null
        );

        return taskService.list(actor, scope, query, filters).items();
    }

    private HomeSummary.TaskCounts countTasks(long actor, String scope, String assignee, String mine, Day day) {
        TaskFilters dueToday = TaskFilters.parse(null, assignee, mine, null, day.end().toString(), null);
        TaskSummary.Counts summary = taskService.summary(actor, scope, null, dueToday).totals();
        long open = summary.todo() + summary.inProgress();

        TaskFilters anyDue = TaskFilters.parse(null, assignee, mine, null, null, null);
        long completed = taskService.completedBetween(actor, scope, anyDue, day.start(), day.end());
        long total = open + completed;

        return new HomeSummary.TaskCounts(total, open, summary.overdue(), completed, Ratio.of(completed, total));
    }

    private static HomeSummary.TaskItem toTaskItem(TaskListItem task) {
        ActorSummary assignee = task.assignee() == null
                ? null
                : new ActorSummary(task.assignee().id(), task.assignee().name());

        return new HomeSummary.TaskItem(
                task.id(),
                task.branchId(),
                task.title(),
                task.status(),
                task.priority(),
                task.dueAt(),
                assignee,
                task.overdue(),
                toLink("task-detail", task.branchId(), task.id(), Map.of())
        );
    }

    private static Map<String, String> toTaskQuery(String scope, TaskCondition condition) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("scope", scope);

        if (condition.assigneeId() != null) {
            query.put("assigneeId", condition.assigneeId());
        } else {
            query.put("mine", Boolean.toString(condition.mine()));
        }

        return query;
    }

    // ---- inquiries -----------------------------------------------------------------------------------------------

    private HomeSummary.InquiryCard toInquiryCard(long actor, String scope, String listBranch) {
        InquirySummary.Counts totals = inquiryService
                .summary(actor, scope, null, InquiryFilters.parse(null, null, null))
                .totals();
        List<HomeSummary.InquiryItem> items = Stream.of(INQUIRY_RECEIVED, INQUIRY_IN_PROGRESS)
                .flatMap(status -> findInquiries(actor, scope, status).stream())
                .sorted(Comparator.comparing(InquiryListItem::createdAt)
                        .thenComparing(item -> Long.parseLong(item.id()))
                        .reversed())
                .limit(CARD_ITEMS)
                .map(item -> new HomeSummary.InquiryItem(
                        item.id(),
                        item.branchId(),
                        item.subject(),
                        item.status(),
                        item.createdAt(),
                        toLink("inquiry-detail", item.branchId(), item.id(), Map.of())
                ))
                .toList();

        return new HomeSummary.InquiryCard(
                totals.unresolved(),
                totals.received(),
                totals.inProgress(),
                items,
                toLink("inquiry-list", listBranch, null, Map.of()),
                toSource("getInquirySummary", toQuery("scope", scope), null)
        );
    }

    private List<InquiryListItem> findInquiries(long actor, String scope, String status) {
        ListQuery query = new ListQuery(1, CARD_ITEMS, "", "createdAt", ListQuery.Direction.DESC);

        return inquiryService.list(actor, scope, query, InquiryFilters.parse(status, null, null)).items();
    }

    // ---- facilities ----------------------------------------------------------------------------------------------

    private HomeSummary.FacilityCard toFacilityCard(long actor, String scope, String listBranch) {
        long due = facilityService
                .summary(actor, scope, null, FacilityFilters.parse(null, null, null, null, null))
                .totals()
                .inspectionDue();
        ListQuery query = new ListQuery(1, CARD_ITEMS, "", "nextInspectionOn", ListQuery.Direction.ASC);
        FacilityFilters inspectionDue = FacilityFilters.parse(null, null, null, "true", null);
        List<HomeSummary.FacilityItem> items = facilityService.list(actor, scope, query, inspectionDue)
                .items()
                .stream()
                .sorted(Comparator.comparing(FacilityListItem::nextInspectionOn)
                        .thenComparing(item -> Long.parseLong(item.id())))
                .map(item -> new HomeSummary.FacilityItem(
                        item.id(),
                        item.branchId(),
                        item.name(),
                        item.status(),
                        item.nextInspectionOn(),
                        toLink("facility-detail", item.branchId(), item.id(), Map.of())
                ))
                .toList();

        return new HomeSummary.FacilityCard(
                due,
                items,
                toLink("facility-list", listBranch, null, Map.of("inspectionDue", "true")),
                toSource("getFacilitySummary", toQuery("scope", scope), "inspectionDue = totals.inspectionDue")
        );
    }

    // ---- shifts --------------------------------------------------------------------------------------------------

    private HomeSummary.ShiftCard toShiftCard(
            long actor,
            AuthorizationState state,
            String scope,
            String listBranch,
            Set<Long> branches,
            Day day
    ) {
        String from = day.date().minusDays(1).toString();
        String to = day.date().toString();
        List<ShiftView> overlapping = findShiftsOverlapping(actor, scope, from, to, day);
        overlapping.sort(Comparator.comparing(ShiftView::startsAt).thenComparing(shift -> Long.parseLong(shift.id())));
        List<HomeSummary.ShiftItem> items = overlapping.stream()
                .limit(CARD_ITEMS)
                .map(HomeSummaryService::toShiftItem)
                .toList();

        // Colleagues (2026-11-05 user decision): only branches of this scope where the requester is an active member.
        Set<Long> memberOf = findActiveMemberBranches(state, branches);
        List<ShiftColleague> colleagues = shiftService.colleagues(actor, memberOf, day.start(), day.end());
        List<HomeSummary.ShiftColleague> shown = colleagues.stream()
                .limit(CARD_ITEMS)
                .map(shift -> new HomeSummary.ShiftColleague(
                        Long.toString(shift.branchId()),
                        shift.employeeName(),
                        shift.startsAt(),
                        shift.endsAt()
                ))
                .toList();

        Map<String, String> sourceQuery = new LinkedHashMap<>();
        sourceQuery.put("scope", scope);
        sourceQuery.put("status", SHIFT_PLANNED);
        sourceQuery.put("from", from);
        sourceQuery.put("to", to);

        return new HomeSummary.ShiftCard(
                overlapping.size(),
                items,
                colleagues.size(),
                shown,
                toLink("shift-list", listBranch, null, Map.of("from", to, "to", to)),
                toSource("listShifts", sourceQuery, "startsAt < " + day.end() + " ∧ endsAt > " + day.start())
        );
    }

    /** A shift is at most 24 hours (M8-D03), so one overlapping today started yesterday or today. */
    private List<ShiftView> findShiftsOverlapping(long actor, String scope, String from, String to, Day day) {
        List<ShiftView> overlapping = new ArrayList<>();

        for (long page = 1; ; page++) {
            ListQuery query = new ListQuery(page, SHIFT_PAGE_SIZE, "", "id", ListQuery.Direction.ASC);
            PageResponse<ShiftView> result = shiftService.list(actor, scope, query, null, null, null, from, to);
            result.items()
                    .stream()
                    .filter(shift -> shift.startsAt().isBefore(day.end()) && shift.endsAt().isAfter(day.start()))
                    .forEach(overlapping::add);

            boolean lastPage = page * SHIFT_PAGE_SIZE >= result.meta().total() || result.items().isEmpty();

            if (lastPage) {
                break;
            }
        }

        return overlapping;
    }

    private static Set<Long> findActiveMemberBranches(AuthorizationState state, Set<Long> branches) {
        Set<Long> memberOf = new TreeSet<>();

        for (long branch : branches) {
            AuthorizationState.Membership membership = state.memberships().get(branch);

            if (membership != null && "ACTIVE".equals(membership.status())) {
                memberOf.add(branch);
            }
        }

        return memberOf;
    }

    private static HomeSummary.ShiftItem toShiftItem(ShiftView shift) {
        return new HomeSummary.ShiftItem(
                shift.id(),
                shift.branchId(),
                new ActorSummary(shift.employee().id(), shift.employee().name()),
                shift.startsAt(),
                shift.endsAt(),
                toLink("shift-detail", shift.branchId(), shift.id(), Map.of())
        );
    }

    // ---- notices -------------------------------------------------------------------------------------------------

    private HomeSummary.NoticeCard toNoticeCard(long actor, String scope) {
        ListQuery query = new ListQuery(1, CARD_ITEMS, "", "id", ListQuery.Direction.DESC);
        List<HomeSummary.NoticeItem> items = noticeService.list(actor, scope, query, PUBLISHED)
                .items()
                .stream()
                .map(notice -> new HomeSummary.NoticeItem(
                        notice.id(),
                        notice.title(),
                        notice.pinned(),
                        notice.publishedAt(),
                        toLink("notice-detail", null, notice.id(), Map.of())
                ))
                .toList();

        Map<String, String> sourceQuery = new LinkedHashMap<>();
        sourceQuery.put("scope", scope);
        sourceQuery.put("status", PUBLISHED);
        sourceQuery.put("page", "1");
        sourceQuery.put("pageSize", Integer.toString(CARD_ITEMS));

        return new HomeSummary.NoticeCard(
                items,
                toLink("notice-list", null, null, Map.of("status", PUBLISHED)),
                toSource("listNotices", sourceQuery, null)
        );
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Link toLink(String screen, String branchId, String targetId, Map<String, String> query) {
        return new Link(screen, branchId, targetId, Map.copyOf(query));
    }

    private static Source toSource(String operationId, Map<String, String> query, String clientFilter) {
        return new Source(operationId, new LinkedHashMap<>(query), clientFilter);
    }

    private static Map<String, String> toQuery(String key, String value) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(key, value);

        return query;
    }

    private static Map<String, String> copyWith(Map<String, String> base, String key, String value) {
        Map<String, String> query = new LinkedHashMap<>(base);
        query.put(key, value);

        return query;
    }

    private static Boolean parseFlag(String value) {
        if (value == null) {
            return null;
        }

        if ("true".equals(value)) {
            return true;
        }

        if ("false".equals(value)) {
            return false;
        }

        throw ApiException.validation("mine", "true 또는 false를 입력해 주세요.");
    }

    private static String parseEmployeeId(String value) {
        if (!value.matches(EMPLOYEE_ID_FORMAT)) {
            throw ApiException.validation("assigneeId", "올바른 직원 ID를 입력해 주세요.");
        }

        try {
            return Long.toString(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw ApiException.validation("assigneeId", "올바른 직원 ID를 입력해 주세요.");
        }
    }
}
