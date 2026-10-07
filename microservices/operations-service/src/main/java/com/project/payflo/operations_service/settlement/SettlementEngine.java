package com.project.payflo.operations_service.settlement;

import com.project.payflo.operations_service.client.MerchantServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@RequiredArgsConstructor
public class SettlementEngine {

    private final MerchantServiceClient merchantServiceClient;
    private final SettlementTransactionExecutor settlementTransactionExecutor;
    private final SettlementProperties properties;

    @Scheduled(cron = "0 0 23 * * *")
    @SchedulerLock(name = "operations-service-settlement-engine", lockAtMostFor = "2h", lockAtLeastFor = "1m")
    public void runScheduled() {
        log.info("Nightly settlement running..");
        run();
    }

    /** How a run went: the merchants it tried, and how many of those failed (to be picked up by the next run). */
    public record RunSummary(int merchants, int failedMerchants) {}

    public RunSummary run() {
        List<UUID> merchantIds = merchantServiceClient.listActiveMerchantIds();
        log.info("Processing the settlement for {} merchantIds", merchantIds.size());

        // At most `concurrency` merchants at a time: each holds database connections and calls three services,
        // and a few hundred at once would exhaust the pool. One merchant failing never stops the others — it is
        // logged, and its payments are simply picked up again by the next run.
        Semaphore permits = new Semaphore(properties.getConcurrency());
        AtomicInteger failed = new AtomicInteger();

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID merchantId: merchantIds) {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Settlement batch interrupted before all merchants were started");
                    break;
                }
                executorService.submit(() -> {
                    try {
                        settlementTransactionExecutor.processForMerchant(merchantId, LocalDate.now());
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.error("Settlement failed for merchantId: {}", merchantId, e);
                    } finally {
                        permits.release();
                    }
                });
            }
        } // closing the executor waits for every submitted merchant
        log.info("Settlement batch completed: {} merchants, {} failed", merchantIds.size(), failed.get());
        return new RunSummary(merchantIds.size(), failed.get());
    }

}


















