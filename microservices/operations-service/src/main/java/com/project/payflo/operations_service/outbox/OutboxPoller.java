package com.project.payflo.operations_service.outbox;

import com.project.payflo.common_lib.config.KafkaProperties;
import com.project.payflo.common_lib.enums.OutboxStatus;
import com.project.payflo.operations_service.entity.OutboxEvent;
import com.project.payflo.operations_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final OutboxResultHandler outboxResultHandler;

    // Oldest-first batches. Loading every PENDING row per poll (the old behavior) fell apart under
    // load: a 90k-row backlog was re-read every 5s and drained ~10 events/s.
    private static final int BATCH_SIZE = 500;
    // Keep draining within one run, but stay well inside the ShedLock lease (lockAtMostFor).
    private static final long MAX_RUN_MILLIS = 30_000;

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "operations-service-outbox-poller", lockAtMostFor = "1m", lockAtLeastFor = "1s")
    public void poll() {
        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        List<OutboxEvent> batch;
        do {
            batch = outboxEventRepository.findTop500ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
            publishBatch(batch);
        } while (batch.size() == BATCH_SIZE && System.currentTimeMillis() < deadline);
    }

    private void publishBatch(List<OutboxEvent> batch) {
        // Hand the whole batch to the producer first so it can pipeline and batch the sends,
        // then wait for each acknowledgement. Per-partition order is kept by the idempotent
        // producer, and delivery stays at-least-once: a row is only marked PUBLISHED once acked.
        Map<OutboxEvent, CompletableFuture<?>> inFlight = new LinkedHashMap<>();
        for (OutboxEvent event : batch) {
            try {
                String topic = kafkaProperties.topicFor(event.getAggregateType());
                String key = extractMerchantId(event.getPayload());

                Map<String, Object> envelope = Map.of(
                        "eventType", event.getEventType(),
                        "aggregateType", event.getAggregateType().name(),
                        "aggregateId", event.getAggregateId().toString(),
                        "data", event.getPayload()
                );

                inFlight.put(event, kafkaTemplate.send(topic, key, envelope));
            } catch (Exception e) {
                log.error("Outbox event failed, eventId: {}, attempts: {}", event.getId(), event.getAttempts());
                outboxResultHandler.handleEventFailed(event, String.valueOf(e.getMessage()));
            }
        }

        List<OutboxEvent> published = new ArrayList<>(inFlight.size());
        inFlight.forEach((event, future) -> {
            try {
                future.get(5, TimeUnit.SECONDS);
                published.add(event);
            } catch (Exception e) {
                log.error("Outbox event failed, eventId: {}, attempts: {}", event.getId(), event.getAttempts());
                outboxResultHandler.handleEventFailed(event, String.valueOf(e.getMessage()));
            }
        });
        if (!published.isEmpty()) {
            outboxResultHandler.handleEventsPublished(published);
        }
    }


    private String extractMerchantId(Map<String, Object> payload) {
        Object value = payload.get("merchantId");
        return value != null ? value.toString() : "unknown";
    }
}
