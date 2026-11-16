package com.openstudy.domainlens;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.domainlens.api.ir.ContractRules;
import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.query.ViewLayouts;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Result layouts follow the DomainLens mock-up: KPI row, then a chart beside its table, wide views full width. */
class ViewLayoutsUnitTests {

    static ViewSpec.Component component(String id, String type) {
        return new ViewSpec.Component(id, new ViewSpec.Ref(type, "1.0.0"), "d", Map.of("columns", List.of("a")), Map.of(), List.of());
    }

    static List<ViewSpec.Component> components(String... idTypes) {
        return Arrays.stream(idTypes).map(pair -> pair.split(":")).map(p -> component(p[0], p[1])).toList();
    }

    @Test void kpiOnTopThenTheChartBesideItsTable() {
        var layout = ViewLayouts.compose(components("kpi:KPI", "chart:BAR_CHART", "table:DATA_TABLE"));

        assertThat(layout).isEqualTo(Layout.stack(List.of(Layout.leaf("kpi"),
                new Layout("split", List.of(Layout.leaf("chart"), Layout.leaf("table")), null))));
    }

    @Test void wideViewsAndLoneComponentsTakeTheFullWidth() {
        assertThat(ViewLayouts.compose(components("calendar:WEEK_CALENDAR", "table:DATA_TABLE")))
                .isEqualTo(Layout.stack(List.of(Layout.leaf("calendar"), Layout.leaf("table"))));
        assertThat(ViewLayouts.compose(components("kpi:KPI", "table:DATA_TABLE")))
                .isEqualTo(Layout.stack(List.of(Layout.leaf("kpi"), Layout.leaf("table"))));
        assertThat(ViewLayouts.compose(components("heatmap:HEATMAP")))
                .isEqualTo(Layout.stack(List.of(Layout.leaf("heatmap"))));
    }

    @Test void generatedComponentsAreArrangedTheSameWay() {
        var layout = ViewLayouts.compose(components("c0:BarChart", "c1:PaginatedDataTable", "c2:KpiGroup"));

        assertThat(layout).isEqualTo(Layout.stack(List.of(Layout.leaf("c2"),
                new Layout("split", List.of(Layout.leaf("c0"), Layout.leaf("c1")), null))));
    }

    @Test void everyComposedLayoutPassesTheViewContract() {
        for (var list : List.of(
                components("kpi:KPI", "chart:BAR_CHART", "table:DATA_TABLE"),
                components("k1:KPI", "k2:KpiGroup", "line:LINE_CHART", "t1:DATA_TABLE", "bar:BAR_CHART"),
                components("seat:SEAT_MAP", "kpi:KPI"),
                components("t1:DATA_TABLE", "t2:DATA_TABLE"))) {
            var view = new ViewSpec("openstudy-ui-1.0.0", list, ViewLayouts.compose(list));
            ContractRules.view(view, Set.of("d"));
        }
    }
}
