package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.util.WebhookUrlValidator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * Delivers one webhook event. Deliberately not transactional: the HTTP call to the merchant can take
 * seconds, and holding a database connection for it would let one slow endpoint starve every other
 * delivery. The database work happens in {@link WebhookDeliveryRecorder}'s two short transactions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliverExecutor {

    private final WebhookDeliveryRecorder recorder;
    private final WebhookRetryQueue webhookRetryQueue;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final WebhookUrlValidator webhookUrlValidator;

    @Value("${webhook.delivery.signature-header:X-PayFlo-Signature}")
    private String signatureHeader;

    public void deliver(UUID webhookEventId) {
        var claimed = recorder.claim(webhookEventId);
        if (claimed.isEmpty()) {
            return;
        }
        WebhookDeliveryRecorder.Attempt attempt = claimed.get();

        // Checked again at delivery: the config was validated when saved, but a hostname can resolve
        // somewhere else by now, and configs saved before this check existed were never validated.
        try {
            webhookUrlValidator.validate(attempt.targetUrl());
        } catch (BusinessRuleViolationException blocked) {
            log.warn("Webhook target blocked for event {}: {}", webhookEventId, blocked.getMessage());
            failed(webhookEventId, null, WebhookUrlValidator.ERROR_CODE + ": " + blocked.getMessage());
            return;
        }

        try {
            var response = restClient.post()
                    .uri(attempt.targetUrl())
                    .header(signatureHeader, attempt.signature())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("event", attempt.eventType(), "payload", attempt.payload()))
                    .retrieve()
                    .toBodilessEntity();

            int statusCode = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                recorder.recordSuccess(webhookEventId, statusCode);
                countDelivery("delivered");
                log.info("Successfully called the merchant for webhook event: {}", webhookEventId);
            } else {
                failed(webhookEventId, statusCode, "HTTP" + statusCode);
            }
        } catch (RestClientResponseException e) { // a 4xx or 5xx answer
            failed(webhookEventId, e.getStatusCode().value(), "HTTP" + e.getStatusCode().value());
        } catch (RestClientException e) { // could not connect, timed out, ...
            log.warn("Webhook delivery failed for event {}: {}", webhookEventId, e.getMessage());
            failed(webhookEventId, null, e.getMessage());
        }
    }

    private void failed(UUID webhookEventId, Integer statusCode, String error) {
        WebhookDeliveryRecorder.FailureOutcome outcome = recorder.recordFailure(webhookEventId, statusCode, error);
        if (outcome.dead()) {
            countDelivery("dead");
            log.error("Webhook event {} is dead after exhausting its attempts: {}", webhookEventId, error);
            return;
        }

        countDelivery("retry");
        log.warn("Webhook event {} failed ({}), next retry at {}", webhookEventId, error, outcome.retryAt());
        try {
            webhookRetryQueue.enqueue(webhookEventId, outcome.retryAt());
        } catch (Exception e) {
            // The retry time is already saved; the reconciler re-queues it once it is overdue.
            log.warn("Could not queue webhook event {} for retry, the reconciler will", webhookEventId, e);
        }
    }

    private void countDelivery(String outcome) {
        meterRegistry.counter("payflo.webhook.deliveries", "outcome", outcome).increment();
    }
}
