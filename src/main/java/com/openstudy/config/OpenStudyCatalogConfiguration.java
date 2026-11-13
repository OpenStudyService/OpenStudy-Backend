package com.openstudy.config;

import com.openstudy.domainlens.ai.OnnxEmbeddingGateway;
import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import com.openstudy.domainlens.api.retrieval.EmbeddingStore;
import com.openstudy.domainlens.api.spi.MetricImpl;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.JpaModelCollector;
import com.openstudy.domainlens.catalog.SearchDocuments;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.Retriever;
import com.openstudy.domainlens.work.persistence.JpaEmbeddingStore;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenStudy semantic catalog configuration (`OS-DL-0308`).
 * Builds the immutable {@link SemanticRegistry} and local ONNX {@link EmbeddingIndex} from JPA metamodel entities.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DomainLensProperties.class)
@Slf4j
public class OpenStudyCatalogConfiguration {
    public static final String VERSION = "openstudy-v1";
    public static final String METRIC_VERSION = "1";

    public static final Set<String> POLICY_REFERENCES = Set.of(
            "openstudy.read.ai",
            "openstudy.read.sales",
            "openstudy.branchScope",
            "openstudy.globalScope",
            "openstudy.field.public",
            "openstudy.field.employee",
            "openstudy.read.pii.denied",
            "openstudy.field.pii.denied"
    );

    public static final Set<String> FORBIDDEN_SOURCES = Set.of(
            "customers",
            "customer_service_interactions",
            "messages",
            "conversations",
            "inquiries",
            "spring_session",
            "invitations",
            "employee_invitations"
    );

    public static final List<String> GRAINS = List.of(
            "branch",
            "payment",
            "refund",
            "product_category",
            "product",
            "pass",
            "seat",
            "seat_session",
            "room",
            "room_reservation",
            "locker",
            "locker_assignment",
            "work_task",
            "attendance_record",
            "attendance_break",
            "attendance_correction",
            "employee"
    );

    @Bean
    @ConditionalOnMissingBean
    public SemanticRegistry openStudySemanticRegistry(EntityManagerFactory factory) {
        return createRegistry(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingStore openStudyEmbeddingStore() {
        return new JpaEmbeddingStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public EmbeddingGateway openStudyEmbeddingGateway(DomainLensProperties properties) {
        var emb = properties.getEmbedding();
        Path modelDir = resolvePath(emb.getDirectory(), ".models/koen-e5-tiny-q8");
        // The model is not committed (.gitignore). Without it the app still starts and only AI retrieval is unavailable;
        // a model that is present but broken still fails fast inside OnnxEmbeddingGateway.
        if (!Files.isDirectory(modelDir)) {
            log.warn("DomainLens embedding model not found at {}; AI retrieval is disabled until it is installed",
                    modelDir.toAbsolutePath());
            return new UnavailableEmbeddingGateway();
        }
        return new OnnxEmbeddingGateway(modelDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingIndex openStudyEmbeddingIndex(
            SemanticRegistry registry,
            EmbeddingGateway gateway,
            EmbeddingStore store
    ) {
        if (gateway instanceof UnavailableEmbeddingGateway) {
            return new EmbeddingIndex("unavailable", gateway.model(), gateway.dimensions(), List.of());
        }
        return EmbeddingIndex.build(new SearchDocuments().build(registry), gateway, store);
    }

    /** Stands in for the local model when it is not installed: every embedding request reports NOT_READY. */
    static final class UnavailableEmbeddingGateway implements EmbeddingGateway, AutoCloseable {
        @Override public String model() { return "unavailable"; }
        @Override public int dimensions() { return 0; }
        @Override public List<List<Double>> embed(List<String> texts) {
            throw new EngineException(EngineErrorCode.NOT_READY, "Local embedding model is not installed");
        }
        @Override public void close() { }
    }

    public static SemanticRegistry createRegistry(EntityManagerFactory factory) {
        var model = new JpaModelCollector().collect(factory);
        Map<String, String> grains = GRAINS.stream().collect(Collectors.toUnmodifiableMap(g -> g, g -> g));
        var metrics = metricDefinitions();
        var implementations = metrics.stream().map(SemanticRegistry.Metric::implementationRef).collect(Collectors.toSet());
        return new SemanticRegistry(
                VERSION,
                model,
                grains,
                POLICY_REFERENCES,
                implementations,
                metrics,
                List.of(),
                terms(),
                List.of(),
                FORBIDDEN_SOURCES
        );
    }

    public static List<SemanticRegistry.Metric> metricDefinitions() {
        return List.of(
                new SemanticRegistry.Metric(
                        "sales.net_sales_krw",
                        "openstudy.sales.branchComparison",
                        "payment",
                        "payment",
                        SemanticRegistry.Aggregate.SUM,
                        "payment.amount_krw",
                        List.of("payment.branch"),
                        null,
                        METRIC_VERSION,
                        "KRW",
                        0,
                        Set.of("payment.paid_at", "payment.status", "payment.branch_id", "branch.name", "refund.amount_krw", "refund.refunded_at")
                ),
                new SemanticRegistry.Metric(
                        "seats.hourly_congestion",
                        "openstudy.seats.hourlyCongestion",
                        "seat_session",
                        "seat_session",
                        SemanticRegistry.Aggregate.COUNT,
                        "seat_session.id",
                        List.of("seat_session.branch"),
                        null,
                        METRIC_VERSION,
                        "%",
                        1,
                        Set.of("seat_session.checked_in_at", "seat_session.checked_out_at", "seat_session.seat_id", "branch.name")
                ),
                new SemanticRegistry.Metric(
                        "passes.top_products",
                        "openstudy.passes.topProducts",
                        "pass",
                        "pass",
                        SemanticRegistry.Aggregate.COUNT,
                        "pass.id",
                        List.of("pass.product"),
                        null,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("pass.issued_at", "pass.status", "product.name", "product.price_krw")
                ),
                new SemanticRegistry.Metric(
                        "seats.realtime_availability",
                        "openstudy.seats.realtimeAvailability",
                        "seat",
                        "seat",
                        SemanticRegistry.Aggregate.COUNT,
                        "seat.id",
                        List.of("seat.branch"),
                        null,
                        METRIC_VERSION,
                        "석",
                        0,
                        Set.of("seat.seat_no", "seat.status", "branch.name")
                ),
                new SemanticRegistry.Metric(
                        "rooms.weekly_reservations",
                        "openstudy.rooms.weeklyReservations",
                        "room_reservation",
                        "room_reservation",
                        SemanticRegistry.Aggregate.COUNT,
                        "room_reservation.id",
                        List.of("room_reservation.room"),
                        null,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("room_reservation.start_at", "room_reservation.end_at", "room_reservation.status", "room.name", "room.branch_id")
                ),
                new SemanticRegistry.Metric(
                        "passes.expiring_soon",
                        "openstudy.passes.expiringSoon",
                        "pass",
                        "pass",
                        SemanticRegistry.Aggregate.COUNT,
                        "pass.id",
                        List.of("pass.branch"),
                        null,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("pass.expires_at", "pass.status", "pass.remaining_minutes", "branch.name")
                ),
                new SemanticRegistry.Metric(
                        "sales.daily_trend",
                        "openstudy.sales.dailyTrend",
                        "payment",
                        "payment",
                        SemanticRegistry.Aggregate.SUM,
                        "payment.amount_krw",
                        List.of(),
                        null,
                        METRIC_VERSION,
                        "KRW",
                        0,
                        Set.of("payment.paid_at", "payment.status", "refund.amount_krw", "refund.refunded_at")
                ),
                new SemanticRegistry.Metric(
                        "sales.by_item_type",
                        "openstudy.sales.byItemType",
                        "payment",
                        "payment",
                        SemanticRegistry.Aggregate.SUM,
                        "payment.amount_krw",
                        List.of(),
                        null,
                        METRIC_VERSION,
                        "KRW",
                        0,
                        Set.of("payment.item_type", "payment.paid_at", "payment.status", "refund.amount_krw", "refund.refunded_at")
                ),
                new SemanticRegistry.Metric(
                        "tasks.urgent_todo",
                        "openstudy.tasks.urgentTodo",
                        "work_task",
                        "work_task",
                        SemanticRegistry.Aggregate.COUNT,
                        "work_task.id",
                        List.of("work_task.branch"),
                        null,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("work_task.title", "work_task.priority", "work_task.status", "work_task.due_at", "work_task.assignee_employee_id", "branch.name")
                ),
                new SemanticRegistry.Metric(
                        "attendance.weekly_work_minutes",
                        "openstudy.attendance.weeklyWorkMinutes",
                        "attendance_record",
                        "attendance_record",
                        SemanticRegistry.Aggregate.SUM,
                        "attendance_record.id",
                        List.of("attendance_record.employee"),
                        null,
                        METRIC_VERSION,
                        "분",
                        0,
                        Set.of("attendance_record.clock_in_at", "attendance_record.clock_out_at", "attendance_record.status", "employee.name", "branch.name")
                ),
                // #232: general metrics for the generation path. They are not tied to a registered analysis, so the
                // model may combine them with dimensions and periods; the server runs them as a JPA aggregate.
                new SemanticRegistry.Metric(
                        "sales.approved_amount_krw",
                        GENERAL_METRIC_PREFIX + "sales.approvedAmount",
                        "payment",
                        "payment",
                        SemanticRegistry.Aggregate.SUM,
                        "payment.amount_krw",
                        List.of(),
                        APPROVED_PAYMENT,
                        METRIC_VERSION,
                        "KRW",
                        0,
                        Set.of("payment.status", "payment.paid_at")
                ),
                new SemanticRegistry.Metric(
                        "sales.approved_count",
                        GENERAL_METRIC_PREFIX + "sales.approvedCount",
                        "payment",
                        "payment",
                        SemanticRegistry.Aggregate.COUNT,
                        "payment.id",
                        List.of(),
                        APPROVED_PAYMENT,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("payment.status", "payment.paid_at")
                ),
                new SemanticRegistry.Metric(
                        "passes.issued_count",
                        GENERAL_METRIC_PREFIX + "passes.issuedCount",
                        "pass",
                        "pass",
                        SemanticRegistry.Aggregate.COUNT,
                        "pass.id",
                        List.of(),
                        null,
                        METRIC_VERSION,
                        "건",
                        0,
                        Set.of("pass.issued_at")
                )
        );
    }

    /** Implementation ids of general metrics; every other metric belongs to a registered analysis. */
    public static final String GENERAL_METRIC_PREFIX = "openstudy.metric.";
    /** A payment counts as sales once it is approved; pending and failed payments never do. */
    static final com.openstudy.domainlens.api.ir.Filter APPROVED_PAYMENT =
            new com.openstudy.domainlens.api.ir.Filter.Leaf("payment.status", com.openstudy.domainlens.api.ir.Filter.Operator.EQ, List.of("APPROVED"));
    /** The time field a resolved period ("이번 달") constrains for each general metric. */
    public static final Map<String, String> PERIOD_FIELDS = Map.of(
            "sales.approved_amount_krw", "payment.paid_at",
            "sales.approved_count", "payment.paid_at",
            "passes.issued_count", "pass.issued_at"
    );

    public static boolean general(SemanticRegistry.Metric metric) {
        return metric.implementationRef().startsWith(GENERAL_METRIC_PREFIX);
    }

    public static List<MetricImpl> metricImplementations() {
        return metricDefinitions().stream()
                .map(m -> (MetricImpl) new MetricImpl() {
                    @Override public String id() { return m.implementationRef(); }
                    @Override public String version() { return m.version(); }
                    @Override public String grain() { return m.grain(); }
                })
                .toList();
    }

    public static List<SemanticRegistry.Term> terms() {
        return List.of(
                new SemanticRegistry.Term(
                        "openstudy.term.branchSales",
                        "sales.net_sales_krw",
                        List.of("지점별 매출", "순매출", "승인 매출", "이번 달 매출", "이번 달 지점별 매출을 비교해줘", "지점 매출 비교")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.congestion",
                        "seats.hourly_congestion",
                        List.of("언제 가장 붐벼", "혼잡도", "피크 시간대", "시간대별 좌석 점유율", "강남점은 언제 가장 붐벼")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.topPasses",
                        "passes.top_products",
                        List.of("가장 잘 팔린 이용권", "인기 이용권", "이용권 TOP 5", "판매 건수", "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.realtimeSeats",
                        "seats.realtime_availability",
                        List.of("빈자리", "남은 좌석", "현재 좌석 현황", "점유 좌석", "지금 강남점에 빈자리가 얼마나 있어")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.weeklyRooms",
                        "rooms.weekly_reservations",
                        List.of("스터디룸 예약 현황", "이번 주 예약", "룸 예약", "이번 주 스터디룸 예약 현황을 보여줘")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.expiringPasses",
                        "passes.expiring_soon",
                        List.of("만료되는 이용권", "7일 이내 만료", "만료 예정", "앞으로 7일 이내에 만료되는 이용권을 보여줘")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.dailySales",
                        "sales.daily_trend",
                        List.of("일별 매출 추이", "일별 매출 그래프", "지난달 매출 추이", "지난달 일별 매출 추이를 그래프로 보여줘")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.itemTypeSales",
                        "sales.by_item_type",
                        List.of("이용권 스터디룸 사물함 매출", "상품군별 매출", "어디서 매출이 가장 많이 나왔어", "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.urgentTasks",
                        "tasks.urgent_todo",
                        List.of("급하게 처리해야 할 업무", "긴급 업무", "기한 지난 업무", "미완료 업무", "지금 급하게 처리해야 할 업무가 뭐야")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.weeklyAttendance",
                        "attendance.weekly_work_minutes",
                        List.of("직원별 근무시간", "지난주 근무시간", "출퇴근 시간 비교", "지난주 직원별 근무시간을 비교해줘")
                ),
                // #232: general metrics. "매출" alone stays with the registered net sales analysis, so these names
                // say what they count: approved payment amounts, not refund-adjusted sales.
                new SemanticRegistry.Term(
                        "openstudy.term.approvedAmount",
                        "sales.approved_amount_krw",
                        List.of("결제 금액", "결제액", "결제 승인액", "승인 금액", "승인된 결제 금액")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.approvedCount",
                        "sales.approved_count",
                        List.of("결제 건수", "결제 수", "승인 건수", "승인된 결제 건수")
                ),
                new SemanticRegistry.Term(
                        "openstudy.term.issuedPasses",
                        "passes.issued_count",
                        List.of("이용권 발급 수", "이용권 발급 건수", "발급된 이용권 수", "발급 건수")
                )
        );
    }

    public static Path resolvePath(String configured, String relativeFallback) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.exists(p)) return p;
        }
        for (Path candidate : List.of(
                Path.of(relativeFallback),
                Path.of("OpenStudy-Backend", relativeFallback),
                Path.of("..", relativeFallback)
        )) {
            if (Files.exists(candidate)) return candidate;
        }
        return Path.of(relativeFallback);
    }
}
