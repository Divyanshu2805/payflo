package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.util.WebhookSignatures;
import com.project.payflo.common_lib.util.WebhookUrlValidator;
import com.project.payflo.operations_service.metrics.WebhookMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Sends webhook deliveries. Deliberately not transactional: the HTTP call to the merchant can take
 * seconds, and holding a database connection for it would let one slow endpoint starve every other
 * delivery. The database work happens in {@link WebhookDeliveryRecorder}'s short transactions, one per batch.
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
    private final WebhookSecretResolver webhookSecretResolver;
    private final WebhookMetrics webhookMetrics;

    static final String EVENT_ID_HEADER = "X-PayFlo-Event-Id";

    @Value("${webhook.delivery.signature-header:X-PayFlo-Signature}")
    private String signatureHeader;

    /**
     * Sends one claimed attempt (see {@link WebhookDeliveryRecorder#claimAll}). A {@code 2xx} answer is returned as
     * its status code for the caller to record, in a batch with the others delivered alongside it; every kind of
     * failure is recorded here (retry, or dead letter) and returns empty.
     */
    public OptionalInt send(WebhookDeliveryRecorder.Attempt attempt) {
        UUID webhookEventId = attempt.id();

        // Checked again at delivery: the config was validated when saved, but a hostname can resolve
        // somewhere else by now, and configs saved before this check existed were never validated.
        try {
            webhookUrlValidator.validate(attempt.targetUrl());
        } catch (BusinessRuleViolationException blocked) {
            log.warn("Webhook target blocked for event {}: {}", webhookEventId, blocked.getMessage());
            failed(webhookEventId, null, WebhookUrlValidator.ERROR_CODE + ": " + blocked.getMessage());
            return OptionalInt.empty();
        }

        // Signed now, not when the event was created: every attempt gets its own timestamp, which the receiver checks
        // against its clock, so a captured request can't be replayed later. The body is the same on every attempt.
        String signature;
        Long timestamp = null;
        if (attempt.configId() != null && attempt.requestBody() != null) {
            Optional<String> secret;
            try {
                secret = webhookSecretResolver.secretFor(attempt.merchantId(), attempt.configId());
            } catch (Exception e) {
                log.warn("Could not get the signing secret for webhook event {}: {}", webhookEventId, e.toString());
                failed(webhookEventId, null, "SIGNING_SECRET_UNAVAILABLE");
                return OptionalInt.empty();
            }
            if (secret.isEmpty()) {
                log.warn("Webhook event {} is for a config the merchant has deleted", webhookEventId);
                abandoned(webhookEventId, "WEBHOOK_CONFIG_DELETED");
                return OptionalInt.empty();
            }
            timestamp = Instant.now().getEpochSecond();
            signature = WebhookSignatures.sign(secret.get(), timestamp, attempt.requestBody());
        } else {
            // Created before deliveries were signed at send time: the signature stored with it.
            signature = attempt.signature();
        }

        try {
            var request = restClient.post()
                    .uri(attempt.targetUrl())
                    .header(signatureHeader, signature)
                    .contentType(MediaType.APPLICATION_JSON);
            if (timestamp != null) {
                request.header(WebhookSignatures.TIMESTAMP_HEADER, String.valueOf(timestamp));
            }
            if (attempt.eventId() != null) {
                request.header(EVENT_ID_HEADER, attempt.eventId());
            }
            // The stored body, byte for byte: it is what the signature was computed over. (Rows from before the
            // body was stored fall back to serializing the payload, which may not match their signature.)
            Object body = attempt.requestBody() != null ? attempt.requestBody()
                    : Map.of("event", attempt.eventType(), "payload", attempt.payload());
            var response = request.body(body)
                    .retrieve()
                    .toBodilessEntity();

            int statusCode = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                countDelivery("delivered");
                if (attempt.eventOccurredAt() != null) {
                    webhookMetrics.recordDelivered(Duration.between(attempt.eventOccurredAt(), LocalDateTime.now()),
                            attempt.attempts() > 1);
                }
                log.debug("Successfully called the merchant for webhook event: {}", webhookEventId);
                return OptionalInt.of(statusCode);
            }
            failed(webhookEventId, statusCode, "HTTP" + statusCode);
        } catch (RestClientResponseException e) { // a 4xx or 5xx answer
            failed(webhookEventId, e.getStatusCode().value(), "HTTP" + e.getStatusCode().value());
        } catch (RestClientException e) { // could not connect, timed out, ...
            log.warn("Webhook delivery failed for event {}: {}", webhookEventId, e.getMessage());
            failed(webhookEventId, null, e.getMessage());
        }
        return OptionalInt.empty();
    }

    // No retry can help: dead-letter it now, so the merchant sees it (and can replay it) instead of 7 attempts failing.
    private void abandoned(UUID webhookEventId, String error) {
        recorder.recordPermanentFailure(webhookEventId, error);
        countDelivery("dead");
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
