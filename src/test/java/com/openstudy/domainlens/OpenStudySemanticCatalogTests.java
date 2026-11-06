package com.openstudy.domainlens;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.ai.OnnxEmbeddingGateway;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.spi.FieldPolicy;
import com.openstudy.domainlens.api.spi.PolicyBinding;
import com.openstudy.domainlens.api.spi.ReadPolicy;
import com.openstudy.domainlens.api.spi.RowPolicy;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.JpaModel;
import com.openstudy.domainlens.catalog.JpaModelCollector;
import com.openstudy.domainlens.catalog.SearchDocuments;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.Retriever;
import com.openstudy.domainlens.security.PolicyEngine;
import com.openstudy.domainlens.work.persistence.JpaEmbeddingStore;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

class OpenStudySemanticCatalogTests {
    static LocalContainerEntityManagerFactoryBean factoryBean;
    static EntityManagerFactory factory;
    static JpaModel model;
    static SemanticRegistry registry;

    @BeforeAll
    static void setUpCatalog() {
        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setPackagesToScan("com.openstudy");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setJpaPropertyMap(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.temp.use_jdbc_metadata_defaults", "false",
                "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.auto", "none"
        ));
        factoryBean.afterPropertiesSet();
        factory = factoryBean.getObject();
        model = new JpaModelCollector().collect(factory);
        registry = OpenStudyCatalogConfiguration.createRegistry(factory);
    }

    @AfterAll
    static void closeFactory() {
        if (factoryBean != null) {
            factoryBean.destroy();
        }
    }

    @Test
    void jpaModelCollectorHasZeroSensitiveOrUnsupportedDiagnostics() {
        assertThat(model.diagnostics())
                .as("JpaModelCollector diagnostics across all OpenStudy entities")
                .isEmpty();
    }

    @Test
    void semanticRegistryExposesAllSeventeenOpenStudyEntitiesAndHidesPiiAndCredentials() {
        Set<String> expectedSources = Set.of(
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
        assertThat(registry.sources().keySet()).containsExactlyInAnyOrderElementsOf(expectedSources);
        assertThat(registry.sources()).doesNotContainKey("customer");

        Set<String> hiddenFieldIds = Set.of(
                "customer.id",
                "customer.member_no",
                "customer.name",
                "customer.phone",
                "customer.email",
                "customer.memo",
                "employee.email",
                "employee.password_hash",
                "branch.phone",
                "work_task.body",
                "refund.reason",
                "employee_invitation.invitee_email",
                "employee_invitation.token_hash",
                "idempotency_record.request_hash"
        );
        assertThat(registry.fields().keySet()).doesNotContainAnyElementsOf(hiddenFieldIds);
        assertThat(registry.publicNodeIds()).doesNotContainAnyElementsOf(hiddenFieldIds);
        assertThat(registry.publicNodeIds()).doesNotContain("customer");

        assertThat(registry.fields()).containsKeys(
                "branch.id", "branch.code", "branch.name", "branch.status",
                "payment.id", "payment.branch_id", "payment.item_type", "payment.amount_krw", "payment.status", "payment.paid_at",
                "refund.id", "refund.payment_id", "refund.amount_krw", "refund.refunded_at",
                "product_category.id", "product_category.name", "product_category.active",
                "product.id", "product.name", "product.pass_type", "product.duration_value", "product.price_krw", "product.status",
                "pass.id", "pass.branch_id", "pass.product_id", "pass.payment_id", "pass.pass_type", "pass.remaining_minutes", "pass.expires_at", "pass.status", "pass.issued_at",
                "seat.id", "seat.branch_id", "seat.seat_no", "seat.status",
                "seat_session.id", "seat_session.branch_id", "seat_session.seat_id", "seat_session.checked_in_at", "seat_session.checked_out_at", "seat_session.end_reason",
                "room.id", "room.branch_id", "room.room_no", "room.name", "room.capacity", "room.rate_per_hour_krw", "room.status",
                "room_reservation.id", "room_reservation.room_id", "room_reservation.payment_id", "room_reservation.start_at", "room_reservation.end_at", "room_reservation.status",
                "locker.id", "locker.branch_id", "locker.locker_no", "locker.rate_per_4weeks_krw", "locker.status",
                "locker_assignment.id", "locker_assignment.locker_id", "locker_assignment.start_date", "locker_assignment.end_date", "locker_assignment.status",
                "work_task.id", "work_task.branch_id", "work_task.title", "work_task.category", "work_task.priority", "work_task.status", "work_task.due_at", "work_task.assignee_employee_id",
                "attendance_record.id", "attendance_record.branch_id", "attendance_record.employee_id", "attendance_record.clock_in_at", "attendance_record.clock_out_at", "attendance_record.status",
                "attendance_break.id", "attendance_break.break_start_at", "attendance_break.break_end_at",
                "attendance_correction.id", "attendance_correction.status", "attendance_correction.requested_clock_in_at", "attendance_correction.requested_clock_out_at",
                "employee.id", "employee.name", "employee.nickname", "employee.status"
        );

        assertThat(registry.relations()).containsKeys(
                "payment.branch",
                "refund.payment",
                "product.category",
                "pass.branch",
                "pass.product",
                "pass.payment",
                "seat.branch",
                "seat_session.branch",
                "seat_session.seat",
                "room.branch",
                "room_reservation.room",
                "room_reservation.payment",
                "locker.branch",
                "locker_assignment.locker",
                "work_task.branch",
                "work_task.assignee",
                "attendance_record.branch",
                "attendance_record.employee",
                "attendance_break.attendance_record",
                "attendance_correction.attendance_record"
        );
        assertThat(registry.relations()).doesNotContainKey("payment.customer");
    }

    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void onnxEmbeddingIndexRetrievesRelevantSemanticNodesForAllTenPocQuestions() {
        Path modelDir = OpenStudyCatalogConfiguration.resolvePath(null, ".models/koen-e5-tiny-q8");

        try (var gateway = new OnnxEmbeddingGateway(modelDir)) {
            var docs = new SearchDocuments().build(registry);
            var index = EmbeddingIndex.build(docs, gateway, new JpaEmbeddingStore());
            assertThat(index.dimensions()).isEqualTo(384);
            assertThat(index.entries()).hasSameSizeAs(docs);

            var readPolicies = List.<ReadPolicy>of(
                    allowRead("openstudy.read.ai"),
                    allowRead("openstudy.read.sales")
            );
            var rowPolicies = List.<RowPolicy>of(
                    allowRow("openstudy.branchScope"),
                    allowRow("openstudy.globalScope")
            );
            var fieldPolicies = List.<FieldPolicy>of(
                    allowField("openstudy.field.public"),
                    allowField("openstudy.field.employee")
            );
            var policyEngine = new PolicyEngine(registry, "test-v1", readPolicies, fieldPolicies, rowPolicies, action -> true);
            var retriever = new Retriever(registry, policyEngine, index, gateway, (user, id) -> true, Retriever.Limits.defaults())
                    .connected(true);

            var ownerUser = new UserContext() {
                @Override public String subjectId() { return "owner-1"; }
                @Override public Set<String> roles() { return Set.of("OWNER"); }
                @Override public PolicyBinding policyBinding() {
                    return new PolicyBinding() {
                        @Override public String roleKey() { return "OWNER"; }
                        @Override public Map<String, Object> scope() { return Map.of("branchScope", "all"); }
                    };
                }
            };

            Map<String, String> pocQuestionsToExpectedMetric = Map.of(
                    "이번 달 지점별 매출을 비교해줘", "sales.net_sales_krw",
                    "강남점은 언제 가장 붐벼?", "seats.hourly_congestion",
                    "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘", "passes.top_products",
                    "지금 강남점에 빈자리가 얼마나 있어?", "seats.realtime_availability",
                    "이번 주 스터디룸 예약 현황을 보여줘", "rooms.weekly_reservations",
                    "앞으로 7일 이내에 만료되는 이용권을 보여줘", "passes.expiring_soon",
                    "지난달 일별 매출 추이를 그래프로 보여줘", "sales.daily_trend",
                    "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?", "sales.by_item_type",
                    "지금 급하게 처리해야 할 업무가 뭐야?", "tasks.urgent_todo",
                    "지난주 직원별 근무시간을 비교해줘", "attendance.weekly_work_minutes"
            );

            for (var entry : pocQuestionsToExpectedMetric.entrySet()) {
                String question = entry.getKey();
                String expectedMetric = entry.getValue();
                var context = retriever.retrieve(question, ownerUser);
                assertThat(context.ids())
                        .as("Retrieved context for question: %s", question)
                        .contains(expectedMetric);
            }
        }
    }

    private static ReadPolicy allowRead(String id) {
        return new ReadPolicy() {
            @Override public String id() { return id; }
            @Override public boolean allows(UserContext user, String sourceId) { return true; }
        };
    }

    private static RowPolicy allowRow(String id) {
        return new RowPolicy() {
            @Override public String id() { return id; }
            @Override public Filter predicate(UserContext user, String sourceId) { return null; }
        };
    }

    private static FieldPolicy allowField(String id) {
        return new FieldPolicy() {
            @Override public String id() { return id; }
            @Override public boolean allows(String fieldId, String operation) { return true; }
        };
    }
}
