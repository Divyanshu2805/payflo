package com.project.payflo.payment_service.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    // With virtual threads on, Spring Boot's default scheduler runs every fixed-delay job on one
    // scheduler thread, so a long run of one job (the bank simulator) stalls the others (the outbox
    // poller). A pooled scheduler, sized by spring.task.scheduling.pool.size, lets them overlap.
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
