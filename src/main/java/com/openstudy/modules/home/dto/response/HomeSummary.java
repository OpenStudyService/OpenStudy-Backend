package com.openstudy.modules.home.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.openstudy.modules.branch.dto.response.ClosureNotice;

/**
 * Contract schema {@code HomeSummary} (aggregation-api.md "운영 홈 요약", FR-DASH-02·03·05·06). Every card follows the
 * permission and scope of its source feature; the shift card's colleagues are the one documented exception.
 * The message card was dropped with team chat (ADR-M10-002 M10-D11). {@code closures} announces the scope branches'
 * closed days from today through seven days ahead (M13-D01, #112).
 */
public record HomeSummary(Meta meta, SeatCard seats, TaskCard tasks, InquiryCard inquiries, FacilityCard facilities,
        ShiftCard shifts, NoticeCard notices, List<ClosureNotice> closures) {

    public record Meta(List<String> branchIds, boolean allBranches, LocalDate date, Instant dayStart, Instant dayEnd,
            String timeZone, Instant asOf, TaskFilter taskFilter) {}

    public record TaskFilter(boolean mine, String assigneeId, boolean defaulted) {}

    /** Contract schema {@code Ratio} (M9-D09): one decimal, HALF_UP; a zero denominator is "해당 없음", not 0%. */
    public record Ratio(String state, BigDecimal percent, long numerator, long denominator) {
        public static Ratio of(long numerator, long denominator) {
            if (denominator == 0) return new Ratio("notApplicable", null, numerator, 0);
            var percent = BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
            return new Ratio("value", percent, numerator, denominator);
        }
    }

    public record Link(String screen, String branchId, String targetId, Map<String, String> query) {}

    public record Source(String operationId, Map<String, String> query, String clientFilter) {}

    public record ActorSummary(String id, String name) {}

    public record SeatCard(long total, long inUse, long available, long blocked, Ratio occupancy, List<SeatBranch> branches,
            Link link, Source source) {}

    public record SeatBranch(String branchId, long total, long inUse, Ratio occupancy) {}

    public record TaskCard(TaskCounts today, TaskCounts mine, List<TaskItem> items, Link link, List<Source> sources) {}

    /** total = open + completedToday. */
    public record TaskCounts(long total, long open, long overdue, long completedToday, Ratio completionRate) {}

    public record TaskItem(String id, String branchId, String title, String status, String priority, Instant dueAt,
            ActorSummary assignee, boolean overdue, Link link) {}

    public record InquiryCard(long unresolved, long received, long inProgress, List<InquiryItem> items, Link link, Source source) {}

    /** No customer field: the home never carries customer information. */
    public record InquiryItem(String id, String branchId, String subject, String status, Instant createdAt, Link link) {}

    public record FacilityCard(long inspectionDue, List<FacilityItem> items, Link link, Source source) {}

    public record FacilityItem(String id, String branchId, String name, String status, LocalDate nextInspectionOn, Link link) {}

    public record ShiftCard(long count, List<ShiftItem> items, long colleagueCount, List<ShiftColleague> colleagues, Link link,
            Source source) {}

    public record ShiftItem(String id, String branchId, ActorSummary employee, Instant startsAt, Instant endsAt, Link link) {}

    /** Name and planned times only: no shift or employee ID, so a colleague's shift never opens a detail. */
    public record ShiftColleague(String branchId, String employeeName, Instant startsAt, Instant endsAt) {}

    public record NoticeCard(List<NoticeItem> items, Link link, Source source) {}

    public record NoticeItem(String id, String title, boolean pinned, Instant publishedAt, Link link) {}
}
