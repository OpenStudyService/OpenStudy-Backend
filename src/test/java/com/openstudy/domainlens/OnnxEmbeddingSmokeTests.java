package com.openstudy.domainlens;

import com.openstudy.domainlens.ai.OnnxEmbeddingGateway;
import com.openstudy.domainlens.api.ir.ComponentCatalog;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OnnxEmbeddingSmokeTests {

    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void localKoenE5TinyQ8ProducesNormalized384DimEmbeddingForKoreanQuery() {
        try (var gateway = new OnnxEmbeddingGateway(Path.of(".models/koen-e5-tiny-q8"))) {
            assertThat(gateway.dimensions()).isEqualTo(384);
            assertThat(gateway.manifest().modelId()).isEqualTo("exp-models/dragonkue-KoEn-E5-Tiny-ONNX");

            List<Double> vector = gateway.embedQuery("이번 달 지점별 매출을 비교해줘");
            assertThat(vector).hasSize(384);

            double normSquared = 0.0;
            for (double v : vector) {
                assertThat(Double.isFinite(v)).isTrue();
                normSquared += v * v;
            }
            assertThat(Math.sqrt(normSquared)).isCloseTo(1.0, within(1e-6));

            var docs = gateway.embedDocuments(List.of("지점별 승인 매출과 환불 차감 순매출 비교", "실시간 좌석 점유율 및 빈자리 현황"));
            assertThat(docs).hasSize(2);
            assertThat(docs.get(0)).hasSize(384);
            assertThat(docs.get(1)).hasSize(384);
        }
    }

    @Test
    void openStudyComponentCatalogAndScalarSemanticRelationAreSupported() throws Exception {
        var catalog = ComponentCatalog.openStudyDefault();
        var ids = catalog.components().stream().map(ComponentCatalog.Definition::id).toList();
        assertThat(ids).contains(
                "KPI", "BAR_CHART", "LINE_CHART", "RANKED_BAR_CHART",
                "DATA_TABLE", "TABLE", "HEATMAP", "SEAT_MAP",
                "WEEK_CALENDAR", "TASK_LIST", "CLARIFICATION");

        assertThat(SemanticRelation.class.getDeclaredMethod("targetEntity").getDefaultValue()).isEqualTo("");
        assertThat(SemanticRelation.class.getDeclaredMethod("targetField").getDefaultValue()).isEqualTo("id");
    }
}
