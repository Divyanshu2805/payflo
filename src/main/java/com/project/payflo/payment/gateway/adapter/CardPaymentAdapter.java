package com.project.payflo.payment.gateway.adapter;

import com.project.payflo.payment.gateway.PaymentAdapter;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@RequiredArgsConstructor
@Component
public class CardPaymentAdapter implements PaymentAdapter {

    @Override
    public PaymentResult initiate(PaymentRequest request) {
        // TODO: integrate with a real card processor
        return null;
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return null;
    }
}
