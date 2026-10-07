package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import com.project.payflo.payment_service.repository.PaymentTransitionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The acquirer's answer to a capture, which the adapters would otherwise always approve. Without it the
 * {@code CAPTURE_FAIL} transition and the manual capture endpoint could never be reached.
 *
 * <p>A payment's capture is refused when its processor reference carries {@link #FAIL_TAG} (the processors
 * stamp it for the test values in docs/api/mock-acquirer.md) or when its id falls inside
 * {@code payment.simulator.capture.failure-rate}. It is refused {@code failures-before-success} times, counted
 * from the transition log, and then goes through, so a merchant's retry can succeed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaptureSimulator {

    /** Carried in a processor reference by the processors' capture-failure test values. */
    public static final String FAIL_TAG = "CAPTURE_FAIL";

    public static final String ERROR_CODE = "CAPTURE_DECLINED";

    private final SimulatorConfig simulatorConfig;
    private final PaymentTransitionLogRepository transitionLogs;

    public Optional<PaymentResult.Failure> rejection(Payment payment) {
        if (!isMarkedToFail(payment)) {
            return Optional.empty();
        }
        long refusedSoFar = transitionLogs.countByPayment_IdAndEvent(payment.getId(), PaymentEvent.CAPTURE_FAIL);
        if (refusedSoFar >= simulatorConfig.getCapture().getFailuresBeforeSuccess()) {
            return Optional.empty();
        }
        log.debug("Capture simulator: refusing capture {} of payment {}", refusedSoFar + 1, payment.getId());
        return Optional.of(new PaymentResult.Failure(ERROR_CODE,
                "The bank declined the capture; the authorization is still held and the capture can be retried"));
    }

    private boolean isMarkedToFail(Payment payment) {
        String reference = payment.getProcessorReference();
        if (reference != null && reference.contains(FAIL_TAG)) {
            return true;
        }
        int rate = simulatorConfig.getCapture().getFailureRate();
        // A different hash from the approval decision's, or the payments refused here would be the ones approved.
        return rate > 0 && Math.floorMod(payment.getId().hashCode() * 31 + 17, 100) < rate;
    }
}
