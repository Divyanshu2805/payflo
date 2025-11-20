package com.project.payflo.payment.gateway;

import com.project.payflo.common.enums.PaymentMethod;
import com.project.payflo.common.exception.UnsupportedPaymentMethodException;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PaymentGatewayRouter {

    private final Map<PaymentMethod, PaymentAdapter> paymentAdapters;

    public PaymentResult initiate(PaymentRequest request) {
        PaymentAdapter adapter = paymentAdapters.get(request.method());
        if (adapter == null) {
            throw new UnsupportedPaymentMethodException("UNSUPPORTED_PAYMENT_METHOD",
                    "No payment adapter registered for method: "+request.method());
        }
        return adapter.initiate(request);
    }

    public PaymentResult capture(PaymentMethod method, UUID paymentId) {
        PaymentAdapter adapter = paymentAdapters.get(method);
        if (adapter == null) {
            throw new UnsupportedPaymentMethodException("UNSUPPORTED_PAYMENT_METHOD",
                    "No payment adapter registered for method: "+method);
        }
        return adapter.capture(paymentId);
    }
}
