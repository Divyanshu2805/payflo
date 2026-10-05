package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.common_lib.util.SignerUtil;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookKafkaConsumer {

    // How long to wait before the same record is delivered again after a transient failure.
    private static final Duration REDELIVERY_DELAY = Duration.ofSeconds(5);

    private final WebhookTargetCache webhookTargetCache;
    private final ObjectMapper objectMapper;
    private final SignerUtil signerUtil;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookRetryQueue retryQueue;
    private final WebhookDlqRecorder dlqRecorder;

    @KafkaListener(topics = {
            "${app.kafka.topics.payments:payments.events}",
            "${app.kafka.topics.orders:orders.events}",
            "${app.kafka.topics.refunds:refunds.events}",
            "${app.kafka.topics.settlements:settlements.events}"
    })
    public void onWebhookEvent(ConsumerRecord<String, Map<String, Object>> record, Acknowledgment ack) {
        try {
            Map<String, Object> envelope = record.value();
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            String eventType = (String) envelope.get("eventType");

            Object merchantIdRaw = data.get("merchantId");
            if (merchantIdRaw == null) {
                log.warn("No merchantId was found, skipping event: {}", eventType);
                ack.acknowledge();
                return;
            }

            UUID merchantId = UUID.fromString(merchantIdRaw.toString());

            List<WebhookTarget> targets = webhookTargetCache.activeTargetsFor(merchantId, eventType);
            if (targets.isEmpty()) {
                log.debug("No webhook target was found, skipping event: {}", eventType);
                ack.acknowledge();
                return;
            }

            Map<String, Object> signatureData = Map.of("event", eventType, "payload", data);
            String signatureJson = objectMapper.writeValueAsString(signatureData);

            List<WebhookEvent> events = targets.stream()
                    .map(target -> WebhookEvent.builder()
                            .merchantId(merchantId)
                            .eventType(eventType)
                            .payload(data)
                            .targetUrl(target.targetUrl())
                            .signature(signerUtil.sign(signatureJson, target.webhookSecret()))
                            .status(WebhookEventStatus.PENDING)
                            .nextRetryAt(LocalDateTime.now())
                            .build())
                    .toList();

            // One transaction for every target of this record: it is either all saved or none, so a
            // redelivery after a failure can't create a second copy for a target that already had one.
            events = webhookEventRepository.saveAll(events);

            for (WebhookEvent webhookEvent : events) {
                try {
                    retryQueue.enqueue(webhookEvent.getId(), webhookEvent.getNextRetryAt());
                    log.info("Created a webhook event with id: {}", webhookEvent.getId());
                } catch (Exception queueDown) {
                    // The event is saved; the reconciler queues it once it is overdue.
                    log.warn("Could not queue webhook event {}, the reconciler will", webhookEvent.getId(), queueDown);
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            if (isTransient(e)) {
                // A dependency is down, not the record. Don't acknowledge: nack seeks back so this
                // record is delivered again after a pause. Acknowledging a later record would commit
                // past it and lose it.
                log.error("Webhook consumer hit a transient failure, will retry the record, topic: {}, offset: {}",
                        record.topic(), record.offset(), e);
                ack.nack(REDELIVERY_DELAY);
                return;
            }
            log.error("Webhook consumer failed due to logical error, Could not process the record, offset: {}", record.offset(), e);
            dlqRecorder.recordConsumerFailed(record, e.getMessage());
            ack.acknowledge();
        }
    }

    // A failure of something the consumer depends on (database, merchant-service, Redis), as opposed to
    // a record it can never process. Looks through the causes, since these are often wrapped.
    static boolean isTransient(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
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
