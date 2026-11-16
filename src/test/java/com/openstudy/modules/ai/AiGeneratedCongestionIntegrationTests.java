package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.openstudy.domainlens.api.ir.IrDocument;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.planning.PlanningPrompt;
import com.openstudy.domainlens.api.planning.SemanticPlan;
import com.openstudy.domainlens.api.prompt.GenerationBudget;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.HeatmapCell;
import com.openstudy.modules.ai.service.OpenStudyClock;
import com.openstudy.modules.ai.service.OpenStudyGenerationModel;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * Seat congestion through the DomainLens generation path: the model plans "좌석 점유율 by 요일 x 시간대", the server
 * compiles the QuerySpec over the V20 occupancy samples and a heatmap ViewSpec beside its table, and the figures equal
 * the registered analysis POC-002 on the same sessions. The model is a gateway replaying such a plan; everything after
 * it is the real server.
 */
@MySqlIntegrationTest
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
class AiGeneratedCongestionIntegrationTests extends AiTestSupport {
    static final String METRIC = "seats.occupancy_pct";
    static final String WEEKDAY = "seat_occupancy_sample.weekday_kst";
    static final String HOUR = "seat_occupancy_sample.hour_kst";

    @MockitoBean OpenStudyGenerationModel model;
    @Autowired OpenStudyAnalyses analyses;
    @Autowired OpenStudyClock clock;

    final AtomicInteger modelCalls = new AtomicInteger();
    /** The planning prompt the server handed the model on the last call. */
    PlanningPrompt captured;
    /** A plan the replayed model returns instead of the built-in one. */
    SemanticPlan override;
    long owner, branch;
    String branchName;

    /**
     * Opt-in tools for checking a real model by hand: OPENSTUDY_AI_PROMPT_EXPORT=true writes the exact request the
     * server sends Qwen for "강남점은 언제 가장 붐벼?", and OPENSTUDY_AI_RECORDED_PLAN=<file> replays a model's answer to
     * it in place of the built-in plan. Both use a branch named 강남점 so the question and its quotes stay the same.
     */
    static final String EXPORT = "OPENSTUDY_AI_PROMPT_EXPORT";
    static final String RECORDED = "OPENSTUDY_AI_RECORDED_PLAN";
    static final String GANGNAM_QUESTION = "강남점은 언제 가장 붐벼?";

    static boolean byHand() {
        return "true".equals(System.getenv(EXPORT)) || System.getenv(RECORDED) != null;
    }

    @BeforeEach void setUp() {
        when(model.gateway()).thenReturn(Optional.of(replay()));
        owner = person("OWNER");
        // No analysis keyword in the name ("혼잡", "좌석"), so only the question's own words decide the route.
        branchName = byHand() ? "강남점" : "샘플" + UUID.randomUUID().toString().substring(0, 4) + "점";
        branch = branch(owner, branchName);
        // Four seats and sessions across the last two full weeks (POC-002's default period), Asia/Seoul wall times.
        var seats = new ArrayList<Long>();
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO seats(branch_id,zone,seat_no,kind,status) VALUES(?,'H',?,'GENERAL','AVAILABLE')", branch, "H-" + i);
            seats.add(count("SELECT id FROM seats WHERE branch_id=? AND seat_no=?", branch, "H-" + i));
        }
        LocalDate monday = clock.previousTwoWeeks().startDateKst();
        stay(seats.get(0), monday, 8, 13);           // Monday 09·12
        stay(seats.get(1), monday, 11, 19);          // Monday 12·15·18
        stay(seats.get(2), monday.plusDays(2), 14, 22); // Wednesday 15·18·21
        stay(seats.get(0), monday.plusDays(7), 8, 10);  // Monday of week two, 09
        stay(seats.get(3), monday.plusDays(12), 17, 20); // Saturday 18
    }

    @AfterEach void cleanSeats() {
        jdbc.update("DELETE FROM seat_sessions WHERE branch_id=?", branch);
        jdbc.update("DELETE FROM seats WHERE branch_id=?", branch);
    }

    @Test void theModelPlannedQueryAnswersCongestionWithAHeatmapAndTheRegisteredFigures() throws Exception {
        try (var s = login(owner)) {
            var response = ask(s, byHand() ? GANGNAM_QUESTION : branchName + "은 언제 가장 붐벼?", "all");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = body(response);
            assertThat(body.get("status").asString()).as(body.toString()).isEqualTo("COMPLETED");
            assertThat(body.get("resultCategory").asString()).as(body.at("/securityGate").toString() + body.at("/stages").toString()).isEqualTo("GENERATED_EXECUTION");
            assertThat(body.get("intentDomain").asString()).isEqualTo("seats");
            assertThat(modelCalls).hasValue(1);
            // The branch is the server's scope: the model reads the question without its name.
            if (!byHand()) assertThat(captured.question()).isEqualTo("선택 지점은 언제 가장 붐벼?");

            // QuerySpec: the metric over the two groupings, the server's two-week period, this branch only.
            JsonNode query = body.get("querySpec");
            assertThat(query.at("/mode").asString()).isEqualTo("AGGREGATE");
            assertThat(query.at("/source").asString()).isEqualTo("seat_occupancy_sample");
            assertThat(query.at("/metrics/0/field").asString()).isEqualTo(METRIC);
            assertThat(query.at("/dimensions/0/field").asString()).isEqualTo(WEEKDAY);
            assertThat(query.at("/dimensions/1/field").asString()).isEqualTo(HOUR);
            var twoWeeks = clock.previousTwoWeeks();
            assertThat(query.at("/filter").toString()).contains(twoWeeks.startInclusive().toString(), twoWeeks.endExclusive().toString());

            // ViewSpec: a heatmap bound weekday x hour -> occupancy, beside the table of the same rows.
            JsonNode view = body.get("viewSpec");
            JsonNode heatmap = component(view, "Heatmap");
            assertThat(heatmap.at("/bindings/category").asString()).isEqualTo("d0");
            assertThat(heatmap.at("/bindings/series").asString()).isEqualTo("d1");
            assertThat(heatmap.at("/bindings/value").asString()).isEqualTo("m0");
            JsonNode table = component(view, "PaginatedDataTable");
            assertThat(view.at("/layout/children/0/kind").asString()).isEqualTo("split");
            assertThat(view.at("/layout/children/0/children/0/componentId").asString()).isEqualTo(heatmap.get("id").asString());
            assertThat(view.at("/layout/children/0/children/1/componentId").asString()).isEqualTo(table.get("id").asString());

            // Figures: every cell equals POC-002 on the same sessions and period.
            var generated = new HashMap<String, Double>();
            for (var row : body.at("/envelope/datasets/0/rows")) {
                generated.put(row.get("d0").asLong() + ":" + row.get("d1").asLong(), row.get("m0").asDouble());
            }
            @SuppressWarnings("unchecked")
            var cells = (List<HeatmapCell>) analyses.executePoc002HourlyCongestion(branch).summary().get("sampled_heatmap_rows");
            assertThat(generated).hasSize(cells.size());
            for (var cell : cells) {
                Double pct = generated.get(cell.weekday() + ":" + cell.hourKst());
                assertThat(pct).as("weekday %d hour %d", cell.weekday(), cell.hourKst()).isNotNull();
                assertThat(Math.round(pct * 10) / 10.0).as("weekday %d hour %d", cell.weekday(), cell.hourKst())
                        .isEqualTo(cell.sampledOccupancyPct());
            }
            assertThat(generated.get("1:12")).isGreaterThan(0.0);
        }
    }

    @Test void aTopOneRankingTheQuestionDidNotAskForStillGivesTheWholeGrid() throws Exception {
        // A model's plan (2026-11-16): right metric, groupings and period, plus "가장 붐벼" read as occupancy DESC, limit 1.
        // The question asks no ranking, so the server drops the order and its row cap and draws the whole heatmap.
        var evidence = List.of(new SemanticPlan.Evidence(METRIC, SemanticPlan.Origin.QUESTION, "붐벼"),
                new SemanticPlan.Evidence(WEEKDAY, SemanticPlan.Origin.CONTRACT, "requestedDimensions"),
                new SemanticPlan.Evidence(HOUR, SemanticPlan.Origin.CONTRACT, "requestedDimensions"),
                new SemanticPlan.Evidence("order", SemanticPlan.Origin.QUESTION, "가장 붐벼"),
                new SemanticPlan.Evidence("limit", SemanticPlan.Origin.QUESTION, "가장 붐벼"));
        var request = new SemanticPlan.Request(QuerySpec.Mode.AGGREGATE, null, null, List.of(METRIC), List.of(WEEKDAY, HOUR), null,
                List.of(new SemanticPlan.Sort(METRIC, QuerySpec.Direction.DESC)), null, null, 1, false, List.of(), evidence, List.of());
        override = new SemanticPlan(SemanticPlan.VERSION, IrStatus.READY, null, List.of(), List.of(request), List.of(), null);
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, branchName + "은 언제 가장 붐벼?", "all"));
            assertThat(body.get("resultCategory").asString()).as(body.at("/securityGate").toString()).isEqualTo("GENERATED_EXECUTION");
            assertThat(body.at("/querySpec/orderBy").isEmpty()).isTrue();
            assertThat(body.at("/envelope/datasets/0/rows").size()).isEqualTo(35);
            component(body.get("viewSpec"), "Heatmap");
        }
    }

    @Test void whenTheModelAbstainsTheRegisteredAnalysisAnswers() throws Exception {
        // What a model said when the branch name was still in its question (2026-11-16): no field to filter the branch.
        override = new SemanticPlan(SemanticPlan.VERSION, IrStatus.UNSUPPORTED, "강남점을 식별할 승인된 필드가 없습니다.", List.of(),
                List.of(), List.of("지점 식별 근거 부재"), com.openstudy.domainlens.api.ir.ReasonCode.KNOWLEDGE_MISSING);
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, branchName + "은 언제 가장 붐벼?", "all"));
            assertThat(body.get("status").asString()).as(body.toString()).isEqualTo("COMPLETED");
            assertThat(body.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_002);
            assertThat(modelCalls).hasValue(1);
        }
    }

    @Test void withoutALiveModelTheRegisteredAnalysisStillAnswers() throws Exception {
        when(model.gateway()).thenReturn(Optional.empty());
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, branchName + "은 언제 가장 붐벼?", "all"));
            assertThat(body.get("status").asString()).as(body.toString()).isEqualTo("COMPLETED");
            assertThat(body.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_002);
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test void aQuestionAboutNowStaysWithTheRealTimeAnalysis() throws Exception {
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, "지금 " + branchName + "에 빈자리가 얼마나 있어?", "all"));
            assertThat(body.get("analysisId").asString()).as(body.toString()).isEqualTo(OpenStudyAnalyses.ID_POC_004);
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = EXPORT, matches = "true")
    void exportsTheRequestTheServerSendsQwen() throws Exception {
        try (var s = login(owner)) {
            ask(s, GANGNAM_QUESTION, "all");
        }
        assertThat(captured).as("the model was not asked").isNotNull();
        // Rebuilt exactly as SpringAiModelGateway.plan sends it: system rules, the canonical {data, planSchema} payload,
        // and the strict JSON schema of the response.
        var rulesField = com.openstudy.domainlens.ai.SpringAiModelGateway.class.getDeclaredField("PLANNING_RULES");
        rulesField.setAccessible(true);
        String system = (String) rulesField.get(null);
        assertThat(captured.formulas()).as("formula rules would be appended").isEmpty();
        var from = Class.forName("com.openstudy.domainlens.ai.SemanticPlanSchema").getDeclaredMethod("from", PlanningPrompt.class);
        from.setAccessible(true);
        Object schema = from.invoke(null, captured);
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("data", captured);
        payload.put("planSchema", schema);
        String user = com.openstudy.domainlens.api.ir.ContractJson.canonical(payload);
        String schemaJson = com.openstudy.domainlens.api.ir.ContractJson.write(schema);
        var dir = java.nio.file.Path.of("build/qwen-congestion-prompt");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("system.txt"), system);
        java.nio.file.Files.writeString(dir.resolve("user.json"), user);
        java.nio.file.Files.writeString(dir.resolve("response-schema.json"), schemaJson);
    }

    static JsonNode component(JsonNode view, String type) {
        for (var component : view.get("components")) if (type.equals(component.at("/componentRef/id").asString())) return component;
        throw new AssertionError("no " + type + " in " + view);
    }

    // --- replayed model: occupancy by weekday and hour, as qwen3.8-flash plans it; the server supplies the period ---

    ModelGateway replay() {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return true; }
            @Override public SemanticPlan plan(PlanningPrompt prompt, GenerationBudget budget, SemanticPlan previous, String failure) {
                modelCalls.incrementAndGet();
                captured = prompt;
                if (override != null) return override;
                String recorded = System.getenv(RECORDED);
                if (recorded != null) {
                    try {
                        return com.openstudy.domainlens.api.ir.ContractJson.read(
                                java.nio.file.Files.readString(java.nio.file.Path.of(recorded)), SemanticPlan.class);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                String question = prompt.question();
                var evidence = List.of(new SemanticPlan.Evidence(METRIC, SemanticPlan.Origin.QUESTION, question),
                        new SemanticPlan.Evidence(WEEKDAY, SemanticPlan.Origin.QUESTION, question),
                        new SemanticPlan.Evidence(HOUR, SemanticPlan.Origin.QUESTION, question));
                var request = new SemanticPlan.Request(QuerySpec.Mode.AGGREGATE, null, null, List.of(METRIC), List.of(WEEKDAY, HOUR),
                        null, List.of(), null, null, null, false, List.of(), evidence, List.of());
                return new SemanticPlan(SemanticPlan.VERSION, IrStatus.READY, null, List.of(), List.of(request), List.of(), null);
            }
            @Override public IrDocument generate(String ask, List<String> refs) { throw new AssertionError("planning only"); }
        };
    }

    // --- fixtures: sessions need a customer and a pass; their rows are irrelevant to occupancy, so FK checks are off ---

    void stay(long seat, LocalDate day, int fromHour, int toHour) {
        Instant in = day.atTime(fromHour, 0).atZone(OpenStudyClock.KST).toInstant();
        Instant out = day.atTime(toHour, 0).atZone(OpenStudyClock.KST).toInstant();
        String key = "CG-" + UUID.randomUUID();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try (var insert = connection.prepareStatement("INSERT INTO seat_sessions(customer_id,pass_id,branch_id,seat_id,status,"
                        + "checked_in_at,effective_checked_in_at,checked_out_at,effective_checked_out_at,start_request_key,end_request_key) "
                        + "VALUES(0,0,?,?,'CLOSED',?,?,?,?,?,?)")) {
                    insert.setLong(1, branch);
                    insert.setLong(2, seat);
                    insert.setTimestamp(3, Timestamp.from(in));
                    insert.setTimestamp(4, Timestamp.from(in));
                    insert.setTimestamp(5, Timestamp.from(out));
                    insert.setTimestamp(6, Timestamp.from(out));
                    insert.setString(7, key);
                    insert.setString(8, key + "-E");
                    insert.executeUpdate();
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }
}
