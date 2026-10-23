package com.openstudy.localpreview;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Temporary, explicitly local workspace preview until business APIs are implemented. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "openstudy.local-preview.enabled", havingValue = "true", matchIfMissing = true)
@Import(LocalPreviewData.class)
public class LocalPreviewConfiguration {
    @Bean
    CommandLineRunner localPreviewBranches(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return args -> transactions.executeWithoutResult(status -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM branches", Long.class) == 0) {
                jdbc.update("INSERT INTO branches (code,name,address,phone) VALUES "
                        + "('LOCAL-PREVIEW-01','강남 스터디카페 (샘플)','서울 강남구 샘플 주소','02-0000-0001'),"
                        + "('LOCAL-PREVIEW-02','성수 스터디카페 (샘플)','서울 성동구 샘플 주소','02-0000-0002')");
            }
        });
    }
}
