package com.openstudy.modules.ai.service;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.prompt.PreviousReferenceResolver;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.QueryIntent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            Map.entry("이용권종류별", "pass.pass_type")
    );
    static final Pattern RECENT_DAYS = Pattern.compile("(?:최근|지난)([0-9]{1,3})일");
    static final Pattern NAMED_MONTH = Pattern.compile("([0-9]{4})년(0?[1-9]|1[0-2])월");

    private final SemanticRegistry registry;
    private final OpenStudyClock clock;

    @Override
    public QueryIntent analyze(String question, PreviousReferenceResolver.Conditions previous) {
        String key = QuestionNormalizer.key(question);
        var metric = metric(key);
        if (metric.isEmpty()) return QueryIntent.unknown();
        var dimensions = new LinkedHashSet<String>();
        for (var grouping : GROUPINGS) {
            if (key.contains(grouping.getKey()) && reachable(metric.get(), grouping.getValue())) dimensions.add(grouping.getValue());
        }
        Filter period = period(key).map(range -> (Filter) new Filter.Group(Filter.Junction.AND, List.of(
                new Filter.Leaf(OpenStudyCatalogConfiguration.PERIOD_FIELDS.get(metric.get().id()), Filter.Operator.GTE, List.of(range.start().toString())),
                new Filter.Leaf(OpenStudyCatalogConfiguration.PERIOD_FIELDS.get(metric.get().id()), Filter.Operator.LT, List.of(range.end().toString()))
        ))).orElse(null);
        QuerySpec.Direction order = dimensions.isEmpty() ? null : order(key);
        return new QueryIntent(Set.of(metric.get().id()), dimensions, period, order, Set.of(), IrStatus.READY, null);
    }

    /** The general metric whose catalog term the question uses; the longest term wins, a tie resolves nothing. */
    Optional<SemanticRegistry.Metric> metric(String key) {
        String best = null;
        int bestLength = 0;
        boolean tie = false;
        for (var term : registry.terms()) {
            var metric = registry.metrics().get(term.target());
            if (metric == null || !OpenStudyCatalogConfiguration.general(metric)) continue;
            for (String alias : term.aliases()) {
                int length = alias.replaceAll("\\s+", "").length();
                if (!QuestionNormalizer.has(key, alias) || length < bestLength) continue;
                if (length > bestLength) { best = metric.id(); bestLength = length; tie = false; }
                else if (!metric.id().equals(best)) tie = true;
            }
        }
        return best == null || tie ? Optional.empty() : Optional.of(registry.metrics().get(best));
    }

    /** A grouping field on the metric's own source, or the branch it belongs to. */
    boolean reachable(SemanticRegistry.Metric metric, String field) {
        var target = registry.fields().get(field);
        if (target == null) return false;
        if (target.source().equals(metric.source())) return true;
        return target.source().equals("branch") && registry.fields().containsKey(metric.source() + ".branch_id");
    }

    record Range(java.time.Instant start, java.time.Instant end) {}

    /** Exactly one period phrase, resolved in KST by the server clock; ranges ("부터 … 까지") stay with the engine. */
    Optional<Range> period(String key) {
        if (key.contains("부터") || key.contains("까지")) return Optional.empty();
        LocalDate today = clock.todayDateKst();
        var found = new ArrayList<LocalDate[]>();
        if (key.contains("오늘")) found.add(days(today, today.plusDays(1)));
        if (key.contains("어제")) found.add(days(today.minusDays(1), today));
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        if (key.contains("이번주")) found.add(days(monday, monday.plusWeeks(1)));
        if (key.contains("지난주")) found.add(days(monday.minusWeeks(1), monday));
        YearMonth month = YearMonth.from(today);
        if (key.contains("이번달")) found.add(days(month.atDay(1), month.plusMonths(1).atDay(1)));
        if (key.contains("지난달")) found.add(days(month.minusMonths(1).atDay(1), month.atDay(1)));
        if (key.contains("올해") || key.contains("금년")) found.add(days(LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear() + 1, 1, 1)));
        if (key.contains("작년") || key.contains("지난해")) found.add(days(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear(), 1, 1)));
        var recent = RECENT_DAYS.matcher(key);
        while (recent.find()) {
            int n = Integer.parseInt(recent.group(1));
            if (n < 1 || n > OpenStudyPlaybackGateway.MAX_DATE_RANGE_DAYS) return Optional.empty();
            found.add(days(today.minusDays(n - 1L), today.plusDays(1)));
        }
        var named = NAMED_MONTH.matcher(key);
        while (named.find()) {
            var ym = YearMonth.of(Integer.parseInt(named.group(1)), Integer.parseInt(named.group(2)));
            found.add(days(ym.atDay(1), ym.plusMonths(1).atDay(1)));
        }
        if (found.size() != 1) return Optional.empty();
        var zone = OpenStudyClock.KST;
        return Optional.of(new Range(found.getFirst()[0].atStartOfDay(zone).toInstant(), found.getFirst()[1].atStartOfDay(zone).toInstant()));
    }

    private static LocalDate[] days(LocalDate startInclusive, LocalDate endExclusive) {
        return new LocalDate[] {startInclusive, endExclusive};
    }

    static QuerySpec.Direction order(String key) {
        boolean desc = key.contains("많은순") || key.contains("높은순") || key.contains("큰순") || key.contains("내림차순");
        boolean asc = key.contains("적은순") || key.contains("낮은순") || key.contains("작은순") || key.contains("오름차순");
        return desc == asc ? null : desc ? QuerySpec.Direction.DESC : QuerySpec.Direction.ASC;
    }
}
