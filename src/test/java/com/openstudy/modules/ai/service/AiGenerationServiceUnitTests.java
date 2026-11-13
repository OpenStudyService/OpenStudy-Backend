package com.openstudy.modules.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.ir.QuerySpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** #233: a saved generated query run again follows the clock for its relative period; engine stops map to responses. */
class AiGenerationServiceUnitTests {
    static AiGenerationService service;

    @BeforeAll static void catalog() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPackagesToScan("com.openstudy");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.temp.use_jdbc_metadata_defaults", "false", "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        var registry = OpenStudyCatalogConfiguration.createRegistry(factory.getObject());
        // Asked in October, run again on 2026-11-13 12:00 KST.
        var intents = new OpenStudyIntentAnalyzer(registry, new OpenStudyClock("2026-11-13T03:00:00Z", false));
        service = new AiGenerationService(null, null, intents, registry);
    }

    static Filter.Leaf leaf(String field, Filter.Operator op, Object value) {
        return new Filter.Leaf(field, op, List.of(value));
    }

    static QuerySpec saved(Filter filter) {
        return new QuerySpec("q0", "payment", "payment", QuerySpec.Mode.AGGREGATE,
                List.of(new QuerySpec.Selection("branch.name", "d0")), List.of(new QuerySpec.Selection("sales.approved_amount_krw", "m0")),
                filter, List.of(), new QuerySpec.Page(0, 10), 10, null);
    }

    @Test void aRelativePeriodIsReadAgainAndOtherConditionsStay() {
        var october = new Filter.Group(Filter.Junction.AND, List.of(
                leaf("payment.paid_at", Filter.Operator.GTE, "2026-09-30T15:00:00Z"),
                leaf("payment.paid_at", Filter.Operator.LT, "2026-10-31T15:00:00Z"),
                leaf("payment.item_type", Filter.Operator.EQ, "PASS")));

        var rebased = service.rebase(saved(october), "이번 달 지점별 결제 금액 합계 보여줘");

        assertThat(rebased.filter()).isEqualTo(new Filter.Group(Filter.Junction.AND, List.of(
                leaf("payment.item_type", Filter.Operator.EQ, "PASS"),
                leaf("payment.paid_at", Filter.Operator.GTE, "2026-10-31T15:00:00Z"),
                leaf("payment.paid_at", Filter.Operator.LT, "2026-11-30T15:00:00Z"))));
    }

    @Test void aQuestionWithoutARelativePeriodKeepsItsSavedFilter() {
        var filter = leaf("payment.item_type", Filter.Operator.EQ, "PASS");
        assertThat(service.rebase(saved(filter), "지점별 결제 금액 합계 보여줘").filter()).isEqualTo(filter);
    }

    @Test void engineStopsMapToResponses() {
        assertThat(AiGenerationService.failure(new EngineException(EngineErrorCode.SCOPE_DENIED, "x"), 1))
                .isEqualTo(new AiGenerationService.Failed(403, "FORBIDDEN", "현재 권한과 지점 범위로는 이 질문을 조회할 수 없어요.", 1));
        assertThat(AiGenerationService.failure(new EngineException(EngineErrorCode.QUERY_TIMEOUT, "x"), 1))
                .isInstanceOfSatisfying(AiGenerationService.Failed.class, f -> assertThat(f.code()).isEqualTo("AI_TIMEOUT"));
        // M10 model boundary: a broken model reply is an ordinary "unsupported" answer; an internal failure is a 502.
        assertThat(AiGenerationService.failure(new EngineException(EngineErrorCode.MODEL_FAILED, "x"), 1))
                .isInstanceOf(AiGenerationService.Declined.class);
        assertThat(AiGenerationService.failure(new EngineException(EngineErrorCode.INTERNAL_ERROR, "x"), 1))
                .isInstanceOfSatisfying(AiGenerationService.Failed.class, f -> assertThat(f.httpStatus()).isEqualTo(502));
        // A query the server will not run is "unsupported", not an error.
        assertThat(AiGenerationService.failure(new EngineException(EngineErrorCode.IR_INVALID, "x"), 1))
                .isInstanceOf(AiGenerationService.Declined.class);
    }
}
