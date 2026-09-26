package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.util.WebhookUrlValidator;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliverExecutor {

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookRetryQueue webhookRetryQueue;
    private final RestClient restClient;
    private final WebhookDlqRecorder webhookDlqRecorder;
    private final MeterRegistry meterRegistry;
    private final WebhookUrlValidator webhookUrlValidator;

    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(8), Duration.ofHours(24));

    private final int MAX_ATTEMPTS = 7;

    @Value("${webhook.delivery.signature-header:X-PayFlo-Signature}")
    private String signatureHeader;

    @Transactional
    public void deliver(UUID webhookEventId) {
        Optional<WebhookEvent> webhookEvent = webhookEventRepository.findById(webhookEventId);

        if(webhookEvent.isEmpty()) {
            log.warn("No webhook event found for this id: {}", webhookEventId);
            return;
        }

        WebhookEvent event = webhookEvent.get();

        if(event.getStatus() == WebhookEventStatus.DELIVERED || event.getStatus() == WebhookEventStatus.DEAD) {
            log.warn("Cannot deliver the event {} in status: {}", webhookEventId, event.getStatus());
            return;
        }

        event.setAttempts(event.getAttempts()+1);
        event.setLastAttemptAt(LocalDateTime.now());

        // Checked again at delivery: the config was validated when saved, but a hostname can resolve
        // somewhere else by now, and configs saved before this check existed were never validated.
        try {
            webhookUrlValidator.validate(event.getTargetUrl());
        } catch (BusinessRuleViolationException blocked) {
            log.warn("Webhook target blocked for event {}: {}", webhookEventId, blocked.getMessage());
            handleAttemptFailed(event, WebhookUrlValidator.ERROR_CODE + ": " + blocked.getMessage());
            return;
        }

        try {
            var response = restClient.post()
                    .uri(event.getTargetUrl())
                    .header(signatureHeader, event.getSignature())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "event", event.getEventType(), "payload", event.getPayload()
                    )).retrieve()
                    .toBodilessEntity();

            int statusCode = response.getStatusCode().value();
            event.setLastResponseCode(statusCode);

            if (response.getStatusCode().is2xxSuccessful()) {
                event.setStatus(WebhookEventStatus.DELIVERED);
                event.setDeliveredAt(LocalDateTime.now());
                webhookEventRepository.save(event);
                countDelivery("delivered");
                log.info("Successfully called the merchant for webhook event: {}", webhookEventId);
                return;
            }

            handleAttemptFailed(event, "HTTP"+statusCode);

        } catch (RestClientException e) {
            event.setLastResponseBody(e.getMessage());
            handleAttemptFailed(event, e.getMessage());
            log.error("Got RestClientException", e);
        }
    }

    private void handleAttemptFailed(WebhookEvent event, String error) {
        event.setLastResponseBody(error);

        if (event.getAttempts() >= MAX_ATTEMPTS) {
            event.setStatus(WebhookEventStatus.DEAD);
            webhookDlqRecorder.recordAfterAttemptsExhausted(event, error);
            countDelivery("dead");
            return;
        }

        Duration backoff = BACKOFF.get(event.getAttempts()-1);
        LocalDateTime nextRetryAt = LocalDateTime.now().plus(backoff);
        event.setStatus(WebhookEventStatus.FAILED);
        event.setNextRetryAt(nextRetryAt);
        webhookEventRepository.save(event);

        webhookRetryQueue.enqueue(event.getId(), nextRetryAt);
        countDelivery("retry");

        log.error("Handling attempt failed for webhook event {} with attempts: {}, Next Retry at: {}",
                event.getId(), event.getAttempts(), nextRetryAt);
    }

    private void countDelivery(String outcome) {
        meterRegistry.counter("payflo.webhook.deliveries", "outcome", outcome).increment();
    }
}
