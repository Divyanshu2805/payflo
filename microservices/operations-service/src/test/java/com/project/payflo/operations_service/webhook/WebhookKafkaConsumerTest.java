package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.util.SignerUtil;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookKafkaConsumerTest {

    private final WebhookTargetCache targetCache = mock(WebhookTargetCache.class);
    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final WebhookRetryQueue retryQueue = mock(WebhookRetryQueue.class);
    private final WebhookDlqRecorder dlqRecorder = mock(WebhookDlqRecorder.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    private final WebhookKafkaConsumer consumer = new WebhookKafkaConsumer(
            targetCache, JsonMapper.builder().build(), new SignerUtil(), repository, retryQueue, dlqRecorder);

    private final UUID merchantId = UUID.randomUUID();

    private ConsumerRecord<String, Map<String, Object>> record() {
        Map<String, Object> value = Map.of("eventType", "PAYMENT_STATUS_CHANGED",
                "data", Map.of("merchantId", merchantId.toString(), "paymentId", "p1"));
        return new ConsumerRecord<>("payments.events", 0, 42L, merchantId.toString(), value);
    }

    private static WebhookTarget target(String url) {
        return new WebhookTarget(UUID.randomUUID(), url, "secret");
    }

    private void withTargets(WebhookTarget... targets) {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString())).thenReturn(List.of(targets));
        when(repository.saveAll(any())).thenAnswer(inv -> {
            List<WebhookEvent> events = inv.getArgument(0);
            events.forEach(e -> e.setId(UUID.randomUUID()));
            return events;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesEveryTargetInOneBatchQueuesThemAndAcknowledges() {
        withTargets(target("https://a.example/hook"), target("https://b.example/hook"));

        consumer.onWebhookEvent(record(), ack);

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        verify(retryQueue, times(2)).enqueue(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void aRecordWithNoTargetsIsAcknowledgedAndNothingIsSaved() {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString())).thenReturn(List.of());

        consumer.onWebhookEvent(record(), ack);

        verify(repository, never()).saveAll(any());
        verify(ack).acknowledge();
    }

    @Test
    void aDatabaseFailureIsNotAcknowledgedSoTheRecordIsDeliveredAgain() {
        withTargets(target("https://a.example/hook"));
        // doThrow: `when(repository.saveAll(..))` would run the answer stubbed by withTargets again
        doThrow(new QueryTimeoutException("db down")).when(repository).saveAll(any());

        consumer.onWebhookEvent(record(), ack);

        verify(ack).nack(any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(dlqRecorder, never()).recordConsumerFailed(any(), anyString());
    }

    @Test
    void aMerchantServiceOutageIsRetriedNotDeadLettered() {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString()))
                .thenThrow(new RuntimeException("lookup failed", new IOException("connection refused")));

        consumer.onWebhookEvent(record(), ack);

        verify(ack).nack(any(Duration.class));
        verify(dlqRecorder, never()).recordConsumerFailed(any(), anyString());
    }

    @Test
    void aRecordThatCanNeverBeProcessedGoesToTheDlqAndIsAcknowledged() {
        ConsumerRecord<String, Map<String, Object>> malformed =
                new ConsumerRecord<>("payments.events", 0, 7L, "k", Map.of("eventType", "X"));

        consumer.onWebhookEvent(malformed, ack);

        verify(dlqRecorder).recordConsumerFailed(any(), any());
        verify(ack).acknowledge();
        verify(ack, never()).nack(any(Duration.class));
    }

    @Test
    void aQueueFailureDoesNotLoseTheRecordBecauseTheReconcilerRecoversIt() {
        withTargets(target("https://a.example/hook"));
        doThrow(new RuntimeException("redis down")).when(retryQueue).enqueue(any(), any());

        consumer.onWebhookEvent(record(), ack);

        verify(repository).saveAll(any());
        verify(ack).acknowledge();
        verify(ack, never()).nack(any(Duration.class));
    }

    @Test
    void classifiesWhichFailuresAreWorthRetrying() {
        assertThat(WebhookKafkaConsumer.isTransient(new QueryTimeoutException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new CannotCreateTransactionException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new IOException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new RuntimeException("wrapped", new IOException("x")))).isTrue();

        assertThat(WebhookKafkaConsumer.isTransient(new IllegalArgumentException("bad uuid"))).isFalse();
        assertThat(WebhookKafkaConsumer.isTransient(new NullPointerException())).isFalse();
    }
}
