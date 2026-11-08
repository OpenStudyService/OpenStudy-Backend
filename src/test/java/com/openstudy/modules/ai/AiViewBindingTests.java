package com.openstudy.modules.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.openstudy.domainlens.api.ir.ComponentCatalog;
import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ValueType;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.query.ViewBinder;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #95: the registered analyses bind their ViewSpec with this binder (OpenStudyAnalyses), so a view that names a field
 * the result does not have, or an unregistered component, is refused before anything is rendered.
 */
class AiViewBindingTests {
    final ViewBinder binder = new ViewBinder(ComponentCatalog.openStudyDefault());
    final Instant now = Instant.parse("2026-11-08T00:00:00Z");
    final TypedDataset totals = new TypedDataset("totals",
            List.of(new TypedDataset.Column("net_krw", ValueType.DECIMAL, "KRW", 0)),
            List.of(Map.of("net_krw", 37_000)), 1, 0, 1, now, now);

    ViewSpec kpi(String componentId, String field) {
        return new ViewSpec(OpenStudyAnalyses.CATALOG_VERSION, List.of(new ViewSpec.Component("kpi",
                new ViewSpec.Ref(componentId, OpenStudyAnalyses.COMPONENT_VERSION), "totals",
                Map.of("values", List.of(field)), Map.of("title", "합계"), List.of())), Layout.stack(List.of(Layout.leaf("kpi"))));
    }

    @Test void aMatchingViewBinds() {
        var bound = binder.bind(kpi("KPI", "net_krw"), List.of(totals));
        assertThat(bound.view().components()).hasSize(1);
        assertThat(bound.warnings()).isEmpty();
    }

    @Test void aFieldTheResultDoesNotHaveIsRefused() {
        assertThatThrownBy(() -> binder.bind(kpi("KPI", "gross_krw"), List.of(totals))).isInstanceOf(RuntimeException.class);
    }

    @Test void anUnregisteredComponentIsRefused() {
        assertThatThrownBy(() -> binder.bind(kpi("RAW_HTML", "net_krw"), List.of(totals))).isInstanceOf(RuntimeException.class);
    }
}
