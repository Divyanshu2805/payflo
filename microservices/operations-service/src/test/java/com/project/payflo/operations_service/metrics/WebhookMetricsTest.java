package com.project.payflo.operations_service.metrics;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookMetricsTest {

    private final WebhookEventRepository events = mock(WebhookEventRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WebhookMetrics metrics = new WebhookMetrics(events, registry);

    @BeforeEach
    void registered() {
        metrics.register();
    }

    private long deliveredWithin(Timer timer, double seconds) {
        for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
            if (bucket.bucket(TimeUnit.SECONDS) == seconds) {
                return (long) bucket.count();
            }
        }
        throw new AssertionError("no histogram bucket at " + seconds + " s");
    }

    @Test
    void theLatencyHistogramHasTheBucketsTheSlaIsReadFrom() {
        metrics.recordDelivered(Duration.ofSeconds(3), false);
        metrics.recordDelivered(Duration.ofSeconds(29), false);
        metrics.recordDelivered(Duration.ofSeconds(45), false);
        metrics.recordDelivered(Duration.ofHours(2), false);

        Timer timer = registry.get(WebhookMetrics.LATENCY).tag("retried", "false").timer();

        assertThat(timer.count()).isEqualTo(4);
        // "within 30 seconds" is the le="30.0" bucket, "within 24 hours" the last one: the two SLA lines
        assertThat(deliveredWithin(timer, 30)).isEqualTo(2);
        assertThat(deliveredWithin(timer, 24 * 3600)).isEqualTo(4);
    }

    @Test
    void aRetriedDeliveryIsCountedSeparatelyFromOneThatWentThroughFirstTime() {
        metrics.recordDelivered(Duration.ofSeconds(2), false);
        metrics.recordDelivered(Duration.ofMinutes(1), true);

        assertThat(registry.get(WebhookMetrics.LATENCY).tag("retried", "false").timer().count()).isEqualTo(1);
        assertThat(registry.get(WebhookMetrics.LATENCY).tag("retried", "true").timer().count()).isEqualTo(1);
    }

    @Test
    void aClockSkewedNegativeLatencyIsRecordedAsZeroNotDropped() {
        metrics.recordDelivered(Duration.ofSeconds(-3), false);

        Timer timer = registry.get(WebhookMetrics.LATENCY).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void theBacklogGaugesReadTheDatabaseOnEveryScrape() {
        when(events.countByStatus(WebhookEventStatus.PENDING)).thenReturn(7L);
        when(events.oldestUnattemptedDueAt(WebhookEventStatus.PENDING))
                .thenReturn(Optional.of(LocalDateTime.now().minusSeconds(90)));

        assertThat(registry.get("payflo.webhook.pending").gauge().value()).isEqualTo(7);
        assertThat(registry.get("payflo.webhook.oldest.unattempted.age").gauge().value()).isBetween(90.0, 95.0);
    }

    @Test
    void withNothingWaitingTheOldestAgeIsZero() {
        when(events.oldestUnattemptedDueAt(WebhookEventStatus.PENDING)).thenReturn(Optional.empty());

        assertThat(registry.get("payflo.webhook.oldest.unattempted.age").gauge().value()).isZero();
    }

    @Test
    void anEventDueInTheFutureIsNotLate() {
        when(events.oldestUnattemptedDueAt(WebhookEventStatus.PENDING))
                .thenReturn(Optional.of(LocalDateTime.now().plusMinutes(2)));

        assertThat(registry.get("payflo.webhook.oldest.unattempted.age").gauge().value()).isZero();
    }
}
