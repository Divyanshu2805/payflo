package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The database side of one webhook delivery attempt, in two short transactions around the HTTP call so
 * that no connection is held while a merchant's endpoint is slow.
 *
 * <p>{@link #claim} locks the event, counts the attempt and moves {@code next_retry_at} a lease into the
 * future. Anything else that tries to deliver the same event meanwhile — a duplicate queue entry, the
 * reconciler — finds it not yet due and backs off. {@link #recordSuccess} and {@link #recordFailure}
 * then write the outcome. If the process dies in between, the lease runs out and the reconciler
 * picks the event up again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryRecorder {

    // Longer than the HTTP connect + read timeouts together.
    static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(8), Duration.ofHours(24));

    static final int MAX_ATTEMPTS = 7;

    private static final int MAX_RESPONSE_BODY = 1000;

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookDlqRecorder webhookDlqRecorder;

    /** What the HTTP call needs, copied out so nothing lazy or managed leaves the transaction. */
    public record Attempt(UUID id, String targetUrl, String signature, String eventType, Map<String, Object> payload) {}

    /** What happened to a failed attempt: it is dead, or due again at {@code retryAt}. */
    public record FailureOutcome(boolean dead, LocalDateTime retryAt) {}

    @Transactional
    public Optional<Attempt> claim(UUID webhookEventId) {
        Optional<WebhookEvent> found = webhookEventRepository.findByIdForUpdate(webhookEventId);
        if (found.isEmpty()) {
            log.warn("No webhook event found for this id: {}", webhookEventId);
            return Optional.empty();
        }

        WebhookEvent event = found.get();
        if (event.getStatus() == WebhookEventStatus.DELIVERED || event.getStatus() == WebhookEventStatus.DEAD) {
            log.warn("Cannot deliver the event {} in status: {}", webhookEventId, event.getStatus());
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        if (event.getNextRetryAt() != null && event.getNextRetryAt().isAfter(now)) {
            log.debug("Webhook event {} is not due (another worker holds it, or its retry is later)", webhookEventId);
            return Optional.empty();
        }

        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptAt(now);
        event.setNextRetryAt(now.plus(CLAIM_LEASE));
        return Optional.of(new Attempt(event.getId(), event.getTargetUrl(), event.getSignature(),
                event.getEventType(), event.getPayload()));
    }

    @Transactional
    public void recordSuccess(UUID webhookEventId, int statusCode) {
        WebhookEvent event = webhookEventRepository.findByIdForUpdate(webhookEventId).orElseThrow();
        event.setLastResponseCode(statusCode);
        event.setStatus(WebhookEventStatus.DELIVERED);
        event.setDeliveredAt(LocalDateTime.now());
        event.setNextRetryAt(null);
    }

    @Transactional
    public FailureOutcome recordFailure(UUID webhookEventId, Integer statusCode, String error) {
        WebhookEvent event = webhookEventRepository.findByIdForUpdate(webhookEventId).orElseThrow();
        event.setLastResponseCode(statusCode);
        event.setLastResponseBody(truncate(error));

        if (event.getAttempts() >= MAX_ATTEMPTS) {
            event.setNextRetryAt(null);
            webhookDlqRecorder.recordAfterAttemptsExhausted(event, truncate(error));
            return new FailureOutcome(true, null);
        }

        LocalDateTime retryAt = LocalDateTime.now().plus(BACKOFF.get(event.getAttempts() - 1));
        event.setStatus(WebhookEventStatus.FAILED);
        event.setNextRetryAt(retryAt);
        webhookEventRepository.save(event);
        return new FailureOutcome(false, retryAt);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_RESPONSE_BODY ? value : value.substring(0, MAX_RESPONSE_BODY);
    }
}
