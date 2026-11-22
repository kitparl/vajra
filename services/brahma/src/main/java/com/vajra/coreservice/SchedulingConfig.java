package com.vajra.coreservice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Background jobs (polling Delta). One thread per job, so a slow candle backfill never delays the 2 s quote poller
 * (Spring's default scheduler runs all fixed-delay jobs on one thread). Tests turn jobs off with
 * {@code vajra.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "vajra.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    /** Quote poller, candle sync, instrument refresh, open-order checker, reconciler, outbox publisher. */
    private static final int JOBS = 6;

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(JOBS);
        scheduler.setThreadNamePrefix("vajra-job-");
        return scheduler;
    }
}
