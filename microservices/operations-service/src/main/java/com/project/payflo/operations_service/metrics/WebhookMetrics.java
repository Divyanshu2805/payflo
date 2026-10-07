package com.project.payflo.operations_service.metrics;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * What the webhook SLA ("99% delivered within 30 seconds, 100% within 24 hours") is read from.
 *
 * <ul>
 *   <li>{@code payflo.webhook.delivery.latency}: for each event the merchant's endpoint accepted, how long after the
 *       change it happened. Histogram buckets include 30 s and 24 h, so the SLA is the share of observations in
 *       {@code le="30.0"} over the count. Events that die are counted by {@code payflo.webhook.deliveries{outcome=dead}}.</li>
 *   <li>{@code payflo.webhook.pending} and {@code payflo.webhook.oldest.unattempted.age}: events waiting for a first
 *       attempt, which the latency histogram can't show until they are delivered. A growing age is the SLA being
 *       missed right now.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WebhookMetrics {

    static final String LATENCY = "payflo.webhook.delivery.latency";

    private final WebhookEventRepository webhookEventRepository;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void register() {
        Gauge.builder("payflo.webhook.pending", webhookEventRepository,
                        repo -> repo.countByStatus(WebhookEventStatus.PENDING))
                .description("Webhook events waiting to be delivered (including those in flight)")
                .register(meterRegistry);
        Gauge.builder("payflo.webhook.oldest.unattempted.age", webhookEventRepository, this::oldestUnattemptedSeconds)
                .description("Seconds the oldest webhook event still waiting for its first attempt has been due")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    private double oldestUnattemptedSeconds(WebhookEventRepository repo) {
        return repo.oldestUnattemptedDueAt(WebhookEventStatus.PENDING)
                .map(dueAt -> Math.max(0, Duration.between(dueAt, LocalDateTime.now()).toMillis() / 1000.0))
                .orElse(0.0);
    }

    /** An event the merchant's endpoint accepted {@code latency} after the change it reports. */
    public void recordDelivered(Duration latency, boolean retried) {
        Timer.builder(LATENCY)
                .description("Time from a change to the merchant's endpoint accepting its webhook")
                .tag("retried", String.valueOf(retried))
                .serviceLevelObjectives(Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60),
                        Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(24))
                .register(meterRegistry)
                .record(latency.isNegative() ? Duration.ZERO : latency);
    }
}
