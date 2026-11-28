package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ValueType;
import com.openstudy.domainlens.api.ir.ViewSpec;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The summary cards and panels of a registered analysis result, as the DomainLens result mock-up shows them: four KPI
 * cards of a label, a display value and a short note, then titled chart and table panels. A card's value is already
 * formatted for reading ("1,578,000원", "68.3%", "수 18시") because cards mix amounts, rates, counts and words.
 */
final class ResultCards {

    static final String DATASET = "summaryCards";
    static final List<TypedDataset.Column> COLUMNS = List.of(
            new TypedDataset.Column("label", ValueType.STRING, null, null),
            new TypedDataset.Column("value", ValueType.STRING, null, null),
            new TypedDataset.Column("note", ValueType.STRING, null, null));
    private static final String[] WEEKDAYS = {"", "월", "화", "수", "목", "금", "토", "일"};

    record Card(String label, String value, String note) {
    }

    private ResultCards() {
    }

    static TypedDataset dataset(List<Card> cards, Instant now) {
        ArrayList<Map<String,Object>> rows = new ArrayList<Map<String, Object>>();
        for (Card card : cards) {
            LinkedHashMap<String,Object> row = new LinkedHashMap<String, Object>();
            row.put("label", card.label());
            row.put("value", card.value());
            row.put("note", card.note() == null ? "" : card.note());
            rows.add(row);
        }
        return new TypedDataset(DATASET, COLUMNS, rows, rows.size(), 0, Math.max(1, rows.size()), now, now);
    }

    /** The KPI cards: a KPI component bound to the label, value and note columns (the frontend draws them as cards). */
    static ViewSpec.Component component() {
        return new ViewSpec.Component("summary", new ViewSpec.Ref("KPI", "1.0.0"), DATASET,
                Map.of("columns", List.of("label", "value", "note")), Map.of("title", "요약"), List.of());
    }

    /** A titled panel; the subtitle is the short hint on the panel's right. */
    static ViewSpec.Component panel(
            String id,
            String type,
            String dataset,
            Map<String, Object> bindings,
            String title,
            String subtitle
    ) {
        LinkedHashMap<String,Object> options = new LinkedHashMap<String, Object>();
        options.put("title", title);
        if (subtitle != null && !subtitle.isBlank()) {
            options.put("subtitle", subtitle);
        }

        return new ViewSpec.Component(id, new ViewSpec.Ref(type, "1.0.0"), dataset, bindings, options, List.of());
    }

    /** A copy of the dataset whose rows carry extra columns, appended after the existing ones. */
    static TypedDataset withColumns(
            TypedDataset dataset,
            List<TypedDataset.Column> extra,
            Function<Map<String, Object>, Map<String, Object>> values
    ) {
        // The analysis's output definition may already declare these columns; each alias appears once.
        ArrayList<TypedDataset.Column> columns = new ArrayList<>(dataset.columns());
        for (TypedDataset.Column column : extra) {
            if (columns.stream().noneMatch(existing -> existing.alias().equals(column.alias()))) {
                columns.add(column);
            }
        }
        ArrayList<Map<String,Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String,Object> row : dataset.rows()) {
            LinkedHashMap<String,Object> next = new LinkedHashMap<String, Object>(row);
            next.putAll(values.apply(row));
            rows.add(next);
        }
        return new TypedDataset(dataset.id(), columns, rows, dataset.totalElements(), dataset.page(), dataset.size(),
                dataset.snapshotAt(), dataset.asOf());
    }

    static String won(long amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
    }

    static String count(long value, String unit) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + unit;
    }

    static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    static String hours(double value) {
        return String.format(Locale.ROOT, "%.1f시간", value);
    }

    static String weekday(int weekday) {
        return weekday >= 1 && weekday <= 7 ? WEEKDAYS[weekday] : String.valueOf(weekday);
    }

    /** "11.02~11.15": an inclusive Asia/Seoul date range for a card note. */
    static String days(LocalDate from, LocalDate toInclusive) {
        return String.format(Locale.ROOT, "%02d.%02d~%02d.%02d", from.getMonthValue(), from.getDayOfMonth(),
                toInclusive.getMonthValue(), toInclusive.getDayOfMonth());
    }

    static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
