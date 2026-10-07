package com.project.payflo.payment_service.gateway;

import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.gateway.dto.PaymentRequest;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import com.project.payflo.payment_service.simulator.CaptureSimulator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class PaymentGatewayRouter {

    private final Map<PaymentMethod, PaymentAdapter> paymentAdapters;
    private final CaptureSimulator captureSimulator;

    public PaymentResult initiate(PaymentRequest request) {
        PaymentAdapter adapter = paymentAdapters.get(request.method());
        if (adapter == null) {
            throw new IllegalArgumentException("No payment adapter registered for method: "+request.method());
        }
        return adapter.initiate(request);
    }

    public PaymentResult capture(Payment payment) {
        PaymentAdapter adapter = paymentAdapters.get(payment.getMethod());
        if (adapter == null) {
            throw new IllegalArgumentException("No payment adapter registered for method: "+payment.getMethod());
        }
        // The acquirer is simulated, so its refusal is decided here; a real adapter would return its own
        // Failure from capture() and this check would go.
        return captureSimulator.rejection(payment)
                .<PaymentResult>map(refusal -> refusal)
                .orElseGet(() -> adapter.capture(payment.getId()));
    }
}
