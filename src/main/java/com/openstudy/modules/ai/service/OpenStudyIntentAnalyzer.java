package com.openstudy.modules.ai.service;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.prompt.PreviousReferenceResolver;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.CalendarPeriod;
import com.openstudy.domainlens.query.DateNormalizer;
import com.openstudy.domainlens.query.QueryIntent;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * #232: OpenStudy's reading of a question for the generation path, made on the server and never by the model.
 * <p>
 * The DomainLens compiler accepts a model's metric, grouping and period only when they are proven by the question.
 * A relative period ("이번 달") cannot be proven from the text alone, so the server resolves it here with its own clock
 * and the compiler checks the model's plan against it. The reading is conservative: it resolves a general metric only
 * from a catalog term, and leaves a slot empty whenever the question is ambiguous (two metrics, two periods).
 */
@Component
@RequiredArgsConstructor
public class OpenStudyIntentAnalyzer implements QueryIntent.Analyzer {

    /** Grouping phrases, with spaces removed, and the field each groups by. */
    static final List<Map.Entry<String, String>> GROUPINGS = List.of(
            Map.entry("지점별", "branch.name"),
            Map.entry("매장별", "branch.name"),
            Map.entry("상품구분별", "payment.item_type"),
            Map.entry("상품군별", "payment.item_type"),
            Map.entry("결제항목별", "payment.item_type"),
            Map.entry("결제상태별", "payment.status"),
            Map.entry("이용권구분별", "pass.pass_type"),
            Map.entry("이용권종류별", "pass.pass_type"),
            // Seat congestion: weekday before hour, so a heatmap reads weekday across and hour down. "언제" asks for both.
            Map.entry("요일", "seat_occupancy_sample.weekday_kst"),
            Map.entry("언제", "seat_occupancy_sample.weekday_kst"),
            Map.entry("시간대", "seat_occupancy_sample.hour_kst"),
            Map.entry("시간별", "seat_occupancy_sample.hour_kst"),
            Map.entry("몇시", "seat_occupancy_sample.hour_kst"),
            Map.entry("언제", "seat_occupancy_sample.hour_kst")
    );
    /** Metrics with a period of their own when the question names none: congestion reads the last two full weeks (POC-002). */
    static final Set<String> TWO_WEEK_DEFAULT = Set.of("seats.occupancy_pct");
    static final Pattern RECENT_DAYS = Pattern.compile("(?:최근|지난)([0-9]{1,3})일");
    static final Pattern NAMED_MONTH = Pattern.compile("([0-9]{4})년(0?[1-9]|1[0-2])월");
    /**
     * #97: a month without a year ("11월에"). Not one already read with its year ("2026년11월"), nor a day ("11월3일"),
     * nor a span ("3개월", which has no digit right before 월).
     */
    static final Pattern MONTH_ONLY = Pattern.compile("(?<![0-9년])(0?[1-9]|1[0-2])월(?![0-9])");

    private final SemanticRegistry semanticRegistry;
    private final OpenStudyClock openStudyClock;

    @Override
    public QueryIntent analyze(String question, PreviousReferenceResolver.Conditions previous) {
        String key = QuestionNormalizer.key(question);
        Optional<SemanticRegistry.Metric> metric = metric(key);
        if (metric.isEmpty()) {
            return QueryIntent.unknown();
        }

        LinkedHashSet<String> dimensions = new LinkedHashSet<String>();
        for (Map.Entry<String,String> grouping : GROUPINGS) {
            if (key.contains(grouping.getKey()) && reachable(metric.get(), grouping.getValue())) {
                dimensions.add(grouping.getValue());
            }
        }
        Optional<Range> named = period(key);
        // Only when the question names no period at all; a range, several periods or one out of range stay unresolved.
        boolean namesNone = !key.contains("부터")
                && !key.contains("까지")
                && findPeriods(key) != null
                && findPeriods(key).isEmpty();
        if (named.isEmpty() && namesNone && TWO_WEEK_DEFAULT.contains(metric.get().id())) {
            OpenStudyClock.KstPeriod twoWeeks = openStudyClock.previousTwoWeeks();
            named = Optional.of(new Range(twoWeeks.startInclusive(), twoWeeks.endExclusive()));
        }
        Filter period =
                named.map(
                        range -> (Filter) new Filter.Group(
                                Filter.Junction.AND,
                                List.of(
                                        new Filter.Leaf(
                                                OpenStudyCatalogConfiguration.PERIOD_FIELDS.get(metric.get().id()),
                                                Filter.Operator.GTE,
                                                List.of(range.start().toString())
                                        ),
                                        new Filter.Leaf(
                                                OpenStudyCatalogConfiguration.PERIOD_FIELDS.get(metric.get().id()),
                                                Filter.Operator.LT,
                                                List.of(range.end().toString())
                                        )
                                )
                        )
                )
                        .orElse(null);
        QuerySpec.Direction order = dimensions.isEmpty() ? null : order(key);
        return new QueryIntent(Set.of(metric.get().id()), dimensions, period, order, Set.of(), IrStatus.READY, null);
    }

    /**
     * Every period the question names, read now as KST date ranges ("2026-11-01~2026-12-01"), for comparing two
     * questions (AiPlanCache). "OUT_OF_RANGE" stands for a period too long to read.
     */
    public List<String> periodReadings(String question) {
        List<LocalDate[]> found = findPeriods(QuestionNormalizer.key(question));
        if (found == null) {
            return List.of("OUT_OF_RANGE");
        }

        return found.stream().map(range -> range[0] + "~" + range[1]).toList();
    }

    /** The general metric whose catalog term the question uses; the longest term wins, a tie resolves nothing. */
    Optional<SemanticRegistry.Metric> metric(String key) {
        String best = null;
        int bestLength = 0;
        boolean tie = false;
        for (SemanticRegistry.Term term : semanticRegistry.terms()) {
            SemanticRegistry.Metric metric = semanticRegistry.metrics().get(term.target());
            if (metric == null || !OpenStudyCatalogConfiguration.general(metric)) {
                continue;
            }

            for (String alias : term.aliases()) {
                int length = alias.replaceAll("\\s+", "").length();
                if (!QuestionNormalizer.has(key, alias) || length < bestLength) {
                    continue;
                }

                if (length > bestLength) {
                    best = metric.id();
                    bestLength = length;
                    tie = false;
                } else if (!metric.id().equals(best)) {
                    tie = true;
                }
            }
        }
        return best == null || tie ? Optional.empty() : Optional.of(semanticRegistry.metrics().get(best));
    }

    /** A grouping field on the metric's own source, or the branch it belongs to. */
    boolean reachable(SemanticRegistry.Metric metric, String field) {
        SemanticRegistry.Field target = semanticRegistry.fields().get(field);
        if (target == null) {
            return false;
        }

        if (target.source().equals(metric.source())) {
            return true;
        }

        return target.source().equals("branch")
                && semanticRegistry.fields().containsKey(metric.source() + ".branch_id");
    }

    record Range(Instant start, Instant end) {
    }

    /** Exactly one period phrase, resolved in KST by the server clock; ranges ("부터 … 까지") stay with the engine. */
    Optional<Range> period(String key) {
        if (key.contains("부터") || key.contains("까지")) {
            return Optional.empty();
        }

        List<LocalDate[]> found = findPeriods(key);
        if (found == null || found.size() != 1) {
            return Optional.empty();
        }

        ZoneId zone = OpenStudyClock.KST;
        return Optional.of(
                new Range(
                        found.getFirst()[0].atStartOfDay(zone).toInstant(),
                        found.getFirst()[1].atStartOfDay(zone).toInstant()
                )
        );
    }

    /**
     * The period a question names, as KST dates {@code [from, toExclusive)}: none, one, or more than one (ambiguous).
     * Registered analyses use it in place of their default period (2026-11-13 decision, replacing M10-D14's reading
     * that a registered analysis never reads a period from the question). "YYYY년 M월부터 … 까지" uses the engine's
     * calendar reading.
     */
    public record QuestionPeriod(LocalDate from, LocalDate toExclusive, boolean ambiguous) {

        public static final QuestionPeriod NONE = new QuestionPeriod(null, null, false);
        public boolean resolved() {
            return from != null;
        }
    }

    public QuestionPeriod questionPeriod(String question) {
        if (question == null || question.isBlank()) {
            return QuestionPeriod.NONE;
        }

        String key = QuestionNormalizer.key(question);
        if (key.contains("부터") || key.contains("까지")) {
            DateNormalizer.Range range = CalendarPeriod.months(
                    question,
                    OpenStudyClock.KST
            );
            return range == null ? QuestionPeriod.NONE : new QuestionPeriod(
                    range.startInclusive().atZone(OpenStudyClock.KST).toLocalDate(),
                    range.endExclusive().atZone(OpenStudyClock.KST).toLocalDate(), false);
        }
        List<LocalDate[]> found = findPeriods(key);
        if (found == null || found.isEmpty()) {
            return QuestionPeriod.NONE;
        }

        if (found.size() > 1) {
            return new QuestionPeriod(null, null, true);
        }

        return new QuestionPeriod(found.getFirst()[0], found.getFirst()[1], false);
    }

    /** Every period phrase in the question, or null when one is out of range ("최근 900일"). */
    private List<LocalDate[]> findPeriods(String key) {
        LocalDate today = openStudyClock.todayDateKst();
        ArrayList<LocalDate[]> found = new ArrayList<LocalDate[]>();
        if (key.contains("오늘")) {
            found.add(toDays(today, today.plusDays(1)));
        }

        if (key.contains("어제")) {
            found.add(toDays(today.minusDays(1), today));
        }

        LocalDate monday = today.with(DayOfWeek.MONDAY);
        if (key.contains("이번주")) {
            found.add(toDays(monday, monday.plusWeeks(1)));
        }

        if (key.contains("지난주")) {
            found.add(toDays(monday.minusWeeks(1), monday));
        }

        YearMonth month = YearMonth.from(today);
        if (key.contains("이번달")) {
            found.add(toDays(month.atDay(1), month.plusMonths(1).atDay(1)));
        }

        if (key.contains("지난달")) {
            found.add(toDays(month.minusMonths(1).atDay(1), month.atDay(1)));
        }

        if (key.contains("올해") || key.contains("금년")) {
            found.add(toDays(LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear() + 1, 1, 1)));
        }

        if (key.contains("작년") || key.contains("지난해")) {
            found.add(toDays(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear(), 1, 1)));
        }

        Matcher recent = RECENT_DAYS.matcher(key);
        while (recent.find()) {
            int n = Integer.parseInt(recent.group(1));
            if (n < 1 || n > OpenStudyPlaybackGateway.MAX_DATE_RANGE_DAYS) {
                return null;
            }

            found.add(toDays(today.minusDays(n - 1L), today.plusDays(1)));
        }
        Matcher named = NAMED_MONTH.matcher(key);
        while (named.find()) {
            YearMonth ym = YearMonth.of(Integer.parseInt(named.group(1)), Integer.parseInt(named.group(2)));
            found.add(toDays(ym.atDay(1), ym.plusMonths(1).atDay(1)));
        }
        Matcher monthOnly = MONTH_ONLY.matcher(key);
        while (monthOnly.find()) {
            // The latest such month up to now: "11월" asked in December is this year's, asked in February last year's.
            YearMonth ym = YearMonth.of(today.getYear(), Integer.parseInt(monthOnly.group(1)));
            if (ym.isAfter(month)) {
                ym = ym.minusYears(1);
            }

            found.add(toDays(ym.atDay(1), ym.plusMonths(1).atDay(1)));
        }
        return found;
    }

    private static LocalDate[] toDays(LocalDate startInclusive, LocalDate endExclusive) {
        return new LocalDate[] {startInclusive, endExclusive};
    }

    static QuerySpec.Direction order(String key) {
        boolean desc = key.contains("많은순") || key.contains("높은순") || key.contains("큰순") || key.contains("내림차순");
        boolean asc = key.contains("적은순") || key.contains("낮은순") || key.contains("작은순") || key.contains("오름차순");
        if (desc == asc) {
            return null;
        }

        return desc ? QuerySpec.Direction.DESC : QuerySpec.Direction.ASC;
    }
}
