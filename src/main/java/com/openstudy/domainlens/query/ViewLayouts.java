package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.ViewSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The arrangement of a result's components, as the DomainLens result mock-up assembles them: the KPI cards on top as one
 * row, then a chart beside the table that lists its figures ({@code split}), and wide views (week calendar, seat map,
 * task list) or a lone table across the full width. Every component appears exactly once, in its original order within
 * each band.
 */
public final class ViewLayouts {
    static final Set<String> KPI = Set.of("KPI", "KpiGroup");
    static final Set<String> TABLE = Set.of("DATA_TABLE", "TABLE", "DataTable", "PaginatedDataTable", "DetailCard");
    /** Views that need the full width; a table is never put beside them. */
    static final Set<String> WIDE = Set.of("WEEK_CALENDAR", "SEAT_MAP", "TASK_LIST");

    private ViewLayouts() {}

    public static Layout compose(List<ViewSpec.Component> components) {
        var kpis = new ArrayList<Layout>();
        var charts = new ArrayList<ViewSpec.Component>();
        var wide = new ArrayList<Layout>();
        var tables = new ArrayList<ViewSpec.Component>();
        for (var component : components) {
            String type = component.componentRef().id();
            if (KPI.contains(type)) kpis.add(Layout.leaf(component.id()));
            else if (TABLE.contains(type)) tables.add(component);
            else if (WIDE.contains(type)) wide.add(Layout.leaf(component.id()));
            else charts.add(component);
        }
        var bands = new ArrayList<Layout>();
        if (!kpis.isEmpty()) bands.add(kpis.size() == 1 ? kpis.getFirst() : new Layout("grid", kpis, null));
        bands.addAll(wide);
        // A chart and the table of the same figures sit side by side; anything else takes its own band.
        int pairs = Math.min(charts.size(), tables.size());
        for (int i = 0; i < pairs; i++) {
            bands.add(new Layout("split", List.of(Layout.leaf(charts.get(i).id()), Layout.leaf(tables.get(i).id())), null));
        }
        charts.subList(pairs, charts.size()).forEach(chart -> bands.add(Layout.leaf(chart.id())));
        tables.subList(pairs, tables.size()).forEach(table -> bands.add(Layout.leaf(table.id())));
        return Layout.stack(bands);
    }
}
