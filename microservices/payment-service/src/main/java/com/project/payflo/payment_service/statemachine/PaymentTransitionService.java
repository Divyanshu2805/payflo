package com.project.payflo.payment_service.statemachine;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.enums.PaymentActor;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.entity.PaymentTransitionLog;
import com.project.payflo.payment_service.repository.PaymentTransitionLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentTransitionService {

    private final PaymentTransitionLogRepository paymentTransitionLogRepository;
    private final PaymentStateMachine paymentStateMachine;
    private final MerchantContext merchantContext;
    private final MeterRegistry meterRegistry;

    public PaymentStatus apply(Payment payment, PaymentEvent event) {
        PaymentTransitionLog entry = transition(payment, event);
        paymentTransitionLogRepository.save(entry);
        return entry.getToStatus();
    }

    /**
     * The first transition of a payment that is not saved yet: validates it and moves the payment, but leaves saving
     * the log entry to {@link #saveLog}, to be called once the payment itself is saved (a log entry can't be saved
     * while the payment it points at is not, and saving the payment first and moving it after makes Hibernate insert
     * it in its old status and UPDATE it in the same flush, a second write of the row and all its indexes).
     */
    public PaymentTransitionLog applyToUnsaved(Payment payment, PaymentEvent event) {
        return transition(payment, event);
    }

    public void saveLog(PaymentTransitionLog entry) {
        paymentTransitionLogRepository.save(entry);
    }

    private PaymentTransitionLog transition(Payment payment, PaymentEvent event) {
        PaymentStatus next = paymentStateMachine.transition(payment.getStatus(), event);
        PaymentActor actor = getPaymentActor();
        PaymentTransitionLog log = PaymentTransitionLog.builder()
                .payment(payment)
                .fromStatus(payment.getStatus())
                .event(event)
                .toStatus(next)
                .actor(actor)
                .occurredAt(LocalDateTime.now())
                .build();
        payment.setStatus(next);
        // One series per (from, event, to) edge of the state machine — bounded by the transition table.
        meterRegistry.counter("payflo.payment.transitions",
                "from", String.valueOf(log.getFromStatus()), "event", event.name(), "to", next.name())
                .increment();
        return log;
    }

    private PaymentActor getPaymentActor() {
        try {
            String keyId = merchantContext.getKeyId();
            UUID merchantId = merchantContext.getMerchantId();

            if (keyId != null && !keyId.isBlank()) {
                return PaymentActor.CUSTOMER;
            } else if (merchantId != null) {
                return PaymentActor.MERCHANT;
            }
        } catch (Exception ignored) {
        }
        return PaymentActor.SYSTEM;
    }
}
