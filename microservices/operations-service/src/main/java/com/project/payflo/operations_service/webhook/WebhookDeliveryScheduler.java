package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryScheduler {

    // An event is only re-queued by the reconciler once its retry time is this far in the past, so
    // an event still being delivered (it holds a lease in the future) is never delivered twice.
    private static final long RECONCILE_GRACE_SECONDS = 30;

    private final WebhookRetryQueue retryQueue;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookDeliverExecutor deliverExecutor;
    private final WebhookDeliveryRecorder recorder;

    // One run keeps draining the queue for at most this long, well inside the ShedLock lease.
    private static final long RUN_MILLIS = 5_000;

    private ExecutorService virtualThreadExecutor;
    private Semaphore batches;

    // The most events taken off the queue (and so claimed, delivered and recorded together) as one batch.
    @Value("${app.webhook.delivery.poll-batch-size:100}")
    private int batchSize = 100;

    // Batches in flight at once. A batch needs the database only twice, briefly (to claim its events and to record
    // what was delivered), never during the HTTP calls, so this is also the most connections delivery uses.
    @Value("${app.webhook.delivery.concurrency:4}")
    private int concurrency = 4;

    // HTTP calls to merchants in flight at once, across all batches. A batch of 100 sent all at once is a burst of 100
    // connections to endpoints that were built for a few: a merchant's server that refuses or stalls under it would
    // fail the lot, and each failure waits a minute for its retry.
    @Value("${app.webhook.delivery.http-concurrency:32}")
    private int httpConcurrency = 32;

    private Semaphore httpSlots;

    @PostConstruct
    void init() {
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        batches = new Semaphore(Math.max(1, concurrency));
        httpSlots = new Semaphore(Math.max(1, httpConcurrency));
    }

    @PreDestroy
    void shutdown() {
        virtualThreadExecutor.shutdown();
    }

    // Drains what is due, as fast as the batches can take it, then stops until the next run. It used to take one batch
    // of 100 a second and deliver them one transaction pair each, which capped delivery at ~100 a second however idle
    // the machine was and, when the load was higher, let the backlog (and so the delivery time) grow without bound.
    // A batch is claimed in one transaction, delivered concurrently on virtual threads, and its successes recorded in
    // one UPDATE: the database cost of a delivery is a hundredth of what it was, and a slow merchant endpoint only holds
    // up its own batch. It only takes a batch when it has a free slot for it, so events are not left waiting in memory.
    @Scheduled(fixedDelay = 200)
    @SchedulerLock(name = "operations-service-webhook-delivery-poll-and-deliver", lockAtMostFor = "30s", lockAtLeastFor = "100ms")
    public void pollAndDeliver() throws InterruptedException {
        long deadline = System.currentTimeMillis() + RUN_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!batches.tryAcquire(5, TimeUnit.MILLISECONDS)) {
                continue; // every batch slot is busy
            }
            Set<UUID> due;
            try {
                due = retryQueue.pollDue(batchSize);
            } catch (RuntimeException e) {
                batches.release();
                throw e;
            }
            if (due.isEmpty()) {
                batches.release();
                return;
            }
            virtualThreadExecutor.submit(() -> {
                try {
                    deliverBatch(due);
                } catch (Exception e) {
                    // Whatever went wrong, the events keep their lease and the reconciler will retry them.
                    log.error("Webhook delivery of a batch of {} crashed", due.size(), e);
                } finally {
                    batches.release();
                }
            });
        }
    }

    void deliverBatch(Set<UUID> due) throws InterruptedException {
        List<WebhookDeliveryRecorder.Attempt> attempts = recorder.claimAll(due);
        if (attempts.isEmpty()) {
            return;
        }

        Map<Integer, List<UUID>> delivered = new ConcurrentHashMap<>();
        List<Callable<Void>> sends = new ArrayList<>(attempts.size());
        for (WebhookDeliveryRecorder.Attempt attempt : attempts) {
            sends.add(() -> {
                httpSlots.acquire();
                try {
                    deliverExecutor.send(attempt).ifPresent(statusCode ->
                            delivered.computeIfAbsent(statusCode, code -> new CopyOnWriteArrayList<>()).add(attempt.id()));
                } catch (Exception e) {
                    // The event keeps its lease and the reconciler will retry it once that has run out.
                    log.error("Webhook delivery crashed for event {}", attempt.id(), e);
                } finally {
                    httpSlots.release();
                }
                return null;
            });
        }
        virtualThreadExecutor.invokeAll(sends);

        if (!delivered.isEmpty()) {
            try {
                recorder.recordDelivered(delivered);
            } catch (Exception e) {
                // Delivered but not recorded: the lease runs out and the reconciler delivers them once more (delivery
                // is at-least-once, and a receiver drops the repeat by its event id).
                log.error("Could not record {} delivered webhook events", delivered.values().stream().mapToInt(List::size).sum(), e);
            }
        }
    }

    // The Redis queue is only a fast path; the database is the truth. Anything PENDING or FAILED whose
    // retry time has passed without being picked up (a lost queue entry, a restart, a crash mid-delivery)
    // is put back on the queue here, so no event is stranded.
    @Scheduled(fixedDelay = 10000)
    @SchedulerLock(name = "operations-service-webhook-delivery-reconcile-from-db", lockAtMostFor = "10s", lockAtLeastFor = "1s")
    public void reconcileFromDatabase() {
        LocalDateTime overdueBefore = LocalDateTime.now().minusSeconds(RECONCILE_GRACE_SECONDS);
        List<WebhookEvent> due = webhookEventRepository
                .findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(
                        List.of(WebhookEventStatus.PENDING, WebhookEventStatus.FAILED), overdueBefore);

        for (WebhookEvent event: due) {
            retryQueue.enqueueIfAbsent(event.getId(), event.getNextRetryAt());
        }
        if (!due.isEmpty()) {
            log.info("Reconciled {} overdue webhook events back onto the retry queue", due.size());
        }
    }

}
