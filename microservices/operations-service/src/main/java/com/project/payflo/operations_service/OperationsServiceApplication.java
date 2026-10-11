package com.project.payflo.operations_service;

import com.project.payflo.common_lib.outbox.OutboxConfiguration;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableFeignClients
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@EnableJpaAuditing(auditorAwareRef = "auditorAwareImpl")
@Import(OutboxConfiguration.class)
public class OperationsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationsServiceApplication.class, args);
    }

}
