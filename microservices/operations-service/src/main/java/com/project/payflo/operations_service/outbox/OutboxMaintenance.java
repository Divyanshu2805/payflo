package com.project.payflo.operations_service.outbox;

import com.project.payflo.operations_service.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Keeps the outbox table healthy: puts events that exhausted their immediate attempts back in the queue
 * after a pause, and deletes published events once they are past retention. Without the purge the table
 * only ever grows, and every poll and index gets slower with it.
 */
@Slf4j
@Component
public class OutboxMaintenance {

    private static final int PURGE_BATCH_SIZE = 5_000;
    // Keep one run well inside the ShedLock lease (lockAtMostFor).
    private static final long MAX_PURGE_MILLIS = 60_000;

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final int retentionDays;
    private final int requeueFailedAfterMinutes;

    public OutboxMaintenance(OutboxEventRepository outboxEventRepository,
                             TransactionTemplate transactionTemplate,
                             @Value("${app.outbox.retention-days:7}") int retentionDays,
                             @Value("${app.outbox.requeue-failed-after-minutes:5}") int requeueFailedAfterMinutes) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.retentionDays = retentionDays;
        this.requeueFailedAfterMinutes = requeueFailedAfterMinutes;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "operations-service-outbox-requeue-failed", lockAtMostFor = "1m", lockAtLeastFor = "10s")
    public void requeueFailed() {
        LocalDateTime now = LocalDateTime.now();
        Integer requeued = transactionTemplate.execute(status ->
                outboxEventRepository.requeueFailed(now.minusMinutes(requeueFailedAfterMinutes), now));
        if (requeued != null && requeued > 0) {
            log.warn("Put {} failed outbox events back in the queue to be published again", requeued);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "operations-service-outbox-purge", lockAtMostFor = "5m", lockAtLeastFor = "30s")
    public void purgePublished() {
        LocalDateTime before = LocalDateTime.now().minusDays(retentionDays);
        long deadline = System.currentTimeMillis() + MAX_PURGE_MILLIS;
        long total = 0;
        int deleted;
        do {
            Integer batch = transactionTemplate.execute(status ->
                    outboxEventRepository.purgePublished(before, PURGE_BATCH_SIZE));
            deleted = batch == null ? 0 : batch;
            total += deleted;
        } while (deleted == PURGE_BATCH_SIZE && System.currentTimeMillis() < deadline);
        if (total > 0) {
            log.info("Purged {} published outbox events older than {} days", total, retentionDays);
        }
    }
}
