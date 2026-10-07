package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.entity.Refund;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.service.RefundProcessingService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

// Stands in for the bank's asynchronous answer to refunds, as BankCallbackSimulator does for payments.
@Slf4j
@Component
public class RefundResolver {

    private static final int BATCH_SIZE = 200;
    // Keep one run well inside the ShedLock lease (lockAtMostFor).
    private static final long MAX_RUN_MILLIS = 30_000;

    private final RefundRepository refundRepository;
    private final RefundProcessingService refundProcessingService;
    private final int delaySeconds;

    public RefundResolver(RefundRepository refundRepository,
                          RefundProcessingService refundProcessingService,
                          @Value("${payment.refund.delay-seconds:3}") int delaySeconds) {
        this.refundRepository = refundRepository;
        this.refundProcessingService = refundProcessingService;
        this.delaySeconds = delaySeconds;
    }

    @Scheduled(fixedDelayString = "${payment.refund.poll-interval-ms:5000}")
    @SchedulerLock(name = "payment-service-refund-resolver", lockAtMostFor = "1m", lockAtLeastFor = "1s")
    public void resolveDue() {
        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        LocalDateTime createdBefore = LocalDateTime.now().minusSeconds(delaySeconds);
        List<Refund> batch;
        int resolved;
        do {
            batch = refundRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(RefundStatus.PENDING, createdBefore);
            resolved = 0;
            for (Refund refund : batch) {
                try {
                    if (refundProcessingService.resolve(refund.getId(), createdBefore)) resolved++;
                } catch (Exception e) {
                    log.error("Could not resolve refund {}", refund.getId(), e);
                }
            }
            if (resolved > 0) {
                log.info("Resolved {} of {} pending refunds", resolved, batch.size());
            }
        } while (batch.size() == BATCH_SIZE && resolved > 0 && System.currentTimeMillis() < deadline);
    }
}
