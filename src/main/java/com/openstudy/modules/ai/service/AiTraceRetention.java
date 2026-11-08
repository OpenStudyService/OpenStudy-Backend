package com.openstudy.modules.ai.service;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.ai.repository.AiTraceRepository;

/**
 * M10-D04 (#96): a diagnostic trace keeps the question text for 30 days, then only the text is removed. Runs daily;
 * {@code openstudy.ai.trace-retention.enabled=false} turns the schedule off (the method can still be called).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "openstudy.ai.trace-retention.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AiTraceRetention {
    private final AiTraceRepository traces;
    private final Clock clock;

    @Scheduled(cron = "${openstudy.ai.trace-retention.cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @Transactional
    public int clearExpiredQuestions() {
        int cleared = traces.clearExpiredQuestions(clock.instant());
        if (cleared > 0) log.info("AI trace questions past 30 days cleared: {}", cleared);
        return cleared;
    }
}
