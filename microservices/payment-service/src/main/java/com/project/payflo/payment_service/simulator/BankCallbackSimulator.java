package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Component
@Slf4j
@RequiredArgsConstructor
public class BankCallbackSimulator {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final SimulatorConfig simulatorConfig;

    private static final int BATCH_SIZE = 500;
    // Keep draining within one run, but stay well inside the ShedLock lease (lockAtMostFor).
    private static final long MAX_RUN_MILLIS = 30_000;

    private final ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Scheduled(fixedDelayString = "${payment.simulator.poll-interval-ms:5000}")
    @SchedulerLock(name = "payment-service-bank-callback-simulator", lockAtMostFor = "1m", lockAtLeastFor = "1s")
    public void processCallbacks() {
        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        List<Payment> candidates;
        int resolved;
        // Oldest first, in bounded slices, so a large backlog is worked through instead of being
        // re-read in full every poll. A full slice with nothing due yet ends the run.
        do {
            LocalDateTime globalWindow = LocalDateTime.now().minusSeconds(1);
            candidates = paymentRepository
                    .findTop500ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(PaymentStatus.AUTHORIZING, globalWindow);
            resolved = simulateCallbacks(candidates);
            log.info("Simulated bank callbacks: {} candidates, {} resolved", candidates.size(), resolved);
        } while (candidates.size() == BATCH_SIZE && resolved > 0 && System.currentTimeMillis() < deadline);
    }

    // One payment at a time (~50/s) fell far behind under load. Each callback is its own short
    // transaction, so they run on virtual threads, at most `concurrency` at once.
    private int simulateCallbacks(List<Payment> candidates) {
        Semaphore permits = new Semaphore(simulatorConfig.getConcurrency());
        List<Future<Boolean>> callbacks = new ArrayList<>(candidates.size());
        int resolved = 0;
        try {
            for (Payment payment : candidates) {
                permits.acquire();
                callbacks.add(callbackExecutor.submit(() -> {
                    try {
                        return simulateCallback(payment);
                    } catch (Exception e) {
                        log.error("Bank callback simulation failed, paymentId: {}", payment.getId(), e);
                        return false;
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<Boolean> callback : callbacks) {
                if (callback.get()) resolved++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Bank callback simulation run failed", e);
        }
        return resolved;
    }

    private boolean simulateCallback(Payment payment) {
        SimulatorConfig.MethodSimulatorConfig methodConfig = simulatorConfig.configFor(payment.getMethod());

        LocalDateTime dueAt = dueAt(payment, methodConfig);

        if(LocalDateTime.now().isBefore(dueAt)) {
            return false;
        }

        ChaosMode chaosMode = simulatorConfig.getChaosMode();

        switch (chaosMode) {
            case SUCCESS -> resolve(payment, true);
            case FAILURE -> resolve(payment, false);
            case TIMEOUT -> {
                log.debug("BankCallback simulator: Payment Timed out");
                return false;
            }
            case NORMAL, SLOW -> resolve(payment, shouldApprove(payment, methodConfig));
        }
        return true;
    }

    private void resolve(Payment payment, boolean approve) {
        if (approve) {
            String bankRef = "SIM_BANK_REF"+ RandomizerUtil.randomBase64(8);
            paymentService.resolveAuthorization(payment.getId(), true, bankRef, null, null);
        } else {
            paymentService.resolveAuthorization(payment.getId(), false, null, "SIM_BANK_ERROR_CODE", "Simulated Bank Decline");
        }
    }

    private boolean shouldApprove(Payment payment, SimulatorConfig.MethodSimulatorConfig methodConfig) {
        int bucket = Math.abs(payment.getId().hashCode()) % 100;
        return bucket < methodConfig.getSuccessRate();
    }

    private LocalDateTime dueAt(Payment payment, SimulatorConfig.MethodSimulatorConfig methodConfig) {

        int range = methodConfig.getMaxDelaySeconds() - methodConfig.getMinDelaySeconds();
        int delaySeconds = methodConfig.getMinDelaySeconds() + Math.abs(payment.getId().hashCode()) % (range+1);

        if (simulatorConfig.getChaosMode() == ChaosMode.SLOW) {
            delaySeconds *= 2;
        }

        return payment.getCreatedAt().plusSeconds(delaySeconds);
    }

}
