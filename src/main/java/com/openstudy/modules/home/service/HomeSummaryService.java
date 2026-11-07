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
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.facility.dto.response.FacilityListItem;
import com.openstudy.modules.facility.service.FacilityFilters;
import com.openstudy.modules.facility.service.FacilityService;
import com.openstudy.modules.home.dto.response.HomeSummary;
import com.openstudy.modules.home.dto.response.HomeSummary.ActorSummary;
import com.openstudy.modules.home.dto.response.HomeSummary.Link;
import com.openstudy.modules.home.dto.response.HomeSummary.Ratio;
import com.openstudy.modules.home.dto.response.HomeSummary.Source;
import com.openstudy.modules.inquiry.dto.response.InquiryListItem;
import com.openstudy.modules.inquiry.service.InquiryFilters;
import com.openstudy.modules.inquiry.service.InquiryService;
import com.openstudy.modules.notice.service.NoticeService;
import com.openstudy.modules.seat.service.SeatService;
import com.openstudy.modules.task.dto.response.TaskListItem;
import com.openstudy.modules.task.service.TaskFilters;
import com.openstudy.modules.task.service.TaskService;
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
    private static final String OPEN_TODO = "할 일";
    private static final String OPEN_IN_PROGRESS = "진행 중";
    private static final String PUBLISHED = "게시 중";

    private final AuthorizationService policy;
    private final SeatService seats;
    private final TaskService tasks;
    private final InquiryService inquiries;
    private final FacilityService facilities;
    private final ShiftService shifts;
    private final NoticeService notices;
    private final Clock clock;

    /** The task condition after the M9-D08 defaults: OWNER sees the scope, everyone else their own tasks. */
    record TaskCondition(boolean mine, String assigneeId, boolean defaulted) {
        String mineFlag() { return mine ? "true" : null; }
    }

    @Transactional(readOnly = true)
    public HomeSummary summary(long actor, String scope, String mine, String assigneeId) {
        Boolean requestedMine = flag(mine);
        String assignee = assigneeId == null ? null : id(assigneeId);
        if (Boolean.TRUE.equals(requestedMine) && assignee != null)
            throw ApiException.validation("assigneeId", "mine=true와 담당자를 함께 지정할 수 없습니다.");
        var state = policy.current(actor);
        String selected = scope == null ? "all" : scope;
        Set<Long> branches = new TreeSet<>(policy.scope(state, selected, BranchAction.READ));
        boolean defaulted = requestedMine == null && assignee == null;
        var condition = new TaskCondition(assignee == null && (requestedMine != null ? requestedMine : !state.owner()), assignee,
                defaulted);

        Instant asOf = clock.instant();
        LocalDate date = LocalDate.ofInstant(asOf, SEOUL);
        Instant dayStart = date.atStartOfDay(SEOUL).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
        String listBranch = "all".equals(selected) ? null : selected;
        var day = new Day(date, dayStart, dayEnd, asOf);

        var meta = new HomeSummary.Meta(branches.stream().map(String::valueOf).toList(), "all".equals(selected), date, dayStart,
                dayEnd, SEOUL.getId(), asOf, new HomeSummary.TaskFilter(condition.mine(), condition.assigneeId(), defaulted));
        return new HomeSummary(meta, seatCard(actor, selected, listBranch), taskCard(actor, selected, listBranch, condition, day),
                inquiryCard(actor, selected, listBranch), facilityCard(actor, selected, listBranch),
                shiftCard(actor, state, selected, listBranch, branches, day), noticeCard(actor, selected));
    }

    record Day(LocalDate date, Instant start, Instant end, Instant asOf) {}

    // ---- seats ---------------------------------------------------------------------------------------------------

    private HomeSummary.SeatCard seatCard(long actor, String scope, String listBranch) {
        var counts = seats.occupancy(actor, scope).items();
        long total = 0, inUse = 0, available = 0, blocked = 0;
        var branches = new ArrayList<HomeSummary.SeatBranch>();
        for (var row : counts) {
            total += row.total(); inUse += row.inUse(); available += row.available(); blocked += row.blocked();
            branches.add(new HomeSummary.SeatBranch(row.branchId(), row.total(), row.inUse(), Ratio.of(row.inUse(), row.total())));
        }
        branches.sort(Comparator.comparing(branch -> Long.parseLong(branch.branchId())));
        return new HomeSummary.SeatCard(total, inUse, available, blocked, Ratio.of(inUse, total), branches,
                link("seat-board", listBranch, null, Map.of()), source("getSeatOccupancy", query("scope", scope), null));
    }

    // ---- tasks ---------------------------------------------------------------------------------------------------

    private HomeSummary.TaskCard taskCard(long actor, String scope, String listBranch, TaskCondition condition, Day day) {
        var today = taskCounts(actor, scope, condition.assigneeId(), condition.mineFlag(), day);
        var mine = taskCounts(actor, scope, null, "true", day);
        String due = day.end().toString();
        var open = Stream.of(OPEN_TODO, OPEN_IN_PROGRESS)
                .flatMap(status -> tasks.list(actor, scope, new ListQuery(1, CARD_ITEMS, "", "dueAt", ListQuery.Direction.ASC),
                        TaskFilters.parse(status, condition.assigneeId(), condition.mineFlag(), null, due, null)).items().stream())
                .sorted(Comparator.comparing(TaskListItem::dueAt).thenComparing(task -> Long.parseLong(task.id())))
                .limit(CARD_ITEMS)
                .map(task -> new HomeSummary.TaskItem(task.id(), task.branchId(), task.title(), task.status(), task.priority(),
                        task.dueAt(), task.assignee() == null ? null : new ActorSummary(task.assignee().id(), task.assignee().name()),
                        task.overdue(), link("task-detail", task.branchId(), task.id(), Map.of())))
                .toList();
        var filter = taskQuery(scope, condition);
        var listQuery = new LinkedHashMap<>(filter);
        listQuery.put("dueTo", due);
        var sources = List.of(
                source("listTasks", with(listQuery, "status", OPEN_TODO), "today.open = total of this query + total with status=진행 중"),
                source("listTasks", with(listQuery, "status", OPEN_IN_PROGRESS), null),
                source("getTaskSummary", listQuery, "today.overdue = totals.overdue"),
                source("listTasks", with(filter, "status", "완료"),
                        "completedAt ∈ [" + day.start() + ", " + day.end() + ") (GET /tasks has no completion time filter)"));
        var linkQuery = new LinkedHashMap<>(filter);
        linkQuery.remove("scope");
        return new HomeSummary.TaskCard(today, mine, open, link("task-list", listBranch, null, linkQuery), sources);
    }

    private HomeSummary.TaskCounts taskCounts(long actor, String scope, String assignee, String mine, Day day) {
        var dueToday = TaskFilters.parse(null, assignee, mine, null, day.end().toString(), null);
        var summary = tasks.summary(actor, scope, null, dueToday).totals();
        long open = summary.todo() + summary.inProgress();
        long completed = tasks.completedBetween(actor, scope, TaskFilters.parse(null, assignee, mine, null, null, null),
                day.start(), day.end());
        long total = open + completed;
        return new HomeSummary.TaskCounts(total, open, summary.overdue(), completed, Ratio.of(completed, total));
    }

    private static Map<String, String> taskQuery(String scope, TaskCondition condition) {
        var query = new LinkedHashMap<String, String>();
        query.put("scope", scope);
        if (condition.assigneeId() != null) query.put("assigneeId", condition.assigneeId());
        else query.put("mine", Boolean.toString(condition.mine()));
        return query;
    }

    // ---- inquiries -----------------------------------------------------------------------------------------------

    private HomeSummary.InquiryCard inquiryCard(long actor, String scope, String listBranch) {
        var totals = inquiries.summary(actor, scope, null, InquiryFilters.parse(null, null, null)).totals();
        var items = Stream.of("접수", "처리 중")
                .flatMap(status -> inquiries.list(actor, scope,
                        new ListQuery(1, CARD_ITEMS, "", "createdAt", ListQuery.Direction.DESC),
                        InquiryFilters.parse(status, null, null)).items().stream())
                .sorted(Comparator.comparing(InquiryListItem::createdAt).thenComparing(item -> Long.parseLong(item.id())).reversed())
                .limit(CARD_ITEMS)
                .map(item -> new HomeSummary.InquiryItem(item.id(), item.branchId(), item.subject(), item.status(), item.createdAt(),
                        link("inquiry-detail", item.branchId(), item.id(), Map.of())))
                .toList();
        return new HomeSummary.InquiryCard(totals.unresolved(), totals.received(), totals.inProgress(), items,
                link("inquiry-list", listBranch, null, Map.of()), source("getInquirySummary", query("scope", scope), null));
    }

    // ---- facilities ----------------------------------------------------------------------------------------------

    private HomeSummary.FacilityCard facilityCard(long actor, String scope, String listBranch) {
        long due = facilities.summary(actor, scope, null, FacilityFilters.parse(null, null, null, null, null)).totals().inspectionDue();
        var items = facilities.list(actor, scope, new ListQuery(1, CARD_ITEMS, "", "nextInspectionOn", ListQuery.Direction.ASC),
                        FacilityFilters.parse(null, null, null, "true", null)).items().stream()
                .sorted(Comparator.comparing(FacilityListItem::nextInspectionOn).thenComparing(item -> Long.parseLong(item.id())))
                .map(item -> new HomeSummary.FacilityItem(item.id(), item.branchId(), item.name(), item.status(),
                        item.nextInspectionOn(), link("facility-detail", item.branchId(), item.id(), Map.of())))
                .toList();
        return new HomeSummary.FacilityCard(due, items, link("facility-list", listBranch, null, Map.of("inspectionDue", "true")),
                source("getFacilitySummary", query("scope", scope), "inspectionDue = totals.inspectionDue"));
    }

    // ---- shifts --------------------------------------------------------------------------------------------------

    private HomeSummary.ShiftCard shiftCard(long actor, AuthorizationState state, String scope, String listBranch,
            Set<Long> branches, Day day) {
        String from = day.date().minusDays(1).toString(), to = day.date().toString();
        // A shift is at most 24 hours (M8-D03), so one overlapping today started yesterday or today.
        var overlapping = new ArrayList<ShiftView>();
        for (long page = 1; ; page++) {
            var result = shifts.list(actor, scope, new ListQuery(page, 100, "", "id", ListQuery.Direction.ASC), null, null, null, from, to);
            result.items().stream().filter(shift -> shift.startsAt().isBefore(day.end()) && shift.endsAt().isAfter(day.start()))
                    .forEach(overlapping::add);
            if (page * 100 >= result.meta().total() || result.items().isEmpty()) break;
        }
        overlapping.sort(Comparator.comparing(ShiftView::startsAt).thenComparing(shift -> Long.parseLong(shift.id())));
        var items = overlapping.stream().limit(CARD_ITEMS)
                .map(shift -> new HomeSummary.ShiftItem(shift.id(), shift.branchId(),
                        new ActorSummary(shift.employee().id(), shift.employee().name()), shift.startsAt(), shift.endsAt(),
                        link("shift-detail", shift.branchId(), shift.id(), Map.of())))
                .toList();
        // Colleagues (2026-11-05 user decision): only branches of this scope where the requester is an active member.
        var memberOf = new TreeSet<Long>();
        for (long branch : branches) {
            var membership = state.memberships().get(branch);
            if (membership != null && "ACTIVE".equals(membership.status())) memberOf.add(branch);
        }
        var colleagues = shifts.colleagues(actor, memberOf, day.start(), day.end());
        var shown = colleagues.stream().limit(CARD_ITEMS)
                .map(shift -> new HomeSummary.ShiftColleague(Long.toString(shift.branchId()), shift.employeeName(), shift.startsAt(),
                        shift.endsAt()))
                .toList();
        var sourceQuery = new LinkedHashMap<String, String>();
        sourceQuery.put("scope", scope);
        sourceQuery.put("status", "예정");
        sourceQuery.put("from", from);
        sourceQuery.put("to", to);
        return new HomeSummary.ShiftCard(overlapping.size(), items, colleagues.size(), shown,
                link("shift-list", listBranch, null, Map.of("from", to, "to", to)),
                source("listShifts", sourceQuery, "startsAt < " + day.end() + " ∧ endsAt > " + day.start()));
    }

    // ---- notices -------------------------------------------------------------------------------------------------

    private HomeSummary.NoticeCard noticeCard(long actor, String scope) {
        var items = notices.list(actor, scope, new ListQuery(1, CARD_ITEMS, "", "id", ListQuery.Direction.DESC), PUBLISHED).items()
                .stream()
                .map(notice -> new HomeSummary.NoticeItem(notice.id(), notice.title(), notice.pinned(), notice.publishedAt(),
                        link("notice-detail", null, notice.id(), Map.of())))
                .toList();
        var sourceQuery = new LinkedHashMap<String, String>();
        sourceQuery.put("scope", scope);
        sourceQuery.put("status", PUBLISHED);
        sourceQuery.put("page", "1");
        sourceQuery.put("pageSize", Integer.toString(CARD_ITEMS));
        return new HomeSummary.NoticeCard(items, link("notice-list", null, null, Map.of("status", PUBLISHED)),
                source("listNotices", sourceQuery, null));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Link link(String screen, String branchId, String targetId, Map<String, String> query) {
        return new Link(screen, branchId, targetId, Map.copyOf(query));
    }

    private static Source source(String operationId, Map<String, String> query, String clientFilter) {
        return new Source(operationId, new LinkedHashMap<>(query), clientFilter);
    }

    private static Map<String, String> query(String key, String value) {
        var query = new LinkedHashMap<String, String>();
        query.put(key, value);
        return query;
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        var query = new LinkedHashMap<>(base);
        query.put(key, value);
        return query;
    }

    private static Boolean flag(String value) {
        if (value == null) return null;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw ApiException.validation("mine", "true 또는 false를 입력해 주세요.");
    }

    private static String id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw ApiException.validation("assigneeId", "올바른 직원 ID를 입력해 주세요.");
        try {
            return Long.toString(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw ApiException.validation("assigneeId", "올바른 직원 ID를 입력해 주세요.");
        }
    }
}
