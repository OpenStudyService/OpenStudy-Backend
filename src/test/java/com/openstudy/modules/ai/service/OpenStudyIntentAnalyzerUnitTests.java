package com.openstudy.modules.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.QueryIntent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** #232: the server's reading of a question on a fixed clock (2026-11-13 12:00 KST, a Friday). */
class OpenStudyIntentAnalyzerUnitTests {
    static SemanticRegistry registry;
    static OpenStudyIntentAnalyzer analyzer;

    @BeforeAll static void catalog() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPackagesToScan("com.openstudy");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.temp.use_jdbc_metadata_defaults", "false", "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        registry = OpenStudyCatalogConfiguration.createRegistry(factory.getObject());
        analyzer = new OpenStudyIntentAnalyzer(registry, new OpenStudyClock("2026-11-13T03:00:00Z", false));
    }

    QueryIntent read(String question) { return analyzer.analyze(question, null); }

    static Filter range(String field, String start, String end) {
        return new Filter.Group(Filter.Junction.AND, List.of(
                new Filter.Leaf(field, Filter.Operator.GTE, List.of(start)), new Filter.Leaf(field, Filter.Operator.LT, List.of(end))));
    }

    @Test void thisMonthByBranch() {
        var intent = read("이번 달 지점별 결제 금액 합계 보여줘");
        assertThat(intent.metrics()).containsExactly("sales.approved_amount_krw");
        assertThat(intent.dimensions()).containsExactly("branch.name");
        assertThat(intent.period()).isEqualTo(range("payment.paid_at", "2026-10-31T15:00:00Z", "2026-11-30T15:00:00Z"));
    }

    @Test void relativePeriodsFollowTheServerClockInKst() {
        assertThat(read("지난달 결제 건수").period()).isEqualTo(range("payment.paid_at", "2026-09-30T15:00:00Z", "2026-10-31T15:00:00Z"));
        assertThat(read("어제 결제 금액").period()).isEqualTo(range("payment.paid_at", "2026-11-11T15:00:00Z", "2026-11-12T15:00:00Z"));
        assertThat(read("이번 주 결제 금액").period()).isEqualTo(range("payment.paid_at", "2026-11-08T15:00:00Z", "2026-11-15T15:00:00Z"));
        assertThat(read("2026년 10월 결제 금액").period()).isEqualTo(range("payment.paid_at", "2026-09-30T15:00:00Z", "2026-10-31T15:00:00Z"));
    }

    @Test void eachMetricConstrainsItsOwnTimeField() {
        var intent = read("최근 7일 이용권 발급 수");
        assertThat(intent.metrics()).containsExactly("passes.issued_count");
        assertThat(intent.period()).isEqualTo(range("pass.issued_at", "2026-11-06T15:00:00Z", "2026-11-13T15:00:00Z"));
    }

    @Test void twoPeriodsResolveNoPeriod() {
        var intent = read("이번 달과 지난달 결제 금액");
        assertThat(intent.metrics()).containsExactly("sales.approved_amount_krw");
        assertThat(intent.period()).isNull();
    }

    @Test void netSalesStaysWithTheRegisteredAnalysis() {
        // "매출" alone names the registered (refund-adjusted) analysis; the analyzer resolves only general metrics.
        assertThat(read("이번 달 지점별 매출").metrics()).isEmpty();
    }

    @Test void theLongestTermWins() {
        assertThat(read("결제 수단별 결제 금액").metrics()).containsExactly("sales.approved_amount_krw");
    }

    @Test void groupingOnlyOnFieldsTheMetricReaches() {
        assertThat(read("이용권 종류별 결제 금액").dimensions()).isEmpty();
        assertThat(read("이용권 종류별 이용권 발급 수").dimensions()).containsExactly("pass.pass_type");
        assertThat(read("상품 구분별 결제 금액").dimensions()).containsExactly("payment.item_type");
    }

    @Test void orderOnlyWithAGrouping() {
        assertThat(read("지점별 결제 금액 많은 순").order()).isEqualTo(QuerySpec.Direction.DESC);
        assertThat(read("결제 금액 많은 순").order()).isNull();
    }

    @Test void everyGeneralMetricHasAPeriodField() {
        var general = registry.metrics().values().stream().filter(OpenStudyCatalogConfiguration::general).map(SemanticRegistry.Metric::id);
        assertThat(general).allMatch(OpenStudyCatalogConfiguration.PERIOD_FIELDS::containsKey);
        assertThat(Set.copyOf(OpenStudyCatalogConfiguration.PERIOD_FIELDS.values())).allMatch(registry.fields()::containsKey);
    }
}
