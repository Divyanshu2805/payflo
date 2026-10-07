package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Turns the services' events into webhook deliveries. It receives whole batches of records (the container's listener
 * type is {@code batch}): the deliveries of every record in a poll are saved in ONE transaction and queued with one
 * Redis call, so a poll of a few hundred events costs one commit instead of one per event, which is what lets it keep
 * up with the services' event rate. If a batch can't be saved because of one bad record, the records are handled one
 * at a time instead, so that record is dead-lettered and the rest still go through.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookKafkaConsumer {

    // How long to wait before the same record is delivered again after a transient failure.
    private static final Duration REDELIVERY_DELAY = Duration.ofSeconds(5);

    private final WebhookTargetCache webhookTargetCache;
    private final ObjectMapper objectMapper;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookRetryQueue retryQueue;
    private final WebhookDlqRecorder dlqRecorder;

    @KafkaListener(topics = {
            "${app.kafka.topics.payments:payments.events}",
            "${app.kafka.topics.orders:orders.events}",
            "${app.kafka.topics.refunds:refunds.events}",
            "${app.kafka.topics.settlements:settlements.events}"
    })
    public void onWebhookEvents(List<ConsumerRecord<String, Map<String, Object>>> records, Acknowledgment ack) {
        // The fast path: every record's deliveries, one transaction.
        try {
            List<WebhookEvent> events = new ArrayList<>();
            for (ConsumerRecord<String, Map<String, Object>> record : records) {
                events.addAll(deliveriesFor(record));
            }
            saveAndQueue(events);
            ack.acknowledge();
            return;
        } catch (Exception e) {
            if (isTransient(e)) {
                // A dependency is down, not a record. Nothing from this poll was saved (the transaction rolled back), so
                // don't acknowledge any of it: nack seeks back to its first record and it is delivered again after a
                // pause. Acknowledging a later record would commit past this one and lose it.
                log.error("Webhook consumer hit a transient failure, will retry {} records from topic: {}, offset: {}",
                        records.size(), records.getFirst().topic(), records.getFirst().offset(), e);
                ack.nack(0, REDELIVERY_DELAY);
                return;
            }
            log.warn("A batch of {} webhook records could not be handled together ({}), handling them one at a time",
                    records.size(), e.toString());
        }

        // The slow path, one record at a time, as an isolated poison record needs.
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, Map<String, Object>> record = records.get(i);
            try {
                saveAndQueue(deliveriesFor(record));
            } catch (Exception e) {
                if (isTransient(e)) {
                    log.error("Webhook consumer hit a transient failure, will retry the record, topic: {}, offset: {}",
                            record.topic(), record.offset(), e);
                    // Everything before this record is done and is committed; this one and the rest come again.
                    ack.nack(i, REDELIVERY_DELAY);
                    return;
                }
                log.error("Webhook consumer failed due to logical error, Could not process the record, offset: {}", record.offset(), e);
                dlqRecorder.recordConsumerFailed(record, e.getMessage());
            }
        }
        ack.acknowledge();
    }

    /** The deliveries one record asks for (none when it has no merchant or the merchant has no matching target). */
    @SuppressWarnings("unchecked")
    private List<WebhookEvent> deliveriesFor(ConsumerRecord<String, Map<String, Object>> record) throws Exception {
        Map<String, Object> envelope = record.value();
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        String eventType = (String) envelope.get("eventType");

        Object merchantIdRaw = data.get("merchantId");
        if (merchantIdRaw == null) {
            log.warn("No merchantId was found, skipping event: {}", eventType);
            return List.of();
        }

        UUID merchantId = UUID.fromString(merchantIdRaw.toString());

        List<WebhookTarget> targets = webhookTargetCache.activeTargetsFor(merchantId, eventType);
        if (targets.isEmpty()) {
            log.debug("No webhook target was found, skipping event: {}", eventType);
            return List.of();
        }

        // The body is built and serialized exactly once, here; it is what gets signed (at send time, with a fresh
        // timestamp, see WebhookDeliverExecutor) and what gets sent byte for byte. "id" is the same for every
        // delivery of this event, so a receiver can drop duplicates.
        String eventId = envelope.get("eventId") != null ? envelope.get("eventId").toString() : UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("event", eventType);
        body.put("created", Instant.now().getEpochSecond());
        body.put("payload", data);
        String requestBody = objectMapper.writeValueAsString(body);
        LocalDateTime occurredAt = occurredAt(envelope.get("occurredAt"));

        return targets.stream()
                .map(target -> WebhookEvent.builder()
                        .merchantId(merchantId)
                        .eventType(eventType)
                        .payload(data)
                        .requestBody(requestBody)
                        .eventId(eventId)
                        .eventOccurredAt(occurredAt)
                        .configId(target.configId())
                        .targetUrl(target.targetUrl())
                        .status(WebhookEventStatus.PENDING)
                        .nextRetryAt(LocalDateTime.now())
                        .build())
                .toList();
    }

    // One transaction for everything it is given: it is either all saved or none, so a redelivery after a failure
    // can't create a second copy of a delivery that already had one. Queued afterwards, in one Redis call.
    private void saveAndQueue(List<WebhookEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<WebhookEvent> saved = webhookEventRepository.saveAll(events);
        Map<UUID, LocalDateTime> dueAt = new LinkedHashMap<>();
        for (WebhookEvent event : saved) {
            dueAt.put(event.getId(), event.getNextRetryAt());
        }
        try {
            retryQueue.enqueueAll(dueAt);
        } catch (Exception queueDown) {
            // The events are saved; the reconciler queues them once they are overdue.
            log.warn("Could not queue {} webhook events, the reconciler will", saved.size(), queueDown);
        }
    }

    // When the change happened, in epoch milliseconds; absent on events published before the field existed.
    private static LocalDateTime occurredAt(Object raw) {
        return raw instanceof Number millis
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(millis.longValue()), ZoneId.systemDefault())
                : null;
    }

    // A failure of something the consumer depends on (database, merchant-service, Redis), as opposed to
    // a record it can never process. Looks through the causes, since these are often wrapped.
    static boolean isTransient(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            // A constraint violation (a value too long, a null) fails the same way every time: retrying
            // would hold up every record behind it for good.
            if (t instanceof DataIntegrityViolationException) {
                return false;
            }
            if (t instanceof DataAccessException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof RetryableException
                    || t instanceof CallNotPermittedException
                    || t instanceof IOException
                    || t instanceof TimeoutException) {
                return true;
            }
            if (t instanceof FeignException feign && (feign.status() >= 500 || feign.status() < 0)) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }
}
