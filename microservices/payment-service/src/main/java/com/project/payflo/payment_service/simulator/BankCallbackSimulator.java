package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.AuthorizationResolution;
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

    @Scheduled(fixedDelayString = "${payment.simulator.poll-interval-ms:1000}")
    @SchedulerLock(name = "payment-service-bank-callback-simulator", lockAtMostFor = "1m", lockAtLeastFor = "500ms")
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
            if (!candidates.isEmpty()) {
                log.info("Simulated bank callbacks: {} candidates, {} resolved", candidates.size(), resolved);
            }
        } while (candidates.size() == BATCH_SIZE && resolved > 0 && System.currentTimeMillis() < deadline);
    }

    // The bank's answer to every payment that is due, resolved in batches: each batch is ONE transaction (one lock
    // query, a few batched writes, one commit), and `concurrency` batches run at once, each holding one database
    // connection. One transaction per payment spent most of its time on round trips and log flushes, and fell
    // behind the arrival rate under load.
    private int simulateCallbacks(List<Payment> candidates) {
        List<AuthorizationResolution> due = new ArrayList<>();
        for (Payment payment : candidates) {
            AuthorizationResolution answer = answerFor(payment);
            if (answer != null) {
                due.add(answer);
            }
        }
        if (due.isEmpty()) {
            return 0;
        }

        int batchSize = Math.max(1, simulatorConfig.getBatchSize());
        Semaphore permits = new Semaphore(Math.max(1, simulatorConfig.getConcurrency()));
        List<Future<Integer>> batches = new ArrayList<>();
        int resolved = 0;
        try {
            for (int from = 0; from < due.size(); from += batchSize) {
                List<AuthorizationResolution> batch = due.subList(from, Math.min(from + batchSize, due.size()));
                permits.acquire();
                batches.add(callbackExecutor.submit(() -> {
                    try {
                        return resolveBatch(batch);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<Integer> batch : batches) {
                resolved += batch.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Bank callback simulation run failed", e);
        }
        return resolved;
    }

    // A batch is all or nothing, so when one fails (a payment changed under it, a deadlock) its payments are
    // resolved one at a time instead: the bad one fails alone and the rest still go through.
    private int resolveBatch(List<AuthorizationResolution> batch) {
        try {
            paymentService.resolveAuthorizations(batch);
            return batch.size();
        } catch (Exception e) {
            log.warn("Resolving a batch of {} payments failed, resolving them one at a time: {}", batch.size(), e.toString());
        }
        int resolved = 0;
        for (AuthorizationResolution answer : batch) {
            try {
                paymentService.resolveAuthorization(answer.paymentId(), answer.approve(), answer.bankRef(),
                        answer.errorCode(), answer.errorDescription());
                resolved++;
            } catch (Exception e) {
                log.error("Bank callback simulation failed, paymentId: {}", answer.paymentId(), e);
            }
        }
        return resolved;
    }

    // What the bank says about this payment, or null while it isn't due (or, in TIMEOUT mode, never).
    private AuthorizationResolution answerFor(Payment payment) {
        SimulatorConfig.MethodSimulatorConfig methodConfig = simulatorConfig.configFor(payment.getMethod());

        LocalDateTime dueAt = dueAt(payment, methodConfig);

        if(LocalDateTime.now().isBefore(dueAt)) {
            return null;
        }

        ChaosMode chaosMode = simulatorConfig.getChaosMode();

        return switch (chaosMode) {
            case SUCCESS -> approved(payment);
            case FAILURE -> declined(payment);
            case TIMEOUT -> {
                log.debug("BankCallback simulator: Payment Timed out");
                yield null;
            }
            case NORMAL, SLOW -> shouldApprove(payment, methodConfig) ? approved(payment) : declined(payment);
        };
    }

    private AuthorizationResolution approved(Payment payment) {
        return AuthorizationResolution.approved(payment.getId(), "SIM_BANK_REF" + RandomizerUtil.randomBase64(8));
    }

    private AuthorizationResolution declined(Payment payment) {
        return AuthorizationResolution.declined(payment.getId(), "SIM_BANK_ERROR_CODE", "Simulated Bank Decline");
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
