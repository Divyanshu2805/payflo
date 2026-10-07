package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
            targetCache, JsonMapper.builder().build(), repository, retryQueue, dlqRecorder);

    private final UUID merchantId = UUID.randomUUID();

    private ConsumerRecord<String, Map<String, Object>> record() {
        return record(42L);
    }

    private ConsumerRecord<String, Map<String, Object>> record(long offset) {
        Map<String, Object> value = Map.of("eventType", "PAYMENT_STATUS_CHANGED",
                "data", Map.of("merchantId", merchantId.toString(), "paymentId", "p" + offset));
        return new ConsumerRecord<>("payments.events", 0, offset, merchantId.toString(), value);
    }

    private void consume(ConsumerRecord<String, Map<String, Object>> record) {
        consumer.onWebhookEvents(List.of(record), ack);
    }

    private static WebhookTarget target(String url) {
        return new WebhookTarget(UUID.randomUUID(), url, "secret");
    }

    // What the database does for a save: gives every row an id.
    private static final org.mockito.stubbing.Answer<List<WebhookEvent>> SAVES = inv -> {
        List<WebhookEvent> events = inv.getArgument(0);
        events.forEach(e -> e.setId(UUID.randomUUID()));
        return events;
    };

    private void withTargets(WebhookTarget... targets) {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString())).thenReturn(List.of(targets));
        when(repository.saveAll(any())).thenAnswer(SAVES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesEveryTargetInOneBatchQueuesThemAndAcknowledges() {
        withTargets(target("https://a.example/hook"), target("https://b.example/hook"));

        consume(record());

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        verify(retryQueue, times(1)).enqueueAll(any());
        verify(ack).acknowledge();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWholePollIsSavedInOneTransactionAndQueuedInOneCall() {
        withTargets(target("https://a.example/hook"));

        consumer.onWebhookEvents(List.of(record(1), record(2), record(3), record(4)), ack);

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(4);
        ArgumentCaptor<Map<UUID, LocalDateTime>> queued = ArgumentCaptor.forClass(Map.class);
        verify(retryQueue, times(1)).enqueueAll(queued.capture());
        assertThat(queued.getValue()).hasSize(4);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStoredBodyIsWhatWillBeSignedAndSentAndCarriesTheStableEventId() throws Exception {
        WebhookTarget target = target("https://a.example/hook");
        withTargets(target);
        Map<String, Object> envelope = Map.of("eventId", "11111111-2222-3333-4444-555555555555",
                "eventType", "PAYMENT_STATUS_CHANGED",
                "data", Map.of("merchantId", merchantId.toString(), "paymentId", "p1"));

        consume(new ConsumerRecord<>("payments.events", 0, 1L, "k", envelope));

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        WebhookEvent event = saved.getValue().getFirst();

        // nothing is signed yet: the delivery signs these stored bytes when it sends them, with a fresh timestamp and
        // the config's secret as it is then. The secret itself is never kept with the event.
        assertThat(event.getSignature()).isNull();
        assertThat(event.getConfigId()).isEqualTo(target.configId());
        assertThat(event.getEventId()).isEqualTo("11111111-2222-3333-4444-555555555555");

        Map<String, Object> body = JsonMapper.builder().build().readValue(event.getRequestBody(), Map.class);
        assertThat(body).containsKeys("id", "event", "created", "payload");
        assertThat(body.get("id")).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(body.get("event")).isEqualTo("PAYMENT_STATUS_CHANGED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void whenTheChangeHappenedIsKeptSoTheDeliveryLatencyCanBeMeasuredFromIt() {
        withTargets(target("https://a.example/hook"));
        long occurredAt = 1_790_000_000_000L;
        Map<String, Object> envelope = Map.of("eventId", "evt-1", "eventType", "PAYMENT_STATUS_CHANGED",
                "occurredAt", occurredAt, "data", Map.of("merchantId", merchantId.toString()));

        consume(new ConsumerRecord<>("payments.events", 0, 1L, "k", envelope));

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue().getFirst().getEventOccurredAt()).isEqualTo(
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(occurredAt), ZoneId.systemDefault()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEnvelopeFromBeforeTheTimestampExistedIsStillAcceptedWithoutOne() {
        withTargets(target("https://a.example/hook"));

        consume(record());

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue().getFirst().getEventOccurredAt()).isNull();
        verify(ack).acknowledge();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTargetOfAnEventSharesItsEventIdAndEachRemembersItsOwnConfig() {
        withTargets(new WebhookTarget(UUID.randomUUID(), "https://a.example/hook", "secret-a"),
                new WebhookTarget(UUID.randomUUID(), "https://b.example/hook", "secret-b"));
        Map<String, Object> envelope = Map.of("eventId", "evt-1", "eventType", "ORDER_CREATED",
                "data", Map.of("merchantId", merchantId.toString()));

        consume(new ConsumerRecord<>("orders.events", 0, 1L, "k", envelope));

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        List<WebhookEvent> events = saved.getValue();
        assertThat(events).extracting(WebhookEvent::getEventId).containsOnly("evt-1");
        assertThat(events.get(0).getConfigId()).isNotNull().isNotEqualTo(events.get(1).getConfigId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOlderEnvelopeWithoutAnEventIdStillGetsOne() {
        withTargets(target("https://a.example/hook"));

        consume(record());

        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue().getFirst().getEventId()).isNotBlank();
    }

    @Test
    void aRecordWithNoTargetsIsAcknowledgedAndNothingIsSaved() {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString())).thenReturn(List.of());

        consume(record());

        verify(repository, never()).saveAll(any());
        verify(ack).acknowledge();
    }

    @Test
    void aDatabaseFailureIsNotAcknowledgedSoTheWholePollIsDeliveredAgain() {
        withTargets(target("https://a.example/hook"));
        // doThrow: `when(repository.saveAll(..))` would run the answer stubbed by withTargets again
        doThrow(new QueryTimeoutException("db down")).when(repository).saveAll(any());

        consumer.onWebhookEvents(List.of(record(1), record(2)), ack);

        // back to the first record of the poll: nothing in it was saved
        verify(ack).nack(eq(0), any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(dlqRecorder, never()).recordConsumerFailed(any(), anyString());
    }

    @Test
    void aMerchantServiceOutageIsRetriedNotDeadLettered() {
        when(targetCache.activeTargetsFor(eq(merchantId), anyString()))
                .thenThrow(new RuntimeException("lookup failed", new IOException("connection refused")));

        consume(record());

        verify(ack).nack(eq(0), any(Duration.class));
        verify(dlqRecorder, never()).recordConsumerFailed(any(), anyString());
    }

    @Test
    void aRecordThatCanNeverBeProcessedGoesToTheDlqAndIsAcknowledged() {
        ConsumerRecord<String, Map<String, Object>> malformed =
                new ConsumerRecord<>("payments.events", 0, 7L, "k", Map.of("eventType", "X"));

        consume(malformed);

        verify(dlqRecorder).recordConsumerFailed(any(), any());
        verify(ack).acknowledge();
        verify(ack, never()).nack(anyInt(), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onePoisonRecordInAPollIsDeadLetteredAndTheOthersStillGoThrough() {
        withTargets(target("https://a.example/hook"));
        ConsumerRecord<String, Map<String, Object>> malformed =
                new ConsumerRecord<>("payments.events", 0, 7L, "k", Map.of("eventType", "X"));

        consumer.onWebhookEvents(List.of(record(1), malformed, record(3)), ack);

        // the poison record fails the batch, so the others were handled one at a time
        verify(dlqRecorder, times(1)).recordConsumerFailed(eq(malformed), any());
        ArgumentCaptor<List<WebhookEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveAll(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(events -> assertThat(events).hasSize(1));
        verify(ack, times(1)).acknowledge();
        verify(ack, never()).nack(anyInt(), any(Duration.class));
    }

    @Test
    void aValueTooLongForItsColumnDoesNotBlockTheRecordsBehindIt() {
        withTargets(target("https://a.example/hook"));
        // the batch is refused by the database for one bad row; handled one at a time, only that one is
        doThrow(new DataIntegrityViolationException("value too long"))
                .doAnswer(SAVES)
                .doThrow(new DataIntegrityViolationException("value too long"))
                .when(repository).saveAll(any());

        consumer.onWebhookEvents(List.of(record(1), record(2)), ack);

        verify(dlqRecorder, times(1)).recordConsumerFailed(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void aFailureAfterTheFirstRecordsWereHandledCommitsThemAndRedeliversFromTheFailedOne() {
        // the batch fails for a bad record, then the database goes away while the records are handled one at a time
        withTargets(target("https://a.example/hook"));
        ConsumerRecord<String, Map<String, Object>> malformed =
                new ConsumerRecord<>("payments.events", 0, 2L, "k", Map.of("eventType", "X"));
        doAnswer(SAVES).doThrow(new QueryTimeoutException("db down")).when(repository).saveAll(any());

        consumer.onWebhookEvents(List.of(record(1), malformed, record(3)), ack);

        // record 1 was saved, the malformed one dead-lettered, and record 3 hit the outage: redeliver from index 2
        verify(ack).nack(eq(2), any(Duration.class));
        verify(ack, never()).acknowledge();
    }

    @Test
    void aQueueFailureDoesNotLoseTheRecordBecauseTheReconcilerRecoversIt() {
        withTargets(target("https://a.example/hook"));
        doThrow(new RuntimeException("redis down")).when(retryQueue).enqueueAll(any());

        consume(record());

        verify(repository).saveAll(any());
        verify(ack).acknowledge();
        verify(ack, never()).nack(anyInt(), any(Duration.class));
    }

    @Test
    void classifiesWhichFailuresAreWorthRetrying() {
        assertThat(WebhookKafkaConsumer.isTransient(new QueryTimeoutException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new CannotCreateTransactionException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new IOException("x"))).isTrue();
        assertThat(WebhookKafkaConsumer.isTransient(new RuntimeException("wrapped", new IOException("x")))).isTrue();

        // A constraint violation fails the same way every time; retrying it would block the whole partition.
        assertThat(WebhookKafkaConsumer.isTransient(new DataIntegrityViolationException("value too long"))).isFalse();
        assertThat(WebhookKafkaConsumer.isTransient(
                new RuntimeException("wrapped", new DataIntegrityViolationException("value too long")))).isFalse();

        assertThat(WebhookKafkaConsumer.isTransient(new IllegalArgumentException("bad uuid"))).isFalse();
        assertThat(WebhookKafkaConsumer.isTransient(new NullPointerException())).isFalse();
    }
}
