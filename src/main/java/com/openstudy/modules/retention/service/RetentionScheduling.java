package com.openstudy.modules.retention.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * #100: runs the retention rules once a day, after the 03:30 AI trace cleanup. A disabled rule does nothing, so the job is
 * harmless until an operator enables rules. Single instance: with several, one of them must own this job.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "openstudy.retention.schedule-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RetentionScheduling {
    private final RetentionService retention;

    @Scheduled(cron = "${openstudy.retention.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void runDaily() { retention.runAll(); }
}
