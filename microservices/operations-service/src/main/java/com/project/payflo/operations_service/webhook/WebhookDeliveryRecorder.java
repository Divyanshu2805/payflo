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
import java.util.ArrayList;
import java.util.Collection;
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
    public record Attempt(UUID id, UUID merchantId, UUID configId, String targetUrl, String signature, String eventType,
                          Map<String, Object> payload, String requestBody, String eventId,
                          LocalDateTime eventOccurredAt, int attempts) {}

    /** What happened to a failed attempt: it is dead, or due again at {@code retryAt}. */
    public record FailureOutcome(boolean dead, LocalDateTime retryAt) {}

    @Transactional
    public Optional<Attempt> claim(UUID webhookEventId) {
        Optional<WebhookEvent> found = webhookEventRepository.findByIdForUpdate(webhookEventId);
        if (found.isEmpty()) {
            log.warn("No webhook event found for this id: {}", webhookEventId);
            return Optional.empty();
        }
        return claimOne(found.get(), LocalDateTime.now());
    }

    /**
     * Claims many events in ONE transaction (one lock query, one commit): what the scheduler does for each batch it takes
     * off the queue. Returns the attempts that can go ahead; an event that is already delivered, dead or not due is
     * left out, as in {@link #claim}.
     */
    @Transactional
    public List<Attempt> claimAll(Collection<UUID> webhookEventIds) {
        LocalDateTime now = LocalDateTime.now();
        List<Attempt> attempts = new ArrayList<>(webhookEventIds.size());
        for (WebhookEvent event : webhookEventRepository.findAllByIdForUpdate(webhookEventIds)) {
            claimOne(event, now).ifPresent(attempts::add);
        }
        return attempts;
    }

    private Optional<Attempt> claimOne(WebhookEvent event, LocalDateTime now) {
        if (event.getStatus() == WebhookEventStatus.DELIVERED || event.getStatus() == WebhookEventStatus.DEAD) {
            log.warn("Cannot deliver the event {} in status: {}", event.getId(), event.getStatus());
            return Optional.empty();
        }

        if (event.getNextRetryAt() != null && event.getNextRetryAt().isAfter(now)) {
            log.debug("Webhook event {} is not due (another worker holds it, or its retry is later)", event.getId());
            return Optional.empty();
        }

        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptAt(now);
        event.setNextRetryAt(now.plus(CLAIM_LEASE));
        return Optional.of(new Attempt(event.getId(), event.getMerchantId(), event.getConfigId(), event.getTargetUrl(),
                event.getSignature(), event.getEventType(), event.getPayload(), event.getRequestBody(), event.getEventId(),
                event.getEventOccurredAt(), event.getAttempts()));
    }

    /** Marks many events delivered in one transaction: one UPDATE for each response code the merchants answered with. */
    @Transactional
    public void recordDelivered(Map<Integer, List<UUID>> idsByStatusCode) {
        LocalDateTime now = LocalDateTime.now();
        idsByStatusCode.forEach((statusCode, ids) -> webhookEventRepository.markDelivered(ids, statusCode, now));
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
        return recordFailure(webhookEventId, statusCode, error, false);
    }

    /** A failure no retry can fix (the merchant deleted the webhook config): dead-lettered at once. */
    @Transactional
    public FailureOutcome recordPermanentFailure(UUID webhookEventId, String error) {
        return recordFailure(webhookEventId, null, error, true);
    }

    private FailureOutcome recordFailure(UUID webhookEventId, Integer statusCode, String error, boolean permanent) {
        WebhookEvent event = webhookEventRepository.findByIdForUpdate(webhookEventId).orElseThrow();
        event.setLastResponseCode(statusCode);
        event.setLastResponseBody(truncate(error));

        if (permanent || event.getAttempts() >= MAX_ATTEMPTS) {
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
