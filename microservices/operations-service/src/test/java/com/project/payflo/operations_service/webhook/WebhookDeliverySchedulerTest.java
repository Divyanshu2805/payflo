package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliverySchedulerTest {

    private final WebhookRetryQueue retryQueue = mock(WebhookRetryQueue.class);
    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final WebhookDeliverExecutor executor = mock(WebhookDeliverExecutor.class);
    private final WebhookDeliveryRecorder recorder = mock(WebhookDeliveryRecorder.class);
    private final WebhookDeliveryScheduler scheduler =
            new WebhookDeliveryScheduler(retryQueue, repository, executor, recorder);

    private static Set<UUID> ids(int count) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private static WebhookDeliveryRecorder.Attempt attempt(UUID id) {
        return new WebhookDeliveryRecorder.Attempt(id, UUID.randomUUID(), UUID.randomUUID(), "https://example.com/hook",
                null, "PAYMENT_STATUS_CHANGED", Map.of(), "{}", "evt-" + id, LocalDateTime.now(), 1);
    }

    // Every id the recorder is asked to claim comes back as an attempt, as when all of them are due.
    private void theRecorderClaimsEverything() {
        when(recorder.claimAll(anyCollection())).thenAnswer(invocation -> {
            Collection<UUID> claimed = invocation.getArgument(0);
            return claimed.stream().map(WebhookDeliverySchedulerTest::attempt).toList();
        });
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, List<UUID>> recordedAsDelivered() {
        ArgumentCaptor<Map<Integer, List<UUID>>> recorded = ArgumentCaptor.forClass(Map.class);
        verify(recorder, timeout(3000)).recordDelivered(recorded.capture());
        return recorded.getValue();
    }

    @Test
    void aBatchIsClaimedOnceDeliveredAndItsSuccessesRecordedTogether() throws Exception {
        Set<UUID> due = ids(5);
        when(retryQueue.pollDue(anyInt())).thenReturn(due).thenReturn(Set.of());
        theRecorderClaimsEverything();
        when(executor.send(any())).thenReturn(OptionalInt.of(204));
        scheduler.init();

        scheduler.pollAndDeliver();

        // the database is used twice for the whole batch, not twice per event
        assertThat(recordedAsDelivered()).containsOnlyKeys(204);
        assertThat(recordedAsDelivered().get(204)).containsExactlyInAnyOrderElementsOf(due);
        verify(recorder, times(1)).claimAll(anyCollection());
        verify(executor, times(5)).send(any());
        scheduler.shutdown();
    }

    @Test
    void keepsDrainingTheQueueWhileThereIsSomethingDue() throws Exception {
        when(retryQueue.pollDue(anyInt())).thenReturn(ids(3)).thenReturn(ids(2)).thenReturn(Set.of());
        theRecorderClaimsEverything();
        when(executor.send(any())).thenReturn(OptionalInt.of(200));
        scheduler.init();

        scheduler.pollAndDeliver();

        // one run takes batch after batch until the queue is empty: it used to take one batch a second
        verify(retryQueue, times(3)).pollDue(anyInt());
        verify(executor, timeout(3000).times(5)).send(any());
        scheduler.shutdown();
    }

    @Test
    void nothingDueMeansNothingClaimed() throws Exception {
        when(retryQueue.pollDue(anyInt())).thenReturn(Set.of());
        scheduler.init();

        scheduler.pollAndDeliver();

        verify(recorder, never()).claimAll(anyCollection());
        verify(executor, never()).send(any());
        scheduler.shutdown();
    }

    @Test
    void aDeliveryThatFailedIsNotRecordedAsDelivered() throws Exception {
        Set<UUID> due = ids(3);
        UUID failing = due.iterator().next();
        when(retryQueue.pollDue(anyInt())).thenReturn(due).thenReturn(Set.of());
        theRecorderClaimsEverything();
        // the executor has already recorded the failure itself (retry or dead letter) and answers with nothing
        when(executor.send(any())).thenAnswer(invocation -> {
            WebhookDeliveryRecorder.Attempt attempt = invocation.getArgument(0);
            return attempt.id().equals(failing) ? OptionalInt.empty() : OptionalInt.of(204);
        });
        scheduler.init();

        scheduler.pollAndDeliver();

        assertThat(recordedAsDelivered().get(204)).hasSize(2).doesNotContain(failing);
        scheduler.shutdown();
    }

    @Test
    void aSendThatCrashesDoesNotStopTheRestOfTheBatchBeingDeliveredAndRecorded() throws Exception {
        Set<UUID> due = ids(3);
        UUID crashing = due.iterator().next();
        when(retryQueue.pollDue(anyInt())).thenReturn(due).thenReturn(Set.of());
        theRecorderClaimsEverything();
        when(executor.send(any())).thenAnswer(invocation -> {
            WebhookDeliveryRecorder.Attempt attempt = invocation.getArgument(0);
            if (attempt.id().equals(crashing)) {
                throw new IllegalStateException("boom");
            }
            return OptionalInt.of(204);
        });
        scheduler.init();

        scheduler.pollAndDeliver();

        // the crashed one keeps its lease and the reconciler brings it back; the others are delivered and recorded
        assertThat(recordedAsDelivered().get(204)).hasSize(2).doesNotContain(crashing);
        scheduler.shutdown();
    }

    @Test
    void aBatchWhoseRecordFailsDoesNotCrashTheRun() throws Exception {
        when(retryQueue.pollDue(anyInt())).thenReturn(ids(2)).thenReturn(Set.of());
        theRecorderClaimsEverything();
        when(executor.send(any())).thenReturn(OptionalInt.of(204));
        doThrow(new IllegalStateException("db down")).when(recorder).recordDelivered(any());
        scheduler.init();

        scheduler.pollAndDeliver();

        // delivered but not recorded: the lease runs out and the reconciler delivers them again (at-least-once)
        verify(recorder, timeout(3000)).recordDelivered(any());
        scheduler.shutdown();
    }

    @Test
    void takesNoNewBatchWhileEverySlotIsBusy() throws Exception {
        ReflectionTestUtils.setField(scheduler, "concurrency", 1);
        scheduler.init();
        CountDownLatch release = new CountDownLatch(1);
        // the first batch holds the only slot until released, so the queue must not be polled again in the meantime
        // (an event taken off it would wait in memory, where a crash would hide it until the reconciler found it)
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(recorder).claimAll(anyCollection());
        List<Long> polledAt = new CopyOnWriteArrayList<>();
        when(retryQueue.pollDue(anyInt())).thenAnswer(invocation -> {
            polledAt.add(System.nanoTime());
            return polledAt.size() <= 2 ? ids(1) : Set.<UUID>of();
        });
        new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            release.countDown();
        }).start();

        scheduler.pollAndDeliver();

        assertThat(polledAt.size()).isGreaterThanOrEqualTo(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(polledAt.get(1) - polledAt.get(0))).isGreaterThanOrEqualTo(300);
        scheduler.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcilesPendingAndFailedEventsThatAreOverdueNotJustPendingOnes() {
        WebhookEvent failed = WebhookEvent.builder().id(UUID.randomUUID()).status(WebhookEventStatus.FAILED)
                .nextRetryAt(LocalDateTime.now().minusHours(1)).build();
        ArgumentCaptor<Collection<WebhookEventStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        when(repository.findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(any(), any()))
                .thenReturn(List.of(failed));

        scheduler.reconcileFromDatabase();

        verify(repository).findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(statuses.capture(), before.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(WebhookEventStatus.PENDING, WebhookEventStatus.FAILED);
        // only events overdue by a grace period, so one being delivered right now (it holds a lease) is left alone
        assertThat(before.getValue()).isBefore(LocalDateTime.now());
        verify(retryQueue).enqueueIfAbsent(failed.getId(), failed.getNextRetryAt());
    }
}
