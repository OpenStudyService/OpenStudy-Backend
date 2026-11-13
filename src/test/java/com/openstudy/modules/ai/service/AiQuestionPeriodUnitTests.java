package com.openstudy.modules.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** 2026-11-13: the period a question names for a registered analysis, on a fixed clock (2026-11-13 12:00 KST). */
class AiQuestionPeriodUnitTests {
    static OpenStudyIntentAnalyzer analyzer;
    static OpenStudyClock clock = new OpenStudyClock("2026-11-13T03:00:00Z", false);

    @BeforeAll static void catalog() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPackagesToScan("com.openstudy");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect", "org.hibernate.dialect.MySQLDialect",
                "hibernate.temp.use_jdbc_metadata_defaults", "false", "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        analyzer = new OpenStudyIntentAnalyzer(OpenStudyCatalogConfiguration.createRegistry(factory.getObject()), clock);
    }

    @Test void oneNamedPeriodIsRead() {
        var lastMonth = analyzer.questionPeriod("지난 달 지점별 매출을 비교해줘");
        assertThat(lastMonth.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lastMonth.toExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(analyzer.questionPeriod("저번달 지점별 매출").from()).isEqualTo(LocalDate.of(2026, 10, 1));
        var range = analyzer.questionPeriod("2026년 9월부터 10월까지 지점별 매출");
        assertThat(range.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(range.toExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test void noneOrSeveralPeriods() {
        assertThat(analyzer.questionPeriod("지점별 매출을 비교해줘").resolved()).isFalse();
        assertThat(analyzer.questionPeriod("지점별 매출을 비교해줘").ambiguous()).isFalse();
        assertThat(analyzer.questionPeriod("이번 달과 지난달 지점별 매출").ambiguous()).isTrue();
    }

    @Test void titlesNameThePeriod() {
        assertThat(AiPeriodTitles.label(clock.fromDates("Q", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))).isEqualTo("2026년 10월");
        assertThat(AiPeriodTitles.label(clock.fromDates("Q", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)))).isEqualTo("2026-11-02~2026-11-08");
        assertThat(AiPeriodTitles.replace("이번 달 전체 순매출 요약", "2026년 10월")).isEqualTo("2026년 10월 전체 순매출 요약");
        assertThat(AiPeriodTitles.replace("지점별 순매출 비교", "2026년 10월")).isEqualTo("지점별 순매출 비교");
    }
}
