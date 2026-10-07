package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentTransitionLogRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureSimulatorTest {

    private final PaymentTransitionLogRepository logs = mock(PaymentTransitionLogRepository.class);
    private final SimulatorConfig config = new SimulatorConfig();
    private final CaptureSimulator simulator = new CaptureSimulator(config, logs);

    private static Payment payment(String processorReference) {
        return Payment.builder().id(UUID.randomUUID()).processorReference(processorReference).build();
    }

    @Test
    void anOrdinaryPaymentIsNeverRefusedAndCostsNoQuery() {
        assertThat(simulator.rejection(payment("UPI_PROCESSOR_abc"))).isEmpty();
        assertThat(simulator.rejection(payment(null))).isEmpty();

        verify(logs, never()).countByPayment_IdAndEvent(any(), any());
    }

    @Test
    void aPaymentTaggedForCaptureFailureIsRefusedOnTheFirstAttempt() {
        Payment tagged = payment("UPI_PROCESSOR_CAPTURE_FAIL_abc");
        when(logs.countByPayment_IdAndEvent(tagged.getId(), PaymentEvent.CAPTURE_FAIL)).thenReturn(0L);

        var refusal = simulator.rejection(tagged);

        assertThat(refusal).isPresent();
        assertThat(refusal.get().errorCode()).isEqualTo("CAPTURE_DECLINED");
    }

    @Test
    void theRetryGoesThroughOnceTheConfiguredFailuresHaveHappened() {
        Payment tagged = payment("CARD_PROCESSOR_CAPTURE_FAIL_abc");
        when(logs.countByPayment_IdAndEvent(tagged.getId(), PaymentEvent.CAPTURE_FAIL)).thenReturn(1L);

        assertThat(simulator.rejection(tagged)).isEmpty();
    }

    @Test
    void moreFailuresCanBeConfiguredBeforeASuccess() {
        config.getCapture().setFailuresBeforeSuccess(3);
        Payment tagged = payment("NBK_PROCESSOR_CAPTURE_FAIL_abc");

        when(logs.countByPayment_IdAndEvent(tagged.getId(), PaymentEvent.CAPTURE_FAIL)).thenReturn(2L);
        assertThat(simulator.rejection(tagged)).isPresent();

        when(logs.countByPayment_IdAndEvent(tagged.getId(), PaymentEvent.CAPTURE_FAIL)).thenReturn(3L);
        assertThat(simulator.rejection(tagged)).isEmpty();
    }

    @Test
    void aFailureRateRefusesRoughlyThatShareOfPaymentsAndTheSameOnesEveryTime() {
        config.getCapture().setFailureRate(30);
        when(logs.countByPayment_IdAndEvent(any(), any())).thenReturn(0L);

        int refused = 0;
        for (int i = 0; i < 2_000; i++) {
            Payment payment = payment("UPI_PROCESSOR_abc");
            boolean first = simulator.rejection(payment).isPresent();
            assertThat(simulator.rejection(payment).isPresent()).isEqualTo(first); // deterministic per payment
            if (first) refused++;
        }

        assertThat(refused).isBetween(480, 720); // 30% of 2000, with room for the hash's spread
    }

    @Test
    void aRateOfZeroRefusesNothing() {
        for (int i = 0; i < 500; i++) {
            assertThat(simulator.rejection(payment("UPI_PROCESSOR_abc"))).isEmpty();
        }
    }
}
