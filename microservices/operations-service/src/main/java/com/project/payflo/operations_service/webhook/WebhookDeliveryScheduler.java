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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private ExecutorService virtualThreadExecutor;

    @PostConstruct
    void init() {
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    void shutdown() {
        virtualThreadExecutor.shutdown();
    }

    @Value("${app.webhook.delivery.poll-batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "operations-service-webhook-delivery-poll-and-deliver", lockAtMostFor = "10s", lockAtLeastFor = "1s")
    public void pollAndDeliver() {
        Set<UUID> due = retryQueue.pollDue(batchSize);

        if (due.isEmpty()) return;

        for (UUID webhookEventId: due) {
            virtualThreadExecutor.submit(() -> {
                try {
                    deliverExecutor.deliver(webhookEventId);
                } catch (Exception e) {
                    // Whatever went wrong, the event keeps its lease and the reconciler will retry it.
                    log.error("Webhook delivery crashed for event {}", webhookEventId, e);
                }
            });
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
