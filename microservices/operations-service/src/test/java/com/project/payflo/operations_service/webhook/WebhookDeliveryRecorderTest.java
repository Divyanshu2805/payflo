package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryRecorderTest {

    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final WebhookDlqRecorder dlqRecorder = mock(WebhookDlqRecorder.class);
    private final WebhookDeliveryRecorder recorder = new WebhookDeliveryRecorder(repository, dlqRecorder);

    private WebhookEvent event;

    @BeforeEach
    void aPendingEventThatIsDue() {
        event = WebhookEvent.builder()
                .id(UUID.randomUUID()).merchantId(UUID.randomUUID()).eventType("PAYMENT_STATUS_CHANGED")
                .payload(Map.of("k", "v")).targetUrl("https://example.com/hook").signature("sig")
                .status(WebhookEventStatus.PENDING).nextRetryAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
    }

    @Test
    void claimCountsTheAttemptAndHoldsTheEventForALease() {
        Optional<WebhookDeliveryRecorder.Attempt> attempt = recorder.claim(event.getId());

        assertThat(attempt).isPresent();
        assertThat(attempt.get().targetUrl()).isEqualTo("https://example.com/hook");
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    void aSecondWorkerCannotClaimAnEventThatIsAlreadyBeingDelivered() {
        assertThat(recorder.claim(event.getId())).isPresent();
        assertThat(recorder.claim(event.getId())).isEmpty();
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void anEventWhoseRetryIsLaterIsNotClaimedEarly() {
        event.setStatus(WebhookEventStatus.FAILED);
        event.setNextRetryAt(LocalDateTime.now().plusMinutes(30));

        assertThat(recorder.claim(event.getId())).isEmpty();
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void deliveredAndDeadEventsAreNeverClaimed() {
        event.setStatus(WebhookEventStatus.DELIVERED);
        assertThat(recorder.claim(event.getId())).isEmpty();
        event.setStatus(WebhookEventStatus.DEAD);
        assertThat(recorder.claim(event.getId())).isEmpty();
    }

    @Test
    void anUnknownEventIsNotClaimed() {
        assertThat(recorder.claim(UUID.randomUUID())).isEmpty();
    }

    private WebhookEvent anotherDueEvent() {
        return WebhookEvent.builder()
                .id(UUID.randomUUID()).merchantId(UUID.randomUUID()).eventType("ORDER_CREATED")
                .payload(Map.of("k", "v")).targetUrl("https://example.com/other")
                .status(WebhookEventStatus.PENDING).nextRetryAt(LocalDateTime.now().minusSeconds(1))
                .build();
    }

    @Test
    void aBatchIsClaimedInOneLockQueryWithEachEventCountedAndLeased() {
        WebhookEvent other = anotherDueEvent();
        when(repository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(event, other));

        List<WebhookDeliveryRecorder.Attempt> attempts = recorder.claimAll(List.of(event.getId(), other.getId()));

        assertThat(attempts).extracting(WebhookDeliveryRecorder.Attempt::id).containsExactly(event.getId(), other.getId());
        verify(repository, times(1)).findAllByIdForUpdate(anyCollection());
        verify(repository, never()).findByIdForUpdate(any());
        for (WebhookEvent claimed : List.of(event, other)) {
            assertThat(claimed.getAttempts()).isEqualTo(1);
            assertThat(claimed.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(1));
        }
    }

    @Test
    void aBatchLeavesOutWhatCannotBeDeliveredNow() {
        WebhookEvent delivered = anotherDueEvent();
        delivered.setStatus(WebhookEventStatus.DELIVERED);
        WebhookEvent later = anotherDueEvent();
        later.setStatus(WebhookEventStatus.FAILED);
        later.setNextRetryAt(LocalDateTime.now().plusMinutes(30));
        WebhookEvent dead = anotherDueEvent();
        dead.setStatus(WebhookEventStatus.DEAD);
        when(repository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(event, delivered, later, dead));

        List<WebhookDeliveryRecorder.Attempt> attempts = recorder.claimAll(
                List.of(event.getId(), delivered.getId(), later.getId(), dead.getId()));

        assertThat(attempts).extracting(WebhookDeliveryRecorder.Attempt::id).containsExactly(event.getId());
        assertThat(later.getAttempts()).isZero();
    }

    @Test
    void aBatchOfDeliveriesIsRecordedWithOneUpdatePerResponseCode() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        recorder.recordDelivered(Map.of(204, List.of(a, b), 200, List.of(c)));

        verify(repository).markDelivered(eq(List.of(a, b)), eq(204), any(LocalDateTime.class));
        verify(repository).markDelivered(eq(List.of(c)), eq(200), any(LocalDateTime.class));
        verify(repository, never()).findByIdForUpdate(any());
    }

    @Test
    void successMarksItDeliveredAndClearsTheRetryTime() {
        recorder.claim(event.getId());
        recorder.recordSuccess(event.getId(), 204);

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.DELIVERED);
        assertThat(event.getLastResponseCode()).isEqualTo(204);
        assertThat(event.getNextRetryAt()).isNull();
        assertThat(event.getDeliveredAt()).isNotNull();
    }

    @Test
    void aFailureSchedulesTheNextRetryWithBackoff() {
        recorder.claim(event.getId());
        WebhookDeliveryRecorder.FailureOutcome outcome = recorder.recordFailure(event.getId(), 500, "HTTP500");

        assertThat(outcome.dead()).isFalse();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
        // first failure: retry in about a minute
        assertThat(outcome.retryAt()).isBetween(LocalDateTime.now().plusSeconds(50), LocalDateTime.now().plusSeconds(70));
        assertThat(event.getNextRetryAt()).isEqualTo(outcome.retryAt());
    }

    @Test
    void theSeventhFailureMakesItDeadAndRecordsItInTheDlq() {
        event.setAttempts(WebhookDeliveryRecorder.MAX_ATTEMPTS);

        WebhookDeliveryRecorder.FailureOutcome outcome = recorder.recordFailure(event.getId(), null, "timeout");

        assertThat(outcome.dead()).isTrue();
        assertThat(event.getNextRetryAt()).isNull();
        verify(dlqRecorder).recordAfterAttemptsExhausted(event, "timeout");
    }

    @Test
    void aVeryLongErrorIsCutToTheColumnWidth() {
        recorder.claim(event.getId());
        recorder.recordFailure(event.getId(), null, "x".repeat(5000));

        assertThat(event.getLastResponseBody()).hasSize(1000);
    }

    @Test
    void aRetryIsNeverScheduledForADeadEvent() {
        event.setAttempts(WebhookDeliveryRecorder.MAX_ATTEMPTS);
        recorder.recordFailure(event.getId(), null, "boom");

        verify(repository, never()).save(event);
    }
}
