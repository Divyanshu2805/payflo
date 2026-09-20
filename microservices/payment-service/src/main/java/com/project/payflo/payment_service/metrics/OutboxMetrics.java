package com.project.payflo.payment_service.metrics;

import com.project.payflo.common_lib.enums.OutboxStatus;
import com.project.payflo.payment_service.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Outbox rows written but not yet published to Kafka. A steadily growing value means the poller
// can't keep up (or Kafka is down) and webhooks/settlement are falling behind real time.
@Component
@RequiredArgsConstructor
public class OutboxMetrics {

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void register() {
        Gauge.builder("payflo.outbox.pending", outboxEventRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Outbox events not yet published to Kafka")
                .register(meterRegistry);
    }
}
